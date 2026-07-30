package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.visuals.AtmosphereModule;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public class PropertiesMixin {
    @Inject(method = "getOverworldClockTime", at = @At("HEAD"), cancellable = true)
    private void getTimeOfDay(CallbackInfoReturnable<Long> info) {
        if (EUClient.MODULE_MANAGER.getModule(AtmosphereModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(AtmosphereModule.class).modifyTime.getValue()) {
            info.setReturnValue(EUClient.MODULE_MANAGER.getModule(AtmosphereModule.class).time.getValue().longValue() * 100L);
        }
    }
}
