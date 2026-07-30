package eu.client.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import eu.client.EUClient;
import eu.client.modules.impl.movement.VelocityModule;
import eu.client.utils.IMinecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FishingHook.class)
public class FishingBobberEntityMixin implements IMinecraft {
    @WrapOperation(method = "handleStatus", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/projectile/FishingHook;pullHookedEntity(Lnet/minecraft/entity/Entity;)V"))
    private void pushOutOfBlocks(FishingHook instance, Entity entity, Operation<Void> original) {
        if (entity == mc.player && EUClient.MODULE_MANAGER.getModule(VelocityModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(VelocityModule.class).antiFishingRod.getValue())
            return;

        original.call(instance, entity);
    }
}
