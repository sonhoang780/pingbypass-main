package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.visuals.AtmosphereModule;
import net.minecraft.client.ClientClockManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// PORT: 26.1.2 replaced the old single dayTime field with a data-driven Timeline/EnvironmentAttribute
// system -- sky color, sky light level, celestial angle etc. are now sampled per-frame from
// ClientClockManager.getTotalTicks(Holder<WorldClock>) (see EnvironmentAttributeSystem.addDefaultLayers),
// NOT from Level.getOverworldClockTime()/getTimeOfDay() (that pair only feeds the compass/clock item
// display). Overriding those, as this mixin originally did (matching the 1.21.4 target), never touched
// actual rendering. Hook the real source instead.
@Mixin(ClientClockManager.class)
public class PropertiesMixin {
    @Inject(method = "getTotalTicks", at = @At("HEAD"), cancellable = true)
    private void getTotalTicks(CallbackInfoReturnable<Long> info) {
        if (EUClient.MODULE_MANAGER.getModule(AtmosphereModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(AtmosphereModule.class).modifyTime.getValue()) {
            info.setReturnValue(EUClient.MODULE_MANAGER.getModule(AtmosphereModule.class).time.getValue().longValue() * 100L);
        }
    }
}
