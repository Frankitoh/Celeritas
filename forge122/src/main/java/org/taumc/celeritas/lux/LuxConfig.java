package org.taumc.celeritas.lux;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.common.Loader;

import java.io.File;

/**
 * Simple Forge-config backed settings for the Lux effects (config/celeritas_lux.cfg).
 * Values are read once at startup; restart the game after editing the file.
 */
public final class LuxConfig {
    // --- Sun rays ---
    public static boolean sunRays = true;
    /** Radial blur taps per pixel. 8 = very cheap, 12 = default, 24 = pretty. */
    public static int sunRaySamples = 12;
    /** Internal resolution divisor (2 = half res, 4 = quarter res). */
    public static int sunRayResolutionDivisor = 2;
    public static float sunRayIntensity = 0.5f;
    /** How much of the way to the sun each ray marches (0..1). */
    public static float sunRayLength = 0.85f;
    public static float sunRayDecay = 0.95f;
    /** Size of the glow area around the sun, in screen heights. */
    public static float sunRayRadius = 1.4f;

    // --- Shadows ---
    public static boolean shadows = true;
    /** Shadow map size in texels (square). 1024 is a good low-end default. */
    public static int shadowResolution = 1024;
    /** Radius around the player covered by the shadow map, in blocks. */
    public static int shadowDistance = 64;
    /** Maximum darkness of a shadow (0..1). */
    public static float shadowStrength = 0.5f;
    /** Re-render the shadow map every N frames (2 = half the shadow cost). */
    public static int shadowUpdateInterval = 1;

    private LuxConfig() {}

    public static void load() {
        Configuration cfg = new Configuration(new File(Loader.instance().getConfigDir(), "celeritas_lux.cfg"));
        try {
            cfg.load();
            String c = "sunrays";
            sunRays = cfg.getBoolean("enabled", c, sunRays, "Enable screen-space sun rays (god rays).");
            sunRaySamples = cfg.getInt("samples", c, sunRaySamples, 4, 48, "Blur taps per pixel. Lower = faster.");
            sunRayResolutionDivisor = cfg.getInt("resolutionDivisor", c, sunRayResolutionDivisor, 1, 8,
                    "Render rays at 1/N resolution. 2 = half, 4 = quarter (fastest).");
            sunRayIntensity = cfg.getFloat("intensity", c, sunRayIntensity, 0.0f, 4.0f, "Brightness of the rays.");
            sunRayLength = cfg.getFloat("length", c, sunRayLength, 0.1f, 1.0f, "How far each ray marches toward the sun.");
            sunRayDecay = cfg.getFloat("decay", c, sunRayDecay, 0.5f, 1.0f, "Per-tap falloff along the ray.");
            sunRayRadius = cfg.getFloat("radius", c, sunRayRadius, 0.2f, 3.0f, "Size of the glow around the sun.");

            String sh = "shadows";
            shadows = cfg.getBoolean("enabled", sh, shadows, "Enable sun shadow mapping on terrain.");
            shadowResolution = cfg.getInt("resolution", sh, shadowResolution, 256, 4096,
                    "Shadow map size in pixels. 512 = fastest, 1024 = default, 2048 = sharper.");
            shadowDistance = cfg.getInt("distance", sh, shadowDistance, 16, 192,
                    "Radius around the player (in blocks) that receives shadows.");
            shadowStrength = cfg.getFloat("strength", sh, shadowStrength, 0.0f, 1.0f, "How dark shadows are.");
            shadowUpdateInterval = cfg.getInt("updateInterval", sh, shadowUpdateInterval, 1, 8,
                    "Re-render the shadow map every N frames. Higher = faster, but shadows lag behind changes.");
        } finally {
            if (cfg.hasChanged()) {
                cfg.save();
            }
        }
    }
}
