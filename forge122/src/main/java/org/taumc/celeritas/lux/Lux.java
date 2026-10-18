package org.taumc.celeritas.lux;

import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Entry point for the Lux rendering effects (sun rays, and later shadows + colored lighting). */
public final class Lux {
    private Lux() {}

    public static void init() {
        LuxConfig.load();
        // Must happen before any terrain shader is compiled (i.e. before the first world is loaded).
        org.embeddedt.embeddium.impl.render.chunk.shader.LuxShadowFlags.ENABLED = LuxConfig.shadows;
        MinecraftForge.EVENT_BUS.register(new Lux());
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        SunRaysRenderer.render(event.getPartialTicks());
    }
}
