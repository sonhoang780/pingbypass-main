package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.visuals.AtmosphereModule;
import eu.client.modules.impl.visuals.NoRenderModule;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import net.minecraft.util.ARGB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.awt.*;

// ponytail: fog rendering was rearchitected from a single FogRenderer.applyFog(...) args-modify
// point into a FogEnvironment strategy system. This mixin covers regular sky/weather/render-distance
// fog (AtmosphericFogEnvironment); see BlindnessFogEnvironmentMixin/DarknessFogEnvironmentMixin for
// the potion-effect vignette bypass.
@Mixin(AtmosphericFogEnvironment.class)
public class BackgroundRendererMixin {
    @Inject(method = "setupFog", at = @At("TAIL"))
    private void euclient$setupFog(FogData fog, Camera camera, ClientLevel level, float renderDistance, DeltaTracker deltaTracker, CallbackInfo info) {
        NoRenderModule noRender = EUClient.MODULE_MANAGER.getModule(NoRenderModule.class);
        AtmosphereModule atmosphere = EUClient.MODULE_MANAGER.getModule(AtmosphereModule.class);

        if (noRender.isToggled() && noRender.fog.getValue()) {
            fog.environmentalStart = renderDistance * 4;
            fog.environmentalEnd = renderDistance * 4.25f;
        } else if (atmosphere.isToggled() && atmosphere.modifyFog.getValue()) {
            fog.environmentalStart = atmosphere.fogStart.getValue().floatValue();
            fog.environmentalEnd = atmosphere.fogEnd.getValue().floatValue();
        }
    }

    @Inject(method = "getBaseColor", at = @At("TAIL"), cancellable = true)
    private void euclient$getBaseColor(ClientLevel level, Camera camera, int renderDistance, float partialTicks, CallbackInfoReturnable<Integer> info) {
        AtmosphereModule atmosphere = EUClient.MODULE_MANAGER.getModule(AtmosphereModule.class);
        if (atmosphere.isToggled() && atmosphere.modifyFog.getValue()) {
            Color color = atmosphere.fogColor.getColor();
            info.setReturnValue(ARGB.color(255, color.getRed(), color.getGreen(), color.getBlue()));
        }
    }
}
