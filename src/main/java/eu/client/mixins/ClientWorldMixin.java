package eu.client.mixins;

import eu.client.EUClient;
import eu.client.events.impl.EntitySpawnEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// PORT: getSkyColor(Vec3, float) no longer exists -- sky color is now driven by the
// EnvironmentAttributes.SKY_COLOR attribute, consumed inside AtmosphericFogEnvironment.getBaseColor,
// which BackgroundRendererMixin already overrides for AtmosphereModule. Nothing left to hook here.
@Mixin(ClientLevel.class)
public class ClientWorldMixin {
    @Inject(method = "addEntity", at = @At(value = "HEAD"))
    private void addEntity(Entity entity, CallbackInfo info) {
        EUClient.EVENT_HANDLER.post(new EntitySpawnEvent(entity));
    }
}
