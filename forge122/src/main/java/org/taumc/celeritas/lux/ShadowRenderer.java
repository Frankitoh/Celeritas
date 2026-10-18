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
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;

import java.nio.FloatBuffer;

/**
 * Screen-space dynamic sun shadows.
 *
 * How it works (same depth source as the god rays, zero extra geometry passes):
 *  1. The main framebuffer's depth buffer (shared with {@link SunRaysRenderer} via
 *     {@link SceneDepth}, so it is copied at most once per frame) is sampled at reduced
 *     resolution.
 *  2. For every pixel the world-space position is reconstructed from depth using only the
 *     projection matrix (analytic near-plane reconstruction), rotated into camera-relative
 *     world space with the transpose of the modelview rotation.
 *  3. A short ray is marched from that position toward the sun. Each step is projected back
 *     to screen space; the occlusion test runs in LINEAR view space (blocks) — comparing in
 *     window depth would make any sane bias swallow all real occlusion. The fraction of
 *     occluded steps becomes the shadow factor (jittered per pixel = soft edges).
 *  4. A fullscreen multiply pass darkens the scene with the shadow factor. Sky pixels are
 *     excluded via the depth test, so the sun/sky itself is never darkened.
 *
 * Everything is camera-relative: no camera position or world coordinates are needed, and
 * terrain, entities, particles and water all cast/receive shadows because they all write
 * to the depth buffer.
 *
 * Cost: one reduced-res march (default 1/2 res, 20 taps) + one fullscreen multiply.
 */
public final class ShadowRenderer {
    private static final Logger LOGGER = LogManager.getLogger("Celeritas/Lux");

    private static final String VERT =
            "#version 120\n" +
            "varying vec2 vUv;\n" +
            "void main() {\n" +
            "    gl_Position = gl_Vertex;\n" +
            "    vUv = gl_Vertex.xy * 0.5 + 0.5;\n" +
            "}\n";

