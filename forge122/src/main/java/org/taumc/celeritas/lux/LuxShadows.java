package org.taumc.celeritas.lux;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.MathHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.embeddedt.embeddium.impl.render.chunk.ChunkRenderMatrices;
import org.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderInterface;
import org.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderTextureSlot;
import org.embeddedt.embeddium.impl.render.chunk.shader.LuxShadowFlags;
import org.embeddedt.embeddium.impl.render.terrain.SimpleWorldRenderer;
import org.embeddedt.embeddium.impl.render.viewport.Viewport;
import org.embeddedt.embeddium.impl.render.viewport.frustum.SimpleFrustum;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.taumc.celeritas.impl.render.terrain.CameraHelper;
import org.taumc.celeritas.impl.render.terrain.CeleritasWorldRenderer;

import java.nio.FloatBuffer;

/**
 * Sun shadow mapping for terrain.
 *
 * How it works:
 *  1. Once per frame (before the normal terrain setup) a single orthographic "sun camera" is fitted around the player
 *     and snapped to the shadow-map texel grid so shadows do not shimmer while walking.
 *  2. Celeritas' own shadow-pass machinery (shadow render lists + the already-uploaded region buffers) draws the
 *     opaque/cutout terrain from the sun's point of view into a depth-only framebuffer. No geometry is re-uploaded.
 *  3. The regular terrain shader (compiled with LUX_SHADOWS) projects each fragment into that map, does a 4-tap PCF
 *     comparison and darkens the fragment where sky light reaches.
 *
 * Cost control: nothing runs at night / in heavy rain / outside the overworld, the map can be refreshed every N frames
 * (the stale map is re-based onto the moving camera), and the depth-only pass skips the shadow lookup entirely.
 */
public final class LuxShadows {
    private static final Logger LOGGER = LogManager.getLogger("Celeritas/Lux");

    /** Texture unit (GL_TEXTURE0 + N) the terrain shader reads the shadow map from. */
    private static final int SHADOW_UNIT = 5;

    private static boolean failed;
    private static boolean warnedNoShadowPass;
    private static boolean loggedActive;
    private static boolean rendering;
    private static boolean haveMap;

    private static int fbo = -1;
    private static int depthTex = -1;
    private static int mapSize;

    /** Camera-relative (to the CURRENT frame) world -> shadow clip matrix used by the terrain shader. */
    private static final Matrix4f currentMatrix = new Matrix4f();
    /** Matrix the map was actually rendered with, relative to lastCam. */
    private static final Matrix4f lastMatrix = new Matrix4f();
    private static double lastCamX, lastCamY, lastCamZ;

    private static float strength;
    private static float bias;
    private static float texel;
    private static int frameCounter;
    // The light direction the current shadow map was built with. It is held fixed between small sun steps so the texel
    // lattice does not rotate every frame (which makes alpha-tested leaf holes shimmer).
    private static float heldSunX, heldSunY, heldSunZ;
    private static boolean haveHeldSun;
    private static int framesSinceRender;
    /** cos of the sun movement (about 0.43 degrees) that triggers a new light direction. */
    private static final float SUN_STEP_COS = 0.99997f;

    private LuxShadows() {}

    public static boolean isRenderingShadowMap() {
        return rendering;
    }

