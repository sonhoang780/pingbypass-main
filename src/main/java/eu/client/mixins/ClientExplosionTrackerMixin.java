package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.visuals.NoRenderModule;
import net.minecraft.client.multiplayer.ClientExplosionTracker;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// NoRenderModule "Explosions" -- the debris cloud (huge burst of grey/smoke particles on crystal/
// TNT explosions) never went through ParticleManagerMixin's ParticleTypes.EXPLOSION check at all:
// it's queued here, from ClientLevel.trackExplosionEffects -> ClientExplosionTracker.track(...),
// then spawned up to 512/tick in ClientExplosionTracker.tick() using whatever particle type the
// SERVER configured for that explosion (packet.blockParticles(), a WeightedList -- poof/cloud/
// smoke/anything, never guaranteed to be ParticleTypes.EXPLOSION). Cancelling the queue-up here
// stops the whole debris burst regardless of which particle type the server picked, instead of
// trying to enumerate/guess every possible type downstream.
@Mixin(ClientExplosionTracker.class)
public class ClientExplosionTrackerMixin {
    @Inject(method = "track", at = @At("HEAD"), cancellable = true)
    private void euclient$cancelDebris(Vec3 center, float radius, int blockCount, WeightedList<ExplosionParticleInfo> blockParticles, CallbackInfo ci) {
        NoRenderModule noRender = EUClient.MODULE_MANAGER.getModule(NoRenderModule.class);
        if (noRender.isToggled() && noRender.explosions.getValue()) {
            ci.cancel();
        }
    }
}