    private static final String MARCH_FRAG_TEMPLATE =
            "#version 120\n" +
            "#define SAMPLES %d\n" +
            "uniform sampler2D uDepth;\n" +
            "uniform mat4 uProj;\n" +   // camera projection
            "uniform mat4 uView;\n" +   // modelview (rotation; translation intentionally unused)
            "uniform vec4 uProjParams;\n" + // (P[0][0], P[1][1], P[2][2], P[3][2])
            "uniform vec3 uSunDir;\n" + // world-space direction toward the sun
            "uniform float uMaxDist;\n" + // shadow march length in blocks
            "uniform float uBias;\n" +
            "varying vec2 vUv;\n" +
            "void main() {\n" +
            "    float rawDepth = texture2D(uDepth, vUv).r;\n" +
            "    if (rawDepth >= 0.99999) { gl_FragColor = vec4(1.0); return; }\n" + // sky: lit
            // Reconstruct view-space position analytically (vz = P32 / (-ndcZ - P22)).
            "    vec2 ndcXY = vUv * 2.0 - 1.0;\n" +
            "    float ndcZ = rawDepth * 2.0 - 1.0;\n" +
            "    float vz = uProjParams.w / (-ndcZ - uProjParams.z);\n" +
            "    vec3 viewPos = vec3(ndcXY.x * (-vz) / uProjParams.x,\n" +
            "                       ndcXY.y * (-vz) / uProjParams.y, vz);\n" +
            "    float viewDist = length(viewPos);\n" +
            // Far-depth pixels (clouds, distant terrain) reconstruct unreliably: depth
            // precision near the far plane blows up the Z, so skip them entirely.
            "    if (viewDist > uMaxDist * 1.2) { gl_FragColor = vec4(1.0); return; }\n" +
            // View -> camera-relative world. The modelview rotation is orthonormal, so its
            // inverse is its transpose (built by hand: GLSL 1.20 has no transpose()).
            "    mat3 rot = mat3(uView[0].xyz, uView[1].xyz, uView[2].xyz);\n" +
            "    mat3 rotT = mat3(vec3(rot[0][0], rot[1][0], rot[2][0]),\n" +
            "                     vec3(rot[0][1], rot[1][1], rot[2][1]),\n" +
            "                     vec3(rot[0][2], rot[1][2], rot[2][2]));\n" +
            "    vec3 relPos = rotT * viewPos;\n" +
            "    float stepLen = uMaxDist / float(SAMPLES);\n" +
            "    float jitter = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);\n" +
            "    float occl = 0.0;\n" +
            "    float total = 0.0;\n" +
            "    float t = 0.5 + stepLen * jitter;\n" + // 0.5 block start offset kills acne
            "    for (int i = 0; i < SAMPLES; i++) {\n" +
            "        vec3 sampleRel = relPos + uSunDir * t;\n" +
            "        vec3 sampleView = rot * sampleRel;\n" +
            "        vec4 clip = uProj * vec4(sampleView, 1.0);\n" +
            "        if (clip.w <= 0.0) break;\n" + // sample behind the camera: marched off-screen
            "        vec3 sNdc = clip.xyz / clip.w;\n" +
            "        vec2 sUv = sNdc.xy * 0.5 + 0.5;\n" +
            "        if (sUv.x < 0.0 || sUv.x > 1.0 || sUv.y < 0.0 || sUv.y > 1.0) break;\n" +
"        float sceneDepth = texture2D(uDepth, sUv).r;\n" +
            // Compare in LINEAR view space (blocks), not window depth: window depth is
            // wildly non-linear, so a bias that looks tiny there swamps the whole depth
            // difference between surfaces beyond ~5 blocks and shadows vanish.
            "        float sceneVz = uProjParams.w / (-(sceneDepth * 2.0 - 1.0) - uProjParams.z);\n" +
            "        float lit = step(sceneVz, sampleView.z - uBias);\n" +
            "        occl += 1.0 - lit;\n" +
            "        total += 1.0;\n" +
            "        t += stepLen;\n" +
            "    }\n" +
            "    float shadow = 1.0 - clamp(occl / max(total, 1e-4) * 1.5, 0.0, 1.0);\n" +
            // Fade shadows out toward maxDist so far pixels (clouds) never go black.
            "    float fade = 1.0 - smoothstep(uMaxDist * 0.35, uMaxDist, viewDist);\n" +
            "    shadow = mix(shadow, 1.0, fade);\n" +
            "    gl_FragColor = vec4(shadow);\n" +
            "}\n";

    private static final String COMPOSITE_FRAG =
            "#version 120\n" +
            "uniform sampler2D uShadow;\n" +
            "uniform sampler2D uDepth;\n" +
            "uniform float uStrength;\n" +
            "varying vec2 vUv;\n" +
            "void main() {\n" +
            "    float s = texture2D(uShadow, vUv).r;\n" +
            "    float d = texture2D(uDepth, vUv).r;\n" +
            "    s = mix(s, 1.0, step(0.99999, d));\n" + // never darken the sky
            "    float f = 1.0 - (1.0 - s) * uStrength;\n" +
            "    gl_FragColor = vec4(f, f, f, 1.0);\n" +
            "}\n";

    private static final FloatBuffer MODELVIEW = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer PROJECTION = BufferUtils.createFloatBuffer(16);
    private static final float[] mv = new float[16];
    private static final float[] pr = new float[16];

    private static LuxProgram marchProgram;
    private static LuxProgram compositeProgram;
    private static Framebuffer shadowFbo;
    private static boolean failed;

    private ShadowRenderer() {}

