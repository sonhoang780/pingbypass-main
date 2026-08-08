package eu.client.mixins;

import eu.client.EUClient;
import eu.client.events.impl.KeyboardTickEvent;
import eu.client.modules.impl.movement.SprintModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
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

        // Sprint "Grim" (omni-sprint vs GrimAC) -- the ONE and only place the faked input exists.
        // Equivalent of homovore's MixinClientPlayerEntity.homovore$moveFixAfterInputTick, which
        // injects into LocalPlayer.aiStep right after `this.input.tick()`; the tail of
        // KeyboardInput.tick() is the same instruction point one frame lower on the stack, and it is
        // the only one of the two with access to ClientInput.moveVector (protected, package-private
        // reach -- this mixin extends ClientInput, ClientPlayerEntityMixin does not).
        //
        // Writing BOTH fields here is the whole trick, and is why Grim mode needs no packet-side
        // mixin. 26.1.2's LocalPlayer.tick() runs super.tick() (-> aiStep -> input.tick(), i.e. this
        // method) FIRST and only afterwards builds ServerboundPlayerInputPacket directly from
        // this.input.keyPresses -- so keyPresses written here is literally the packet GrimAC reads
        // as knownInput, while moveVector written here is literally what vanilla's physics uses.
        // One assignment, zero possibility of the wire and the local movement disagreeing.
        //
        // Reported input is unconditionally PURE FORWARD. GrimAC's loopVectors pins forward to +1
        // (and ignores knownInput.backward()) whenever player.isSprinting, so W / W+A / W+D are the
        // only key combinations it can predict correctly for a sprinting player. SprintModule sets
        // the reported yaw to realYaw + atan2(inputX, inputZ) on the same tick from the same key
        // source, which makes pure forward at that yaw algebraically identical to the real input at
        // the real yaw -- so nothing is lost by never reporting a strafe. moveVector is (0, 1)
        // rather than a rounded/rotated vector for the same reason: KeyboardInput builds it as
        // `new Vec2(strafe, forward).normalized()`, so an honest unit forward vector is exactly what
        // Grim's own transformInputsToVector(strafe=0, forward=1) produces.
        //
        // jump/shift/sprint are carried through untouched -- Grim reads knownInput.jump()/shift()
        // for its own jump and sneak predictions, and those are genuinely unchanged by this mode.
        SprintModule sprint = EUClient.MODULE_MANAGER == null ? null : EUClient.MODULE_MANAGER.getModule(SprintModule.class);
        if (sprint != null && sprint.isGrimCompensating()) {
            this.keyPresses = new Input(true, false, false, false,
                    this.keyPresses.jump(), this.keyPresses.shift(), this.keyPresses.sprint());
            this.moveVector = new Vec2(0.0f, 1.0f);

            // Mirrors homovore's sprint$applyBeforeJump, injected at the same point for the same
            // reason: this is the last moment before LocalPlayer.aiStep()'s own sprint bookkeeping
            // and before jumpFromGround(), and unlike SprintModule.onPlayerUpdate it runs AFTER
            // RotationManager resolved this tick's rotation, so shouldSprint() sees a live
            // isGrimCompensating() instead of last tick's. Without this the first tick of any fresh
            // omni movement would not sprint.
            Minecraft.getInstance().player.setSprinting(true);
        }
    }
}
