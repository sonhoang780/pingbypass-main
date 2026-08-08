package eu.client.modules.impl.movement;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.events.impl.TickEvent;
import eu.client.events.impl.UpdateMovementEvent;
import eu.client.mixins.accessors.ClientPlayerEntityAccessor;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.MovementUtils;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

// "Grim" mode = homovore's OMNI sprint, re-ported from scratch (dev/leonetic SprintModule.omni() +
// RotationManager.computeMoveFixInput + MixinClientPlayerEntity.homovore$moveFixAfterInputTick +
// MixinLivingEntityTravel.homovore$travelHead/Return/jumpYaw).
//
// WHY OMNI-SPRINT NEEDS ANY OF THIS AT ALL -- the exact GrimAC 2.0 rule being satisfied
// (common/src/main/java/ac/grim/grimac/predictionengine/predictions/PredictionEngine.java,
// loopVectors(), the "Stop omni-sprint" block, verified against branch 2.0 while writing this):
//
//     int forwardMin = player.isSprinting && !player.isSwimming ? 1 : -1;
//     ...
//     if (player.supportsEndTick()) {                 // <- TRUE on 1.21.2+, i.e. us
//         forwardMin = forwardMax = strafeMin = strafeMax = 0;
//         final KnownInput knownInput = player.packetStateData.knownInput;
//         if (knownInput.forward() || player.isSprinting && !player.isSwimming) { forwardMax++; forwardMin++; }
//         if (knownInput.backward() && (!player.isSprinting || player.isSwimming)) { forwardMax--; forwardMin--; }
//         if (knownInput.left())  { strafeMax++;  strafeMin++;  }
//         if (knownInput.right()) { strafeMax--;  strafeMin--;  }
//     }
//
// Three things fall out of that, and every one of them shaped this implementation:
//
//  1. On 1.21.2+ Grim does NOT brute-force a range of candidate inputs -- min == max, so it derives
//     ONE exact (strafe, forward) pair straight from the raw bits of our last
//     ServerboundPlayerInputPacket, and simulates that pair at the yaw from our last movement
//     packet. Our predicted position is therefore fully determined by (reported keys, reported yaw).
//     Anything we actually do that doesn't land on that exact vector is a setback. That is the
//     rubberband.
//  2. While isSprinting, forward is PINNED to +1 and knownInput.backward() is ignored outright. So
//     the only reported key combinations Grim can ever predict correctly for a sprinting player are
//     the ones containing forward: W, W+A, W+D. Reporting pure-strafe or backward while sprinting is
//     unpredictable-by-construction no matter how the yaw is faked -- which is why this mode reports
//     PURE FORWARD, always, and never the general 8-octant remap homovore's computeMoveFixInput
//     does. homovore only ever hits its non-forward octants transiently (its rotation queue lags one
//     tick behind its input), and it gets away with it; we don't need to take that risk because we
//     can make the reported yaw exact (see 3).
//  3. So the whole mode reduces to a single invariant, which is all the code below exists to keep:
//        reported keys = pure forward
//        reported yaw  = realYaw + atan2(inputX, inputZ)   (the direction WASD actually points)
//     Vanilla then computes world velocity as getInputVector((0,0,1), speed, reportedYaw), which is
//     algebraically identical to getInputVector((realStrafe,0,realForward), speed, realYaw) for all
//     8 keyboard directions -- so we move EXACTLY where the player asked, while Grim simulates
//     exactly what we move. Nothing is approximated and nothing is snapped.
//
// The invariant only holds if the reported yaw, the local physics yaw, and the reported keys all
// come from ONE evaluation. Three places had to agree, and each one is a place a previous attempt
// could have (and, from the symptoms, probably did) drift:
//
//  a. The reported keys AND the local movement input are the SAME field write --
//     ClientInput.keyPresses / ClientInput.moveVector, overwritten once in KeyboardInputMixin at the
//     tail of KeyboardInput.tick(). Verified against 26.1.2 bytecode: LocalPlayer.tick() runs
//     super.tick() (-> aiStep -> input.tick()) at offset 19 and only THEN, at offset 22, builds
//     ServerboundPlayerInputPacket straight from this.input.keyPresses. So one write feeds both the
//     wire and the physics and they cannot disagree. No separate packet-side fake, deliberately:
//     two independent computations of "the fake input" is exactly how the wire and the local
//     movement drift apart by a tick or by a rounding.
//  b. The reported yaw and the physics yaw are the SAME value -- RotationManager's queued Rotation.
//     The wire gets it via RotationManager's own swap over the UpdateMovementEvent window (which
//     encloses sendPosition(), see ClientPlayerEntityMixin), local physics gets it via
//     LivingEntityMixin's travel()/jumpFromGround() swaps, and both read
//     ROTATION_MANAGER.getRotation().getYaw(). Never a private copy of the yaw in this class.
//  c. The key state is read from mc.options.keyUp/keyDown/keyLeft/keyRight.isDown() -- NOT from
//     mc.player.input.keyPresses. grimUpdate() runs on PlayerUpdateEvent, which fires BEFORE
//     LocalPlayer.tick() calls super.tick(), i.e. before this tick's input.tick() has run, so
//     keyPresses there is still LAST tick's snapshot. Deriving the yaw from last tick's keys while
//     input.tick() derives the movement from this tick's keys puts a one-tick lag between the
//     reported yaw and the real direction that shows up ONLY on the tick a direction changes --
//     which is precisely the reported symptom ("backward alone from a standstill worked, strafing
//     and direction changes rubberbanded"). mc.options.keyX.isDown() is the exact same source
//     KeyboardInput.tick() itself reads (verified in bytecode), so the two are identical by
//     construction.
//
// Deliberately NOT depended on: RotationsModule.movementFix. Its onKeyboardTick applies
// Math.round() to the rotated input, which turns a diagonal (0.707, 0.707) into (1, 1) -- sqrt(2)x
// too fast, an instant setback. Grim mode writes moveVector itself, after that handler, so it wins
// whether movementFix is on or off (and when it is on, its velocity/jump handlers substitute the
// same yaw we already substitute, so they become no-ops rather than double-compensating).
@RegisterModule(name = "Sprint", description = "Makes it so that you are always sprinting when possible.", category = Module.Category.MOVEMENT)
public class SprintModule extends Module {
    // Instant: same technique HoleSnapModule's own homing movement uses -- set the horizontal
    // velocity directly toward the input direction every tick instead of letting vanilla's normal
    // acceleration/friction physics ramp it up over several ticks, so full speed applies
    // instantly the moment a movement key is pressed (and stops instantly on release, no slide).
    public ModeSetting mode = new ModeSetting("Mode", "The limits to when you can be sprinting.", "Rage", new String[]{"Legit", "Rage", "RageStrict", "Instant", "Grim"});
    public NumberSetting instantSpeed = new NumberSetting("InstantSpeed", "Per-tick horizontal speed to move at (blocks/tick) when Mode is Instant.", new ModeSetting.Visibility(mode, "Instant"), (float) MovementUtils.DEFAULT_SPEED, 0.05f, 0.6f);

