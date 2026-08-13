package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.visuals.NoRenderModule;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ParticleEngine.class)
public class ParticleManagerMixin {
    // Only ever catches the single "boom" particle from ClientPacketListener.handleExplosion's
    // own explicit addParticle(packet.explosionParticle(), ...) call -- the actual debris cloud
    // (the huge grey burst screenshotted, still showing with this on) is a SEPARATE mechanism
    // entirely: ClientExplosionTracker queues up to 512 particles/tick from a server-controlled
    // WeightedList<ExplosionParticleInfo>, whose particle TYPE is whatever the server configured
    // for that specific explosion (poof/cloud/smoke/...), never guaranteed to be
    // ParticleTypes.EXPLOSION -- filtering by type here can never reliably catch it. See
    // ClientExplosionTrackerMixin, which cancels the debris at its actual source instead.
    @Inject(method = "createParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)Lnet/minecraft/client/particle/Particle;", at = @At("HEAD"), cancellable = true)
    private void addParticle(ParticleOptions parameters, double x, double y, double z, double velocityX, double velocityY, double velocityZ, CallbackInfoReturnable<Particle> info) {
        NoRenderModule noRender = EUClient.MODULE_MANAGER.getModule(NoRenderModule.class);
        if (!noRender.isToggled()) return;

        if (noRender.explosions.getValue() && parameters.getType() == ParticleTypes.EXPLOSION) {
            info.cancel();
        } else if (noRender.totemPop.getValue() && parameters.getType() == ParticleTypes.TOTEM_OF_UNDYING) {
            info.cancel();
        }
    }
}
