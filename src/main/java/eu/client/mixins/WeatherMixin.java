package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.visuals.AtmosphereModule;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Client-only fake weather (AtmosphereModule.weather) -- same "override the read path, never touch
// the actual data" approach as PropertiesMixin does for Time. isRaining/isThundering/getRainLevel/
// getThunderLevel are the paths particle spawning (rain/snow), sky darkening, and ambient rain
// sound all read from -- overriding them here fakes the visual/audio without sending anything to
// the server, so a real weather change from the server still lands underneath and reasserts itself
// the moment this module (or its Weather mode) is turned off.
@Mixin(Level.class)
public abstract class WeatherMixin {
    @Inject(method = "isRaining", at = @At("HEAD"), cancellable = true)
    private void euclient$isRaining(CallbackInfoReturnable<Boolean> info) {
        AtmosphereModule module = EUClient.MODULE_MANAGER.getModule(AtmosphereModule.class);
        if (!module.isToggled() || module.weather.getValue().equalsIgnoreCase("Unchanged")) return;
        // Was "anything but Clear" -- Snow/Dust are the module's OWN decorative particles (see
        // AtmosphereModule.onTick), not real vanilla rain, so they were wrongly forcing real rain
        // ALSO true underneath, stacking real rain with the fake snow.
        String weather = module.weather.getValue();
        info.setReturnValue(weather.equalsIgnoreCase("Rain") || weather.equalsIgnoreCase("Thunder"));
    }

    @Inject(method = "isThundering", at = @At("HEAD"), cancellable = true)
    private void euclient$isThundering(CallbackInfoReturnable<Boolean> info) {
        AtmosphereModule module = EUClient.MODULE_MANAGER.getModule(AtmosphereModule.class);
        if (!module.isToggled() || module.weather.getValue().equalsIgnoreCase("Unchanged")) return;
        info.setReturnValue(module.weather.getValue().equalsIgnoreCase("Thunder"));
    }

    @Inject(method = "getRainLevel", at = @At("HEAD"), cancellable = true)
    private void euclient$getRainLevel(float delta, CallbackInfoReturnable<Float> info) {
        AtmosphereModule module = EUClient.MODULE_MANAGER.getModule(AtmosphereModule.class);
        if (!module.isToggled() || module.weather.getValue().equalsIgnoreCase("Unchanged")) return;
        String weather = module.weather.getValue();
        info.setReturnValue(weather.equalsIgnoreCase("Rain") || weather.equalsIgnoreCase("Thunder") ? 1.0f : 0.0f);
    }

    @Inject(method = "getThunderLevel", at = @At("HEAD"), cancellable = true)
    private void euclient$getThunderLevel(float delta, CallbackInfoReturnable<Float> info) {
        AtmosphereModule module = EUClient.MODULE_MANAGER.getModule(AtmosphereModule.class);
        if (!module.isToggled() || module.weather.getValue().equalsIgnoreCase("Unchanged")) return;
        info.setReturnValue(module.weather.getValue().equalsIgnoreCase("Thunder") ? 1.0f : 0.0f);
    }
}
