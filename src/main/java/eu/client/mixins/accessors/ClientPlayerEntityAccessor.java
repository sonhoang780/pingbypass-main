package eu.client.mixins.accessors;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
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

    // Yarn LocalPlayer.canSprint() no longer exists; closest surviving equivalent is
    // LocalPlayer.isSprintingPossible(boolean), which covers mobility-restriction, vehicle-sprint,
    // and the old food>6||flying check (via Player.hasEnoughFoodToDoExhaustiveManoeuvres(), not
    // directly targetable here since it's declared on the Player superclass, not LocalPlayer).
    // The boolean param gates an internal isInShallowWater() check; pass true since callers
    // already do their own water-state checks separately.
    @Invoker("isSprintingPossible")
    boolean invokeCanSprint(boolean ignoreWater);

    @Invoker("sendPosition")
    void invokeSendMovementPackets();

    @Accessor("lastOnGround")
    void setLastOnGround(boolean lastOnGround);

    @Accessor("yRotLast")
    void setLastYaw(float lastYaw);

    @Accessor("xRotLast")
    void setLastPitch(float lastPitch);

    // GrimV2 (InventoryControl): after sending a spoofed stationary Input packet ahead of a
    // container click, LocalPlayer.tick() would otherwise immediately notice the real (moving)
    // input still differs from what we just claimed and resend the true state next tick anyway --
    // harmless for the click itself (already sent), but writing lastSentInput here matches what
    // the real tick() loop does after every genuine send, keeping the two in sync.
    @Accessor("lastSentInput")
    void setLastSentInput(Input input);
}
