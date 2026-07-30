package eu.client.mixins.accessors;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LocalPlayer.class)
public interface ClientPlayerEntityAccessor {
    // BEST-EFFORT: Yarn LocalPlayer.isWalking() (private, checked movementForward>=0.8f or
    // submerged+hasForwardMovement) no longer exists by that name. The new canStartSprinting()
    // forward-input check is now `this.input.hasForwardImpulse()` (public, on the ClientInput
    // object, not on LocalPlayer itself, so it cannot be an @Invoker target here). Closest
    // surviving private no-arg boolean predicate on LocalPlayer about movement input is
    // isMoving() (checks input.getMoveVector().lengthSquared() > 0), used for auto-jump. NOT a
    // confirmed semantic match to old isWalking's 0.8f threshold - flagged as approximate.
    @Invoker("isMoving")
    boolean invokeIsWalking();

    // BEST-EFFORT: Yarn LocalPlayer.canSprint() (private, food>6 || allowFlying, AND !hasVehicle)
    // no longer exists by that name; sprint-food gating moved to Player.hasEnoughFoodToDoExhaustiveManoeuvres()
    // (protected, foodData.hasEnoughFood() || abilities.mayfly) which is invoked via
    // LocalPlayer.isSprintingPossible(). Vehicle handling is now separate (vehicleCanSprint).
    // Closest surviving equivalent; flagged as approximate.
    @Invoker("hasEnoughFoodToDoExhaustiveManoeuvres")
    boolean invokeCanSprint();

    @Invoker("sendPosition")
    void invokeSendMovementPackets();

    @Accessor("lastOnGround")
    void setLastOnGround(boolean lastOnGround);

    @Accessor("yRotLast")
    void setLastYaw(float lastYaw);

    @Accessor("xRotLast")
    void setLastPitch(float lastPitch);
}
