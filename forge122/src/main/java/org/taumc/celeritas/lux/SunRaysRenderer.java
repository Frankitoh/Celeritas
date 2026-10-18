package org.taumc.celeritas.lux;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.math.MathHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.nio.FloatBuffer;

/**
 * Screen-space sun rays ("god rays").
 *
 * Cost-saving design:
 *  - exits immediately when the sun is not visible / is under the horizon / it's raining hard
 *  - only samples the DEPTH buffer (one texture tap per ray step, no color reads)
 *  - runs at a reduced internal resolution (default 1/2) and is upscaled with a linear filter
 *  - one fullscreen additive composite pass
 *
 * Runs from RenderWorldLastEvent: after the world, clouds and weather are drawn but before
 * the hand and the HUD, so the depth buffer is still intact.
 */
public final class SunRaysRenderer {
    private static final Logger LOGGER = LogManager.getLogger("Celeritas/Lux");

    private static final String VERT =
            "#version 120\n" +
            "varying vec2 vUv;\n" +
            "void main() {\n" +
            "    gl_Position = gl_Vertex;\n" +
            "    vUv = gl_Vertex.xy * 0.5 + 0.5;\n" +
            "}\n";

    private static final String RAY_FRAG_TEMPLATE =
            "#version 120\n" +
            "#define SAMPLES %d\n" +
            "uniform sampler2D uDepth;\n" +
            "uniform vec2 uSun;\n" +
            "uniform float uAspect;\n" +
            "uniform float uLength;\n" +
            "uniform float uDecay;\n" +
            "uniform float uRadius;\n" +
            "varying vec2 vUv;\n" +
            "void main() {\n" +
            "    vec2 stepV = (uSun - vUv) * (uLength / float(SAMPLES));\n" +
            "    float jitter = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);\n" +
            "    vec2 uv = vUv + stepV * jitter;\n" +
            "    float illum = 1.0;\n" +
            "    float acc = 0.0;\n" +
            "    float norm = 0.0;\n" +
            "    for (int i = 0; i < SAMPLES; i++) {\n" +
            "        uv += stepV;\n" +
            "        float d = texture2D(uDepth, uv).r;\n" +
            "        float inside = step(0.0, uv.x) * step(uv.x, 1.0) * step(0.0, uv.y) * step(uv.y, 1.0);\n" +
            "        acc += step(0.9999, d) * inside * illum;\n" +
            "        norm += illum;\n" +
            "        illum *= uDecay;\n" +
            "    }\n" +
            "    vec2 dv = vUv - uSun;\n" +
            "    dv.x *= uAspect;\n" +
            "    float falloff = 1.0 - smoothstep(0.0, uRadius, length(dv));\n" +
            "    falloff *= falloff;\n" +
            "    gl_FragColor = vec4(vec3((acc / norm) * falloff), 1.0);\n" +
            "}\n";

    private static final String COMPOSITE_FRAG =
            "#version 120\n" +
            "uniform sampler2D uRays;\n" +
            "uniform vec3 uColor;\n" +
            "varying vec2 vUv;\n" +
            "void main() {\n" +
            "    float r = texture2D(uRays, vUv).r;\n" +
            "    gl_FragColor = vec4(uColor * r, 1.0);\n" +
            "}\n";

    private static final FloatBuffer MODELVIEW = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer PROJECTION = BufferUtils.createFloatBuffer(16);
    private static final float[] mv = new float[16];
    private static final float[] pr = new float[16];

    private static LuxProgram rayProgram;
    private static LuxProgram compositeProgram;
    private static Framebuffer raysFbo;
    private static boolean failed;

    private SunRaysRenderer() {}

