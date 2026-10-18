package org.embeddedt.embeddium.impl.render.chunk.shader;

/**
 * Global switch for the Lux shadow-mapping terrain shader variant. When enabled, the terrain shaders are compiled with
 * the LUX_SHADOWS define and sample a shadow map. Must be set before any terrain shader is compiled.
 */
public final class LuxShadowFlags {
    public static volatile boolean ENABLED = false;

    private LuxShadowFlags() {}
}