    // Captured here (fires before UpdateMovementEvent) instead of read live inside
    // onUpdateMovement below -- RotationManager's "Normal"/silent-rotation queue (e.g. AutoCrystal's
    // default Rotate=Normal) really does overwrite mc.player's actual getYRot() for the DURATION of
    // UpdateMovementEvent (set at MAX priority, restored at MIN priority), so a live read while
    // AutoCrystal is mid-place/attack returned the CRYSTAL's facing yaw instead of the camera's --
    // Instant then set velocity toward wherever the crystal was, not wherever WASD pointed (the
    // reported "đi theo hướng đặt crystal"). PlayerUpdateEvent runs before that window opens, so
    // the yaw here is always the real one.
    private float cachedYaw;

    /** True iff grimUpdate() queued a rotation for THIS tick. See isGrimCompensating(). */
    private boolean grimQueued;

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (mc.player == null) return;

        cachedYaw = mc.player.getYRot();

        // Must run before the shouldSprint()/setSprinting() call below AND before RotationManager's
        // own PlayerUpdateEvent handler. RotationManager subscribes at Integer.MIN_VALUE and
        // EventHandler.insert() orders listeners highest-priority-first, so its
        // `rotation = queue.peek()` refresh runs LAST -- meaning a rotation queued here is picked up
        // on this same tick, not the next one. That is what lets the reported yaw be exact rather
        // than one tick stale (see the class comment, point c).
        if (isGrim()) grimUpdate();