    public static void render(float partialTicks) {
        if (failed || !LuxConfig.shadows) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc.world;
        if (world == null || mc.player == null || !OpenGlHelper.isFramebufferEnabled()
                || !world.provider.isSurfaceWorld()) {
            return;
        }

        // ---- cheap CPU-side early outs (mirrors the god rays) ----
        float rain = world.getRainStrength(partialTicks);
        float angle = world.getCelestialAngle(partialTicks) * (float) (Math.PI * 2.0);
        float sunElevation = MathHelper.cos(angle);
        float strength = LuxConfig.shadowStrength
                * smoothstep(-0.02f, 0.2f, sunElevation)
                * (1.0f - 0.8f * rain);
        if (strength < 0.02f) {
            return; // night / hard rain: everything is ambient-lit, no shadows to compute
        }

        // World-space direction toward the sun (same convention as SunRaysRenderer).
        float sunX = -MathHelper.sin(angle);
        float sunY = sunElevation;
        float sunZ = 0.0f;

        MODELVIEW.clear();
        PROJECTION.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODELVIEW);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, PROJECTION);
        MODELVIEW.get(mv);
        PROJECTION.get(pr);

        Framebuffer main = mc.getFramebuffer();
        int fw = main.framebufferWidth;
        int fh = main.framebufferHeight;
        int div = Math.max(1, LuxConfig.shadowResolutionDivisor);
        int sw = Math.max(1, fw / div);
        int sh = Math.max(1, fh / div);

        try {
            ensureResources(main, sw, sh);
        } catch (Throwable t) {
            disable("initialization", t);
            return;
        }

        int prevActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        // We touch the bindings of units 0 and 1 (depth + shadow textures); remember both,
        // and park the active unit on 0 (the sampler always reads unit 0 here).
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        int prevTexUnit1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevTex2D = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        // NOTE: GL_TEXTURE_BIT deliberately excluded (slow to save/restore on old drivers);
        // the texture bindings we touch are restored manually.
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_VIEWPORT_BIT);
        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_FOG);

            if (!SceneDepth.prepare(main)) {
                return;
            }
            int depthTex = SceneDepth.texture();

            // 1) march shadows toward the sun at reduced resolution
            shadowFbo.bindFramebuffer(true);
            marchProgram.bind();
            marchProgram.set1i("uDepth", 0);
            marchProgram.setMatrix4("uProj", pr);
            marchProgram.setMatrix4("uView", mv);
            marchProgram.set4f("uProjParams", pr[0], pr[5], pr[10], pr[14]);
            marchProgram.set3f("uSunDir", sunX, sunY, sunZ);
            marchProgram.set1f("uMaxDist", LuxConfig.shadowDistance);
            marchProgram.set1f("uBias", LuxConfig.shadowBias);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
            drawFullscreenQuad();

            // 2) fullscreen multiply onto the main framebuffer
            main.bindFramebuffer(true);
            compositeProgram.bind();
            compositeProgram.set1i("uShadow", 0);
            compositeProgram.set1i("uDepth", 1);
            compositeProgram.set1f("uStrength", strength);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, shadowFbo.framebufferTexture);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_DST_COLOR, GL11.GL_ZERO); // multiply
            drawFullscreenQuad();
        } catch (Throwable t) {
            disable("rendering", t);
        } finally {
            LuxProgram.unbind();
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTexUnit1);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex2D);
            GL13.glActiveTexture(prevActiveTexture);
            main.bindFramebuffer(true);
            GL11.glPopAttrib();
        }
    }

    private static void ensureResources(Framebuffer main, int sw, int sh) {
        if (marchProgram == null) {
            marchProgram = new LuxProgram("lux_shadow_march", VERT,
                    String.format(MARCH_FRAG_TEMPLATE, LuxConfig.shadowSamples));
            compositeProgram = new LuxProgram("lux_shadow_composite", VERT, COMPOSITE_FRAG);
            LOGGER.info("Shadow shaders compiled ({} samples, 1/{} resolution)",
                    LuxConfig.shadowSamples, LuxConfig.shadowResolutionDivisor);
        }
        if (shadowFbo == null) {
            shadowFbo = new Framebuffer(sw, sh, false);
            shadowFbo.setFramebufferFilter(GL11.GL_LINEAR); // linear upscaling = soft edges
            clampShadowFboTexture();
            main.bindFramebuffer(true);
        } else if (shadowFbo.framebufferWidth != sw || shadowFbo.framebufferHeight != sh) {
            shadowFbo.createBindFramebuffer(sw, sh);
            shadowFbo.setFramebufferFilter(GL11.GL_LINEAR);
            clampShadowFboTexture();
            main.bindFramebuffer(true);
        }
    }

    private static void clampShadowFboTexture() {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, shadowFbo.framebufferTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
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
        LOGGER.error("Sun shadows failed during " + stage + "; disabling the effect for this session.", t);
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = MathHelper.clamp((x - e0) / (e1 - e0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }
}