    /** Called by the terrain renderer for every terrain draw so the shadow uniforms/texture are always current. */
    public static void configureShader(ChunkShaderInterface shader) {
        if (!LuxShadowFlags.ENABLED) {
            return;
        }
        boolean sample = !rendering && haveMap && strength > 0.0f && depthTex != -1;
        shader.setTextureSlot(ChunkShaderTextureSlot.SHADOW, SHADOW_UNIT);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit + SHADOW_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sample ? depthTex : 0);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        shader.setShadowMatrix(currentMatrix);
        // Negative strength tells the shader it is building the shadow map (leaves are drawn solid there).
        shader.setShadowParams(sample ? strength : (rendering ? -1.0f : 0.0f), bias, texel);
    }

    /**
     * Per-frame entry point, called right before the regular terrain setup. Renders the shadow map when needed and
     * updates the shadow uniforms. Never throws; on failure the feature disables itself for the session.
     */
    public static void update(CeleritasWorldRenderer renderer, Entity entity, double partialTicks, Viewport playerViewport,
                              SimpleWorldRenderer.CameraState cameraState, int frame, boolean spectator) {
        strength = 0.0f;
        if (failed || !LuxShadowFlags.ENABLED || !LuxConfig.shadows) {
            return;
        }
        if (!renderer.luxSupportsShadows()) {
            if (!warnedNoShadowPass) {
                warnedNoShadowPass = true;
                LOGGER.warn("Shadows are enabled but the render section manager has no shadow pass; shadows stay off.");
            }
            return;
        }
        if (!loggedActive) {
            loggedActive = true;
            LOGGER.info("Shadows active: map {}x{}, radius {} blocks, update every {} frame(s)",
                    LuxConfig.shadowResolution, LuxConfig.shadowResolution, LuxConfig.shadowDistance,
                    LuxConfig.shadowUpdateInterval);
        }
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc.world;
        if (world == null || !world.provider.isSurfaceWorld() || !OpenGlHelper.isFramebufferEnabled()) {
            return;
        }

        float pt = (float) partialTicks;
        float rain = world.getRainStrength(pt);
        float angle = world.getCelestialAngle(pt) * (float) (Math.PI * 2.0);
        float elevation = MathHelper.cos(angle);
        float s = LuxConfig.shadowStrength * smoothstep(0.08f, 0.30f, elevation) * (1.0f - 0.8f * rain);
        if (s < 0.02f) {
            return;
        }

        // Same camera position the terrain pass uses (entity feet, interpolated).
        double cx = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks;
        double cy = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks;
        double cz = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks;

        double moved = Math.sqrt(sq(cx - lastCamX) + sq(cy - lastCamY) + sq(cz - lastCamZ));
        int interval = Math.max(1, LuxConfig.shadowUpdateInterval);

        // Direction TOWARD the sun (matches vanilla's sky rotations).
        float dx = -MathHelper.sin(angle);
        float dy = elevation;
        float dz = 0.0f;

        // Only adopt a new light direction after the sun moved a little; in between, rebuild with the held one.
        boolean sunStep = !haveHeldSun || (dx * heldSunX + dy * heldSunY + dz * heldSunZ) < SUN_STEP_COS;
        boolean aged = ++framesSinceRender >= interval;
        boolean redraw = !haveMap || sunStep || moved > LuxConfig.shadowDistance * 0.35 || aged;

        if (redraw) {
            if (sunStep) {
                heldSunX = dx;
                heldSunY = dy;
                heldSunZ = dz;
                haveHeldSun = true;
            }
            try {
                renderShadowMap(renderer, mc, cx, cy, cz, heldSunX, heldSunY, heldSunZ, playerViewport, cameraState, frame, spectator);
            } catch (Throwable t) {
                failed = true;
                haveMap = false;
                LOGGER.error("Shadow mapping failed; disabling shadows for this session.", t);
                return;
            }
            framesSinceRender = 0;
            currentMatrix.set(lastMatrix);
        } else {
            // Re-base the stale map onto the moving camera: world - camNow = (world - camOld) - (camNow - camOld)
            currentMatrix.set(lastMatrix).translate((float) (cx - lastCamX), (float) (cy - lastCamY), (float) (cz - lastCamZ));
        }
        strength = s;
    }

    private static void renderShadowMap(CeleritasWorldRenderer renderer, Minecraft mc, double cx, double cy, double cz,
                                        float sunX, float sunY, float sunZ, Viewport playerViewport,
                                        SimpleWorldRenderer.CameraState cameraState, int frame, boolean spectator) {
        final int size = LuxConfig.shadowResolution;
        final float radius = LuxConfig.shadowDistance;
        final float casterReach = Math.max(96.0f, radius * 2.0f);   // how far toward the sun we still catch casters
        final float far = 2.0f * radius + casterReach;

        // ---- light basis: f = direction the light travels (from the sun toward the world) ----
        Vector3f f = new Vector3f(-sunX, -sunY, -sunZ).normalize();
        Vector3f upHint = Math.abs(f.y) > 0.95f ? new Vector3f(0, 0, 1) : new Vector3f(0, 1, 0);
        Vector3f right = f.cross(upHint, new Vector3f()).normalize();
        Vector3f lup = right.cross(f, new Vector3f()).normalize();

        // ---- snap the map center to the texel grid (in absolute light space) so it doesn't shimmer ----
        double texelWorld = (2.0 * radius) / size;
        // The lattice rotates with the sun, so measure it from an anchor close to the player instead of the world
        // origin: far from the origin the lattice would sweep across the terrain at several texels per second.
        double ax = Math.floor(cx / 64.0) * 64.0;
        double ay = Math.floor(cy / 64.0) * 64.0;
        double az = Math.floor(cz / 64.0) * 64.0;
        double lx = (cx - ax) * right.x + (cy - ay) * right.y + (cz - az) * right.z;
        double ly = (cx - ax) * lup.x + (cy - ay) * lup.y + (cz - az) * lup.z;
        float snapX = (float) (Math.floor(lx / texelWorld) * texelWorld - lx);
        float snapY = (float) (Math.floor(ly / texelWorld) * texelWorld - ly);

        // camera-relative center of the box, then the eye backs off toward the sun
        Vector3f center = new Vector3f(right).mul(snapX).add(new Vector3f(lup).mul(snapY));
        Vector3f eye = new Vector3f(f).mul(-(radius + casterReach)).add(center);
        Vector3f target = new Vector3f(eye).add(f);

        Matrix4f view = new Matrix4f().lookAt(eye, target, lup);
        Matrix4f proj = new Matrix4f().setOrtho(-radius, radius, -radius, radius, 0.0f, far);
        Matrix4f clip = new Matrix4f(proj).mul(view);

        lastMatrix.set(clip);
        lastCamX = cx;
        lastCamY = cy;
        lastCamZ = cz;
        mapSize = size;
        texel = 1.0f / size;
        bias = (2.0f * (float) texelWorld) / far;

        // ---- shadow viewport: same origin convention as the player viewport (eye / third-person offset) ----
        Vector3f off = CameraHelper.getThirdPersonOffset();
        Matrix4f clipForViewport = new Matrix4f(clip).translate(off.x, off.y, off.z);
        Viewport shadowViewport = new Viewport(new SimpleFrustum(new FrustumIntersection(clipForViewport)),
                new Vector3d(cx + off.x, cy + off.y, cz + off.z));

        // Joins the previous frame's searches, applies chunk events, uploads, and runs the shadow search.
        renderer.setupShadowTerrain(playerViewport, shadowViewport, cameraState, frame, spectator);

        Framebuffer main = mc.getFramebuffer();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_VIEWPORT_BIT | GL11.GL_POLYGON_BIT | GL11.GL_TEXTURE_BIT);
        try {
            ensureFramebuffer(size);
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, fbo);
            GL11.glViewport(0, 0, size, size);
            GL11.glColorMask(false, false, false, false);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glPolygonOffset(2.0f, 4.0f);

            // The block atlas is needed for alpha-tested cutout blocks (leaves, grass, ...).
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, mc.getTextureMapBlocks().getGlTextureId());

            ChunkRenderMatrices matrices = new ChunkRenderMatrices(proj, view);
            renderer.luxSetShadowPass(true);
            rendering = true;
            try {
                renderer.luxDrawShadowLayer(BlockRenderLayer.SOLID, matrices, cx, cy, cz);
                renderer.luxDrawShadowLayer(BlockRenderLayer.CUTOUT_MIPPED, matrices, cx, cy, cz);
                renderer.luxDrawShadowLayer(BlockRenderLayer.CUTOUT, matrices, cx, cy, cz);
            } finally {
                rendering = false;
                renderer.luxSetShadowPass(false);
            }
            haveMap = true;
        } finally {
            main.bindFramebuffer(true);
            GL11.glPopAttrib();
        }
    }

    private static void ensureFramebuffer(int size) {
        if (fbo != -1 && depthTex != -1 && mapSize == size && sizeOfAllocated == size) {
            return;
        }
        destroyFramebuffer();

        depthTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, size, size, 0,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (FloatBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        fbo = OpenGlHelper.glGenFramebuffers();
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, fbo);
        OpenGlHelper.glFramebufferTexture2D(OpenGlHelper.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, depthTex, 0);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);

        int status = OpenGlHelper.glCheckFramebufferStatus(OpenGlHelper.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            destroyFramebuffer();
            throw new IllegalStateException("Shadow framebuffer incomplete (status 0x" + Integer.toHexString(status) + ")");
        }
        sizeOfAllocated = size;
        LOGGER.info("Shadow map created: {}x{}, radius {} blocks", size, size, LuxConfig.shadowDistance);
    }

    private static int sizeOfAllocated = -1;

    private static void destroyFramebuffer() {
        if (fbo != -1) {
            OpenGlHelper.glDeleteFramebuffers(fbo);
            fbo = -1;
        }
        if (depthTex != -1) {
            GL11.glDeleteTextures(depthTex);
            depthTex = -1;
        }
        sizeOfAllocated = -1;
        haveMap = false;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = MathHelper.clamp((x - e0) / (e1 - e0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }
}
