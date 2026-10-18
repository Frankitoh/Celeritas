package org.taumc.celeritas.lux;

import net.minecraft.client.shader.Framebuffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;

import java.nio.FloatBuffer;

/**
 * Per-frame copy of the main framebuffer's depth buffer into a sampleable texture.
 *
 * Shared between the shadow march and the sun rays so the expensive full-resolution
 * depth copy only happens once per frame, no matter how many effects consume it.
 */
final class SceneDepth {
    private static final Logger LOGGER = LogManager.getLogger("Celeritas/Lux");

    private static int texture = -1;
    private static int width = -1, height = -1;
    private static boolean copiedThisFrame;
    private static boolean failed;

    private SceneDepth() {}

    /** Called once per frame from {@code Lux} before any effect renders. */
    static void beginFrame() {
        copiedThisFrame = false;
    }

    /**
     * Makes sure the depth texture exists, matches the main framebuffer's size and holds
     * this frame's depth. Leaves the main framebuffer bound. Returns false on failure.
     */
    static boolean prepare(Framebuffer main) {
        if (failed) {
            return false;
        }
        // The copy target is the binding on the ACTIVE unit; do it on unit 0 so consumers
        // (which sample sampler2D on unit 0) always see it, whatever vanilla left active.
        int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        try {
            int fw = main.framebufferWidth;
            int fh = main.framebufferHeight;
            if (fw <= 0 || fh <= 0) {
                return false;
            }
            if (texture == -1) {
                texture = GL11.glGenTextures();
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            if (width != fw || height != fh) {
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, fw, fh, 0,
                        GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (FloatBuffer) null);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                width = fw;
                height = fh;
                copiedThisFrame = false; // size changed, old contents are stale
            }
            if (!copiedThisFrame) {
                main.bindFramebuffer(false);
                GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, fw, fh);
                copiedThisFrame = true;
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("Scene depth copy failed; depth-based Lux effects are disabled for this session.", t);
            return false;
        } finally {
            GL13.glActiveTexture(prevActive);
        }
    }

    static int texture() {
        return texture;
    }
}
