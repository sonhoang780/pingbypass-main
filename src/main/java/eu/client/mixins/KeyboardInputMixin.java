package eu.client.mixins;

import eu.client.EUClient;
import eu.client.events.impl.KeyboardTickEvent;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public class KeyboardInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At(value = "TAIL"))
    private void tick$TAIL(CallbackInfo info) {
        KeyboardTickEvent event = new KeyboardTickEvent(moveVector.y, moveVector.x);
        EUClient.EVENT_HANDLER.post(event);
        if (event.isCancelled()) {
            this.moveVector = new Vec2(event.getMovementSideways(), event.getMovementForward());
        }
    }
}
