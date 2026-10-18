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

    // --- Sun shadows ---
    public static boolean shadows = true;
    /** Depth-march taps per pixel. 8 = very cheap, 20 = default, 40 = pretty. */
    public static int shadowSamples = 20;
    /** Internal resolution divisor (2 = half res, 4 = quarter res). */
    public static int shadowResolutionDivisor = 2;
    /** 0 = off, 1 = pitch black in shadow. */
    public static float shadowStrength = 0.6f;
    /** How far shadows are marched, in blocks. */
    public static float shadowDistance = 48.0f;
    /** Depth comparison bias; raise it if surfaces shadow themselves (acne). */
    public static float shadowBias = 0.002f;

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

            c = "shadows";
            shadows = cfg.getBoolean("enabled", c, shadows, "Enable screen-space dynamic sun shadows.");
            shadowSamples = cfg.getInt("samples", c, shadowSamples, 4, 64, "Depth-march taps per pixel. Lower = faster.");
            shadowResolutionDivisor = cfg.getInt("resolutionDivisor", c, shadowResolutionDivisor, 1, 8,
                    "March shadows at 1/N resolution. 2 = half, 4 = quarter (fastest).");
            shadowStrength = cfg.getFloat("strength", c, shadowStrength, 0.0f, 1.0f, "Darkness of shadows (0 = none, 1 = black).");
            shadowDistance = cfg.getFloat("distance", c, shadowDistance, 8.0f, 128.0f, "How far shadow rays march, in blocks.");
            shadowBias = cfg.getFloat("bias", c, shadowBias, 0.0f, 0.02f,
                    "Depth comparison bias. Increase if surfaces shadow themselves (acne), decrease if shadows detach.");
        } finally {
            if (cfg.hasChanged()) {
                cfg.save();
            }
        }
    }
}