    public static void render(float partialTicks) {
        if (failed || !LuxConfig.sunRays) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc.world;
        if (world == null || mc.player == null || !OpenGlHelper.isFramebufferEnabled()
                || !world.provider.isSurfaceWorld()) {
            return;
        }

        // ---- cheap CPU-side early outs ----
        float rain = world.getRainStrength(partialTicks);
        float angle = world.getCelestialAngle(partialTicks) * (float) (Math.PI * 2.0);
        float sunElevation = MathHelper.cos(angle);
        float intensity = LuxConfig.sunRayIntensity
                * smoothstep(-0.02f, 0.2f, sunElevation)
                * (1.0f - 0.85f * rain);
        if (intensity < 0.01f) {
            return;
        }

        // Sun direction in world space (matches vanilla RenderGlobal.renderSky rotations).
        float dx = -MathHelper.sin(angle);
        float dy = sunElevation;
        float dz = 0.0f;

        MODELVIEW.clear();
        PROJECTION.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODELVIEW);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, PROJECTION);
        MODELVIEW.get(mv);
        PROJECTION.get(pr);

        // view = M * (d, 0); clip = P * view   (column-major, direction => translation ignored)
        float vx = mv[0] * dx + mv[4] * dy + mv[8] * dz;
        float vy = mv[1] * dx + mv[5] * dy + mv[9] * dz;
        float vz = mv[2] * dx + mv[6] * dy + mv[10] * dz;
        float cx = pr[0] * vx + pr[4] * vy + pr[8] * vz;
        float cy = pr[1] * vx + pr[5] * vy + pr[9] * vz;
        float cw = pr[3] * vx + pr[7] * vy + pr[11] * vz;
        if (cw <= 0.001f) {
            return; // sun is behind the camera
        }
        float ndcX = cx / cw;
        float ndcY = cy / cw;
        float edge = Math.max(Math.abs(ndcX), Math.abs(ndcY));
        float offscreenFade = MathHelper.clamp((1.8f - edge) / 0.8f, 0.0f, 1.0f);
        intensity *= offscreenFade;
        if (intensity < 0.01f) {
            return;
        }

        Framebuffer main = mc.getFramebuffer();
        int fw = main.framebufferWidth;
        int fh = main.framebufferHeight;
        int div = Math.max(1, LuxConfig.sunRayResolutionDivisor);
        int rw = Math.max(1, fw / div);
        int rh = Math.max(1, fh / div);

        try {
            ensureResources(main, rw, rh);
        } catch (Throwable t) {
            disable("initialization", t);
            return;
        }

        // Warm tint near the horizon, near-white at midday.
        float warm = 1.0f - smoothstep(0.0f, 0.4f, sunElevation);
        float r = lerp(1.00f, 1.00f, warm) * intensity;
        float g = lerp(0.96f, 0.55f, warm) * intensity;
        float b = lerp(0.85f, 0.25f, warm) * intensity;

        float sunU = ndcX * 0.5f + 0.5f;
        float sunV = ndcY * 0.5f + 0.5f;
        float aspect = fw / (float) fh;

        // NOTE: GL_TEXTURE_BIT deliberately excluded from the attrib mask — saving/restoring the
        // whole texture state is slow on old Intel/AMD drivers. The bindings we change are
        // restored manually below; force unit 0 because the sampler reads unit 0 regardless
        // of what vanilla left active.
        int prevActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevTex2D = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_VIEWPORT_BIT);
        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_FOG);

            // 1) scene depth (shared with ShadowRenderer, copied at most once per frame)
            if (!SceneDepth.prepare(main)) {
                return;
            }
            int depthTex = SceneDepth.texture();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);

            // 2) radial blur toward the sun at reduced resolution
            raysFbo.bindFramebuffer(true);
            rayProgram.bind();
            rayProgram.set1i("uDepth", 0);
            rayProgram.set2f("uSun", sunU, sunV);
            rayProgram.set1f("uAspect", aspect);
            rayProgram.set1f("uLength", LuxConfig.sunRayLength);
            rayProgram.set1f("uDecay", LuxConfig.sunRayDecay);
            rayProgram.set1f("uRadius", LuxConfig.sunRayRadius);
            drawFullscreenQuad();

            // 3) additive composite onto the main framebuffer
            main.bindFramebuffer(true);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, raysFbo.framebufferTexture);
            compositeProgram.bind();
            compositeProgram.set1i("uRays", 0);
            compositeProgram.set3f("uColor", r, g, b);
            drawFullscreenQuad();
        } catch (Throwable t) {
            disable("rendering", t);
        } finally {
            LuxProgram.unbind();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex2D);
            GL13.glActiveTexture(prevActiveTexture);
            main.bindFramebuffer(true);
            GL11.glPopAttrib();
        }
    }

    private static void ensureResources(Framebuffer main, int rw, int rh) {
        if (rayProgram == null) {
            rayProgram = new LuxProgram("lux_sunrays", VERT,
                    String.format(RAY_FRAG_TEMPLATE, LuxConfig.sunRaySamples));
            compositeProgram = new LuxProgram("lux_sunrays_composite", VERT, COMPOSITE_FRAG);
            LOGGER.info("Sun rays shaders compiled ({} samples, 1/{} resolution)",
                    LuxConfig.sunRaySamples, LuxConfig.sunRayResolutionDivisor);
        }
        if (raysFbo == null) {
            raysFbo = new Framebuffer(rw, rh, false);
            raysFbo.setFramebufferFilter(GL11.GL_LINEAR);
            main.bindFramebuffer(true);
        } else if (raysFbo.framebufferWidth != rw || raysFbo.framebufferHeight != rh) {
            raysFbo.createBindFramebuffer(rw, rh);
            raysFbo.setFramebufferFilter(GL11.GL_LINEAR);
            main.bindFramebuffer(true);
        }
    }

    private static void drawFullscreenQuad() {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(-1.0f, -1.0f);
        GL11.glVertex2f(1.0f, -1.0f);
        GL11.glVertex2f(1.0f, 1.0f);
        GL11.glVertex2f(-1.0f, 1.0f);
        GL11.glEnd();
    }

    private static void disable(String stage, Throwable t) {
        failed = true;
        LOGGER.error("Sun rays failed during " + stage + "; disabling the effect for this session.", t);
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = MathHelper.clamp((x - e0) / (e1 - e0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
