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
        // Reported input is PURE FORWARD for non-diagonal real input, or FORWARD+strafe (matching
        // whichever real strafe key is down) for a real diagonal -- see SprintModule.getGrimStrafe()
        // and grimUpdate()'s derivation comment for exactly which and why. GrimAC's loopVectors pins
        // forward to +1 (and ignores knownInput.backward()) whenever player.isSprinting, so those are
        // the only reported key combinations it can predict correctly for a sprinting player.
        // SprintModule sets the reported yaw from the same key source on the same tick so that
        // whichever combo we report is algebraically identical, real-world, to the real input at the
        // real yaw -- nothing is lost, and diagonal real input additionally picks up vanilla's own
        // diagonal-without-turning speed bonus this way (same input SHAPE as the real keys, just
        // possibly a different reported yaw).
        //
        // 2026-08-12 (REVERTED once, see git history): first attempt at extending the diagonal case
        // to backward-diagonal too used a fixed always-report-right diagonal, and got the yaw
        // compensation sign wrong -- broke straight W movement. Now side-aware (getGrimStrafe()) and
        // numerically re-verified, see grimUpdate().
        //
        // jump/shift/sprint are carried through untouched -- Grim reads knownInput.jump()/shift()
        // for its own jump and sneak predictions, and those are genuinely unchanged by this mode.
        // MovementFix, always on, for a rotation owned by anyone OTHER than Sprint Grim (AutoCrystal /
        // SpeedMine / KillAura at Rotate=Normal). Must run every tick even when it declines, because
        // computeMoveFix clears RotationManager's moveFixActive flag that LivingEntityMixin's physics
        // yaw swap reads -- returning early here would leave it stale. It returns null (complete
        // no-op, real WASD untouched) on a Sprint Grim tick, on a tick with no rotation at all, and on
        // the sprinting-into-a-non-forward-octant case; see its doc for all three.
        //
        // Both fields are written, exactly like the Grim block below: moveVector is what vanilla
        // physics moves by (at the spoofed yaw, courtesy of LivingEntityMixin) and keyPresses is
        // verbatim what LocalPlayer.tick() puts in ServerboundPlayerInputPacket for GrimAC to predict
        // from. Writing only one of the two was the previous implementation's central bug.
        float[] fix = EUClient.ROTATION_MANAGER.computeMoveFix(-this.moveVector.x, this.moveVector.y);
        if (fix != null) {
            float right = fix[0], forward = fix[1];
            this.keyPresses = new Input(forward > 0.0f, forward < 0.0f, right < 0.0f, right > 0.0f,
                    this.keyPresses.jump(), this.keyPresses.shift(), this.keyPresses.sprint());
            this.moveVector = new Vec2(-right, forward); // moveVector.x is the LEFT impulse
        }

        SprintModule sprint = EUClient.MODULE_MANAGER == null ? null : EUClient.MODULE_MANAGER.getModule(SprintModule.class);
        if (sprint != null && sprint.isGrimCompensating()) {
            int strafe = sprint.getGrimStrafe();
            this.keyPresses = new Input(true, false, strafe > 0, strafe < 0,
                    this.keyPresses.jump(), this.keyPresses.shift(), this.keyPresses.sprint());
            // The RAW SQUARE pair (+-1, 1) for a diagonal, not the pre-normalized (+-0.7071, 0.7071)
            // it used to be -- same reasoning as RotationManager.OCTANTS, see the long note there.
            // Entity.getInputVector only normalizes when lengthSqr() > 1.0, so both forms are
            // bit-identical on a native 26.1.2 connection; they stop being identical the moment a
            // downgraded protocol re-applies old vanilla's `xxa *= 0.98F` before that check, at which
            // point the unit form falls UNDER the threshold (0.96), never normalizes, and moves 2%
            // slower than the full-speed diagonal GrimAC's legacy transformer predicts from the raw
            // pair -- a setback on diagonals only, on ViaFabricPlus-downgraded connections only.
            // strafe == 0 -> honest (0, 1), unchanged.
            this.moveVector = strafe == 0 ? new Vec2(0.0f, 1.0f)
                    : new Vec2(strafe > 0 ? 1.0f : -1.0f, 1.0f);

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