        boolean sprint = shouldSprint();

        if (sprint) {
            mc.player.setSprinting(true);
        } else if (mc.player.isSprinting()) {
            // Was never explicitly desprinting here, only ever force-asserting TRUE every tick
            // shouldSprint() held -- relying on vanilla's own aiStep() to notice and flip it back
            // off whenever shouldSprint() went false (e.g. the wall-collision case below). Since
            // we're the ones re-forcing true every tick to begin with, that's not a safe
            // assumption to lean on for a state a real anticheat is actively watching (SprintE) --
            // explicitly desprint the instant our own condition says we shouldn't be.
            mc.player.setSprinting(false);
        }
    }

    @SubscribeEvent
    public void onUpdateMovement(UpdateMovementEvent event) {
        if (mc.player == null) return;

        if (mode.getValue().equalsIgnoreCase("Instant")) {
            Vec2 move = mc.player.input.getMoveVector();
            Vec3 v = mc.player.getDeltaMovement();

            if (move.x != 0.0f || move.y != 0.0f) {
                // Inverse of the strafe/forward <- world-delta rotation (yaw rotates world->input
                // via [xxa;zza] = [[cos,sin],[-sin,cos]]*[dx;dz], same formula HoleSnap's Strict
                // mode uses the other direction) -- input->world is the transpose of that rotation.
                double yawRad = Math.toRadians(cachedYaw);
                double sin = Math.sin(yawRad), cos = Math.cos(yawRad);
                double vx = move.x * cos - move.y * sin;
                double vz = move.x * sin + move.y * cos;
                double speed = instantSpeed.getValue().doubleValue();

                mc.player.setDeltaMovement(vx * speed, v.y, vz * speed);
            } else {
                // No movement key held -- this only ever force-set horizontal velocity to full
                // speed above, never back down, so releasing keys left vanilla's normal ground
                // friction to decay it over several ticks (0.2 -> 24.29km/h instantly on press,
                // but a gradual slide back to 0 on release instead of stopping the same way).
                // Zero it outright, same instant-snap treatment, every tick no key is held.
                mc.player.setDeltaMovement(0.0, v.y, 0.0);
            }
        }
    }

    // RageStrict (ported from hachimi's RAGE_STRICT): continuously fakes the reported rotation to
    // face the movement direction (45 deg for diagonal, 90 for pure strafe, 180+-45 for backward/
    // backward-diagonal -- same offsets hachimi's own getSprintYaw uses), every tick, via
    // RotationManager's real silent-rotation queue. Requires RotationsModule.movementFix to be ON
    // for the real velocity to actually follow the faked rotation -- without it this only fakes the
    // packet.
    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.player == null) return;
        if (!mode.getValue().equalsIgnoreCase("RageStrict") || !mc.player.isSprinting()) return;

        float sprintYaw = getSprintYaw(mc.player.getYRot());
        EUClient.ROTATION_MANAGER.rotate(sprintYaw, mc.player.getXRot(), this);
    }

    private boolean isGrim() {
        return mode.getValue().equalsIgnoreCase("Grim");
    }

    // Queues the omni-sprint rotation for this tick. Direct port of homovore's SprintModule.omni().
    private void grimUpdate() {
        grimQueued = false;

        // Fall-flying is excluded exactly as homovore does: elytra movement doesn't go through the
        // strafe/forward input vector at all, so remapping the input would change where we fly
        // without changing what Grim predicts. Passengers likewise -- LocalPlayer.tick() sends a
        // Rot packet and skips sendPosition() entirely while riding, so RotationManager's swap never
        // reaches the wire and the reported yaw would not match the physics yaw.
        if (mc.player.isFallFlying() || mc.player.isPassenger()) return;

        // Read the raw key bindings, NOT mc.player.input.keyPresses -- see class comment point (c).
        // Same convention as KeyboardInput.tick(): +Z is forward, +X is right.
        int inputX = (mc.options.keyRight.isDown() ? 1 : 0) - (mc.options.keyLeft.isDown() ? 1 : 0);
        int inputZ = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        if (inputX == 0 && inputZ == 0) return;

        // atan2(x, z) is the clockwise-from-forward angle of the input direction, which is exactly
        // the yaw offset needed to point at it: W+D -> +45, D -> +90, S -> +180, S+A -> -135, etc.
        // Identical to getSprintYaw()'s 45/90/180 offset table below for all 8 keyboard directions
        // (that table is only kept because RageStrict uses it and reads keyPresses, which is fine
        // there because RageStrict runs on TickEvent).
        float moveAngle = (float) Math.toDegrees(Math.atan2(inputX, inputZ));
        float targetYaw = Mth.wrapDegrees(mc.player.getYRot() + moveAngle);

        // Real pitch: pitch plays no part in horizontal movement, and reporting it unchanged keeps
        // the rotation packet's pitch honest.
        EUClient.ROTATION_MANAGER.rotate(targetYaw, mc.player.getXRot(), this);
        grimQueued = true;
    }

    /**
     * The single gate for every piece of Grim's fake (input keys in KeyboardInputMixin, travel() yaw
     * and jumpFromGround() yaw in LivingEntityMixin). True only when the rotation RotationManager
     * actually resolved for this tick -- the one that will be on the wire -- is OURS.
     *
     * <p>Sprint has no entry in RotationManager.PRIORITIES, so it resolves at priority 0 and loses
     * the queue to KillAura/AutoCrystal/SpeedMine whenever those are aiming. In that window the wire
     * yaw is the other module's, so faking pure-forward input would walk the player wherever that
     * module happens to be looking. Rather than compensate against a foreign yaw (homovore's
     * computeMoveFixInput snaps to the nearest 45 deg octant, which for a sprinting player can land
     * on a pure-strafe or backward octant -- unpredictable by Grim, see class comment point 2), Grim
     * simply stands down for those ticks and shouldSprint() falls back to plain forward-only sprint.
     *
     * <p>Callers must only invoke this after RotationManager's PlayerUpdateEvent handler has run,
     * i.e. anywhere from aiStep onwards. All three call sites are inside aiStep or later.
     */
    public boolean isGrimCompensating() {
        // mc.player null-check included: grimQueued survives a world/dimension change (grimUpdate
        // simply stops being called), and a queued Rotation stays valid for 100ms, so without it a
        // disconnect could leave all three consumers thinking the fake is live.
        if (mc.player == null || !isToggled() || !isGrim() || !grimQueued) return false;
        var rotation = EUClient.ROTATION_MANAGER.getRotation();
        return rotation != null && rotation.getModule() == this;
    }

    /** The faked yaw currently on the wire. Only meaningful while isGrimCompensating(). */
    public float getGrimYaw() {
        return EUClient.ROTATION_MANAGER.getRotation().getYaw();
    }

    // Ported verbatim (offsets/branches) from hachimi's SprintModule.getSprintYaw -- byte-identical
    // to Shoreline's InputUtil.getYawFromInput. Used by RageStrict.
    private float getSprintYaw(float yaw) {
        var keys = mc.player.input.keyPresses;
        boolean forward = keys.forward() && !keys.backward();
        boolean backward = keys.backward() && !keys.forward();
        boolean left = keys.left() && !keys.right();
        boolean right = keys.right() && !keys.left();

        if (forward) {
            if (left) yaw -= 45.0f;
            else if (right) yaw += 45.0f;
        } else if (backward) {
            yaw += 180.0f;
            if (left) yaw += 45.0f;
            else if (right) yaw -= 45.0f;
        } else if (left) {
            yaw -= 90.0f;
        } else if (right) {
            yaw += 90.0f;
        }

        return Mth.wrapDegrees(yaw);
    }

    @Override
    public void onEnable() {
        if (mc.player == null) return;
        mc.player.setSprinting(shouldSprint());
    }

    @Override
    public void onDisable() {
        grimQueued = false;
        if (mc.player == null) return;
        mc.player.setSprinting(false);
    }

    @Override
    public String getMetaData() {
        return mode.getValue();
    }

    public boolean shouldSprint() {
        if (!((ClientPlayerEntityAccessor) mc.player).invokeCanSprint(true)) return false;
        if (mc.player.isInWater() && !mc.player.isUnderWater()) return false;
        if (mc.player.isSwimming() && !mc.player.onGround() && !mc.player.input.keyPresses.shift() && !mc.player.isInWater()) return false;

        // Ported from hachimi's canSprint() -- these were only ever checked in the Legit (else)
        // branch below, via vanilla's OWN Player.canStartSprinting/isSprinting internals, which
        // the Rage/RageStrict/Instant branch bypasses entirely by design (that's the whole
        // point of those modes). But bypassing the DIRECTION/wall gating vanilla does isn't the
        // same as bypassing THESE -- blindness, fall-flying, and lava are each their own GrimAC
        // check (SprintD/SprintF/an equivalent lava check), and low hunger is SprintA
        // ("Sprinting with too low hunger") -- none of those are about movement legitimacy, they're
        // just "is sprinting even POSSIBLE right now", true regardless of which mode is forcing the
        // flag. Missing here meant Rage/RageStrict/Instant could force-sprint through all four
        // states unconditionally, each one individually flaggable server-side.
        if (mc.player.isInLava()) return false;
        if (mc.player.onClimbable()) return false;
        if (mc.player.hasEffect(MobEffects.BLINDNESS)) return false;
        if (mc.player.isFallFlying()) return false;
        if (mc.player.getFoodData().getFoodLevel() <= 6) return false;

        if (mode.getValue().equalsIgnoreCase("Rage") || mode.getValue().equalsIgnoreCase("RageStrict") || mode.getValue().equalsIgnoreCase("Instant") || isGrim()) {
            // GrimAC's real SprintE check ("Sprinting while colliding with a wall") flags/sets back
            // ANY tick where isSprinting is still true while horizontally colliding with a wall
            // (hard collision, not a minor/soft graze) -- UNLESS sprinting only just started that
            // exact tick. This branch never had that check at all (only the Legit branch below
            // did), so Rage/Instant kept re-forcing setSprinting(true) every single tick
            // straight through a wall, which is exactly what SprintE exists to catch. Same guard
            // as Legit's, gated on already-sprinting so the FIRST tick of a fresh sprint that
            // happens to touch a wall isn't blocked (matches SprintE's own startedSprintingThisTick
            // exemption).
            if (mc.player.horizontalCollision && !mc.player.minorHorizontalCollision && mc.player.isSprinting()) return false;

            // KeyboardInput.tick() builds moveVector as new Vec2(strafe, forward).normalized() --
            // keyboard input is always an exact unit vector (or zero), never a partial analog
            // value, so pure diagonal movement (e.g. W+D) normalizes to (~0.707, ~0.707) on BOTH
            // axes. A per-axis >=0.8 threshold (meant to filter weak analog-stick input) rejects
            // that as "not really moving", failing to trigger sprint at all for diagonal-only
            // movement -- the reported "can't reach ~20.2km/h moving diagonally" bug. Check the
            // raw key presses directly instead -- makes the water/non-water split above dead code
            // (both used the same condition once this got fixed), collapsed into one return.
            // Grim only earns the omni ("any direction counts as sprinting") allowance on the ticks
            // it is actually compensating -- i.e. the ticks where the server is being told we face
            // the movement direction and are pressing W. On any other tick (another aim module owns
            // the rotation queue, or the fake couldn't be applied) sprinting sideways/backwards is
            // exactly dead end #1: Grim pins forward to +1 while isSprinting and would predict us
            // walking forwards. Fall back to vanilla's own condition for those ticks.
            if (isGrim() && !isGrimCompensating()) return mc.player.input.keyPresses.forward();

            return mc.player.input.keyPresses.forward() || mc.player.input.keyPresses.backward() || mc.player.input.keyPresses.left() || mc.player.input.keyPresses.right();
        } else {
            // Blindness/fall-flying/lava/ladder/hunger are already checked above, unconditionally
            // for every mode -- not duplicated here.
            if (!((ClientPlayerEntityAccessor) mc.player).invokeIsWalking()) return false;
            if (mc.player.isUsingItem() && (!EUClient.MODULE_MANAGER.getModule(NoSlowModule.class).isToggled() || !EUClient.MODULE_MANAGER.getModule(NoSlowModule.class).items.getValue())) return false;
            if (mc.player.horizontalCollision && !mc.player.minorHorizontalCollision) return false;
            return mc.player.input.hasForwardImpulse();
        }
    }
}
