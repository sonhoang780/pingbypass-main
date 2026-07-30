package eu.client.mixins;

import eu.client.EUClient;
import eu.client.events.impl.RemoveFireworkEvent;
import eu.client.utils.IMinecraft;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireworkRocketEntity.class)
public class FireworkRocketEntityMixin implements IMinecraft {
    @Shadow private int life;

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/projectile/FireworkRocketEntity;updateRotation()V", shift = At.Shift.AFTER), cancellable = true)
    private void tick(CallbackInfo info) {
        FireworkRocketEntity entity = ((FireworkRocketEntity) (Object) this);

        RemoveFireworkEvent event = new RemoveFireworkEvent(entity);
        EUClient.EVENT_HANDLER.post(event);

        if (event.isCancelled()) {
            info.cancel();

            if (life == 0 && !entity.isSilent()) {
                mc.level.playSound(null, entity.getX(), entity.getY(), entity.getZ(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.AMBIENT, 3.0f, 1.0f);
            }

            ++life;
            if (life % 2 < 2) {
                mc.level.addParticle(ParticleTypes.FIREWORK, entity.getX(), entity.getY(), entity.getZ(), mc.level.getRandom().nextGaussian() * 0.05, -entity.getDeltaMovement().y * 0.5, mc.level.getRandom().nextGaussian() * 0.05);
            }
        }
    }
}
