package eu.client.modules.impl.movement;

import eu.client.EUClient;
import lombok.Getter;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.ClientRotationEvent;
import eu.client.events.impl.PlayerMoveEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.mixins.accessors.ClientPlayerEntityAccessor;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.MovementUtils;
import eu.client.utils.minecraft.NetworkUtils;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
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
//     the ones containing forward: W, W+A, W+D (real backward/pure-strafe bits are simply irrelevant
//     to what Grim simulates). Reporting anything else -- e.g. a genuine backward-diagonal -- is
//     unpredictable-by-construction no matter how the yaw is faked. This mode reports PURE FORWARD
//     for non-diagonal real input, and never the general 8-octant remap homovore's computeMoveFixInput
//     does (homovore only ever hits its non-forward octants transiently, its rotation queue lags one
//     tick behind its input, and gets away with it; we don't take that risk).
//
//     For a real DIAGONAL input (W+A, W+D, S+A, or S+D), it instead reports forward + whichever real
//     strafe key (A/D) is actually down, at a yaw offset chosen so the fake's real-world displacement
//     exactly matches the real diagonal's (see grimUpdate()'s derivation, and getGrimStrafe()) --
//     picking up vanilla's diagonal-without-turning speed bonus (cattyn, 20.62 vs 20.20km/h, see
//     grimUpdate()) for all four diagonals, not only the two (W+A/W+D) simple enough to need no fake
//     at all. That W+A/W+D case is the offset formula's own zero-offset special case, not a separate
//     code path.
//  3. So the whole mode reduces to a single invariant, which is all the code below exists to keep:
//        reported keys = pure forward, or forward + real-matching-strafe for a real diagonal
//        reported yaw  = realYaw + atan2(inputX, inputZ) [+/-45 for the diagonal case]
//     Vanilla then computes world velocity from the reported (strafe, forward) pair at reportedYaw,
//     which lands on the exact same real-world direction (and, for diagonals, the same input SHAPE)
//     as the real input at realYaw for all 8 keyboard directions -- so we move EXACTLY where the
//     player asked, while Grim simulates exactly what we move. Nothing is approximated and nothing
//     is snapped.
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
// Deliberately NOT depended on: RotationsModule.movementFix (2026-08-12: its Math.round() bug that
// used to make this a bad idea -- turning a diagonal (0.707, 0.707) into (1, 1), sqrt(2)x too fast
// -- is fixed now, but the independence stands on its own: Grim needs the reported KEYS themselves
// to change, not just the input vector movementFix remaps, and needs that from the exact same key
// source the yaw is computed from on the exact same tick -- see point (c) above). Grim mode writes
// moveVector itself, after that handler, so it wins whether movementFix is on or off (and when it
// is on, its velocity/jump handlers substitute the same yaw we already substitute, so they become
// no-ops rather than double-compensating).
@RegisterModule(name = "Sprint", description = "Makes it so that you are always sprinting when possible.", category = Module.Category.MOVEMENT)
public class SprintModule extends Module {
    // Instant: same technique HoleSnapModule's own homing movement uses -- set the horizontal
    // velocity directly toward the input direction every tick instead of letting vanilla's normal
    // acceleration/friction physics ramp it up over several ticks, so full speed applies
    // instantly the moment a movement key is pressed (and stops instantly on release, no slide).
    public ModeSetting mode = new ModeSetting("Mode", "The limits to when you can be sprinting.", "Rage", new String[]{"Legit", "Rage", "Instant", "Grim"});
    public NumberSetting instantSpeed = new NumberSetting("InstantSpeed", "Per-tick horizontal speed to move at (blocks/tick) when Mode is Instant.", new ModeSetting.Visibility(mode, "Instant"), (float) MovementUtils.DEFAULT_SPEED, 0.05f, 0.6f);
    public BooleanSetting instantWater = new BooleanSetting("Water", "Keeps applying the Instant speed override while in water.", new ModeSetting.Visibility(mode, "Instant"), true);
    public BooleanSetting instantLava = new BooleanSetting("Lava", "Keeps applying the Instant speed override while in lava.", new ModeSetting.Visibility(mode, "Instant"), false);

    // Captured here (fires before UpdateMovementEvent) instead of read live inside
    // onUpdateMovement below -- RotationManager's "Normal"/silent-rotation queue (e.g. AutoCrystal's
    // default Rotate=Normal) really does overwrite mc.player's actual getYRot() for the DURATION of
    // UpdateMovementEvent (set at MAX priority, restored at MIN priority), so a live read while
    // AutoCrystal is mid-place/attack returned the CRYSTAL's facing yaw instead of the camera's --
    // Instant then set velocity toward wherever the crystal was, not wherever WASD pointed (the
    // reported "đi theo hướng đặt crystal"). PlayerUpdateEvent runs before that window opens, so
    // the yaw here is always the real one.
    private float cachedYaw;

    // Same bug class as cachedYaw above, missed when that fix was written. KeyboardInput.tick()
    // runs INSIDE super.tick() (between PlayerUpdateEvent's tick$BEFORE and UpdateMovementEvent's
    // tick$AFTER), and RotationManager.computeMoveFix() -- called from the tail of that same
    // KeyboardInput.tick() whenever ANY module holds a rotation this tick (AutoCrystal/SpeedMine/
    // KillAura Rotate=Normal or MovementSync, not just Sprint's own Grim) -- REMAPS moveVector to
    // an octant relative to the SPOOFED yaw, specifically so vanilla's OWN travel()/getInputVector
    // (which also gets swapped onto the spoofed yaw for the same window) reproduces the real
    // input's world direction. Reading mc.player.input.getMoveVector() live inside onUpdateMovement
    // below picks up that ALREADY-REMAPPED vector, then combines it with cachedYaw (the REAL yaw)
    // instead of the spoofed one it was actually remapped for -- two mismatched halves of two
    // different systems. Instant's own velocity write bypasses vanilla physics entirely, so it
    // never wanted or needed that remap in the first place; captured here (PlayerUpdateEvent, same
    // place as cachedYaw, before KeyboardInput.tick() ever runs) it's the real, untouched WASD
    // state. Reported as "quay sau nhấn S đi lùi rất chậm, sai hướng" while an aim module's
    // Rotate=Normal/MovementSync happened to be live the same tick.
    private Vec2 cachedMove;

    /** True iff grimUpdate() queued a rotation for THIS tick. See isGrimCompensating(). */
    private boolean grimQueued;

    // Reasserted every tick from onClientRotation below instead of the old one-shot rotate() calls
    // -- see RotationManager class doc. Only Grim writes this (grimUpdate(), via PlayerUpdateEvent);
    // onPlayerUpdate() clears it to null on every tick isGrim() is false, so switching away from Grim
    // mid-sprint can't leave a stale fake applying forever.
    private Float pendingYaw;
    private float pendingPitch;

    /** 0 = report pure forward (non-diagonal input). +1/-1 = report forward+left / forward+right
     *  (a real diagonal input, forward OR backward-diagonal). See grimUpdate()'s derivation. Only
     *  meaningful while isGrimCompensating(); read by KeyboardInputMixin. */
    @Getter
    private int grimStrafe;

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (mc.player == null) return;

        cachedYaw = mc.player.getYRot();
        cachedMove = mc.player.input.getMoveVector();

        // Must run before the shouldSprint()/setSprinting() call below AND before RotationManager's
        // own PlayerUpdateEvent handler. RotationManager subscribes at Integer.MIN_VALUE and
        // EventHandler.insert() orders listeners highest-priority-first, so its
        // `rotation = queue.peek()` refresh runs LAST -- meaning a rotation queued here is picked up
        // on this same tick, not the next one. That is what lets the reported yaw be exact rather
        // than one tick stale (see the class comment, point c).
        if (isGrim()) grimUpdate();
        else pendingYaw = null;

        boolean sprint = shouldSprint();

        if (sprint) {
            // On 1.20.x (legacy protocol), the slot-swap desync needed to cancel the "using item"
            // state on the server is now handled by NoSlowModule.onPlayerUpdate() -- it fires at
            // PlayerUpdateEvent (same event as us, before super.tick()/aiStep()), so by the time
            // aiStep() detects the sprint transition and sends START_SPRINTING, the server already
            // thinks the player stopped eating. No separate desync needed here.
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

    // Was UpdateMovementEvent + mc.player.setDeltaMovement() -- fires AFTER super.tick() has
    // already called travel()/move() for this tick (see ClientPlayerEntityMixin's tick$AFTER),
    // so the velocity written there only takes effect on the FOLLOWING tick's move() call, and
    // that call is still full, un-intercepted vanilla travel() physics (friction/momentum
    // blending toward the new velocity, not snapping to it). Reported as "0 -> 11.71 -> 20.62
    // km/h thay vì nhảy thẳng" -- a 1-tick-late, friction-smoothed ramp instead of an instant
    // snap, while AccelerateModule (same target speed, MovementUtils.DEFAULT_SPEED = 20.69km/h)
    // jumps straight there in one step. Accelerate/Speed both hook PlayerMoveEvent instead,
    // which this project's own move() mixin (ClientPlayerEntityMixin#move) posts from INSIDE
    // travel() and lets cancel+replace the actual vector physics is about to apply THIS tick --
    // the real "instant" hook. Moved Instant onto the same one.
    @SubscribeEvent
    public void onPlayerMove(PlayerMoveEvent event) {
        if (mc.player == null) return;
        if (!mode.getValue().equalsIgnoreCase("Instant")) return;

        // Was setting the Instant speed override unconditionally off move-key state alone --
        // shouldSprint()'s hunger/lava gates (SprintA/lava's own GrimAC checks) only ever stopped
        // the SPRINTING FLAG, not this direct velocity write, so low hunger or being in lava still
        // moved at full Instant speed. Same low-hunger threshold shouldSprint() uses (<=6, matches
        // vanilla's own Player.canStartSprinting), plus dedicated Water/Lava toggles since Instant's
        // homing velocity write ignores vanilla's swimming/lava drag entirely unlike normal sprint.
        // ElytraFly's Control mode drives horizontal velocity itself from inside PlayerTravelEvent,
        // gated on exactly this condition (mode == Control && isFallFlying, see its onPlayerTravel).
        // Instant hooks PlayerMoveEvent, which this project posts from inside travel() AFTER that --
        // so it overwrote ElytraFly's flight velocity with its own ground-speed vector every tick
        // the two were on together. Stand down for those ticks and let ElytraFly own the flight.
        ElytraFlyModule elytra = EUClient.MODULE_MANAGER.getModule(ElytraFlyModule.class);
        if (elytra.isToggled() && elytra.mode.getValue().equalsIgnoreCase("Control") && mc.player.isFallFlying()) return;

        boolean instantAllowed = mc.player.getFoodData().getFoodLevel() > 6
                && (instantLava.getValue() || !mc.player.isInLava())
                && (instantWater.getValue() || !mc.player.isInWater());
        if (!instantAllowed) return;

        Vec2 move = cachedMove;

        if (move.x != 0.0f || move.y != 0.0f) {
            // move (real vanilla getMoveVector()) is NOT guaranteed unit length here -- vanilla's
            // own "square movement" input adjustment (KeyboardInput's modifyInputSpeedForSquareMovement,
            // same one GrimAC's ModernInputTransformer mirrors) deliberately rescales a diagonal
            // press back up toward sqrt(2) so normal per-axis-clamped movement doesn't get slower
            // going diagonal. Rotating that straight into a velocity (below) carried the same
            // sqrt(2) into Instant's OWN speed override -- same instantSpeed setting, but diagonal
            // came out ~1.41x faster than straight ("cùng Speed nhưng tốc độ khác nhau khi đi
            // thẳng và đi chéo"). Instant is a flat magnitude override, not a replica of vanilla's
            // per-axis physics, so normalize the direction first and let instantSpeed alone decide
            // the magnitude, same in every direction.
            double moveLength = Math.sqrt(move.x * move.x + move.y * move.y);
            double normX = move.x / moveLength, normY = move.y / moveLength;

            // Inverse of the strafe/forward <- world-delta rotation (yaw rotates world->input
            // via [xxa;zza] = [[cos,sin],[-sin,cos]]*[dx;dz], same formula HoleSnap's Strict
            // mode uses the other direction) -- input->world is the transpose of that rotation.
            double yawRad = Math.toRadians(cachedYaw);
            double sin = Math.sin(yawRad), cos = Math.cos(yawRad);
            double vx = normX * cos - normY * sin;
            double vz = normX * sin + normY * cos;
            double speed = instantSpeed.getValue().doubleValue();

            event.setMovement(new Vec3(vx * speed, event.getMovement().y, vz * speed));
        } else {
            // No movement key held -- zero horizontal outright, same instant-snap treatment as
            // the press case, instead of leaving vanilla's normal ground friction to decay it
            // over several ticks on release.
            event.setMovement(new Vec3(0.0, event.getMovement().y, 0.0));
        }

        event.setCancelled(true);
    }

    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent event) {
        if (pendingYaw == null || event.isCancelled()) return;

        event.setYaw(pendingYaw);
        event.setPitch(pendingPitch);
        event.setOwner(this);
    }

    private boolean isGrim() {
        return mode.getValue().equalsIgnoreCase("Grim");
    }

    // Queues the omni-sprint rotation for this tick. Direct port of homovore's SprintModule.omni().
    private void grimUpdate() {
        grimQueued = false;
        // Clear any pendingYaw we set last tick right away -- every early return below means "don't
        // fake this tick", and leaving a stale value would let onClientRotation keep applying it
        // after isGrimCompensating() has already gone false for this tick, disagreeing with local
        // physics. See the pendingYaw field doc.
        pendingYaw = null;

        // Fall-flying is excluded exactly as homovore does: elytra movement doesn't go through the
        // strafe/forward input vector at all, so remapping the input would change where we fly
        // without changing what Grim predicts. Passengers likewise -- LocalPlayer.tick() sends a
        // Rot packet and skips sendPosition() entirely while riding, so RotationManager's swap never
        // reaches the wire and the reported yaw would not match the physics yaw.
        if (mc.player.isFallFlying() || mc.player.isPassenger()) return;

        // Freecam likewise: it reads mc.options.keyUp/Down/Left/Right/Jump/Shift.isDown() directly
        // to drive the free-floating camera around, so it can only ever zero the DERIVED state
        // (mc.player.input.keyPresses/moveVector, see FreecamModule's own doc on that fix) -- it
        // cannot also zero the raw KeyMapping.isDown() bits without breaking its own camera
        // movement. grimUpdate() reads those same raw bindings on purpose (class comment point c),
        // which made it blind to Freecam's suppression entirely: the real hidden player kept
        // getting the omni-sprint rotation swap + vanilla's own real jumpFromGround() sprint-boost
        // (both driven by isSprinting()+yaw, independent of moveVector) off the operator's actual
        // WASD while they thought only the camera was moving. Bail out here, same shape as the
        // fall-flying/passenger exclusions above -- shouldSprint()'s Grim branch already falls
        // back to reading mc.player.input.keyPresses (which Freecam zeroes) once isGrimCompensating()
        // is false, so this alone silences the fake rotation AND the sprint-boost translation.
        if (EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.visuals.FreecamModule.class).isToggled()) return;

        // Read the raw key bindings, NOT mc.player.input.keyPresses -- see class comment point (c).
        // Same convention as KeyboardInput.tick(): +Z is forward, +X is right.
        int inputX = (mc.options.keyRight.isDown() ? 1 : 0) - (mc.options.keyLeft.isDown() ? 1 : 0);
        int inputZ = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        if (inputX == 0 && inputZ == 0) return;

        // cattyn (Discord, 2026-08-08): moving diagonally without rotating to face it is ~0.49km/h
        // faster than the same diagonal movement WITH the camera turned to face it (20.69 vs
        // 20.20km/h, confirmed by xcvT) -- a real vanilla movement quirk, unrelated to any anti-cheat
        // spoofing. W+A/W+D-while-sprinting is exactly the one diagonal case the class comment above
        // (point 2) already says Grim predicts correctly with zero faking needed -- forward XOR one
        // strafe key, sprinting pins forward to +1 either way. Real physics runs completely unfaked
        // for that case, hence the natural bonus.
        //
        // 2026-08-12: extended this to S+A/S+D (backward-diagonal) too, which can't just skip the
        // fake the same way -- Grim ignores knownInput.backward() outright while sprinting, so
        // reporting real backward-diagonal keys would have it predict FORWARD-diagonal instead of
        // backward-diagonal, an immediate mismatch. Instead we report a FORWARD+strafe diagonal
        // (matching whichever real strafe key -- A or D -- is actually down) at a yaw offset that
        // makes that fake diagonal's real-world displacement land exactly on the real backward-
        // diagonal direction, so real physics still moves the genuine diagonal shape (same speed
        // bonus) while Grim simulates a combo it can actually predict.
        //
        // Derivation (verified by direct substitution into vanilla's own world-velocity formula --
        // worldX = strafe*cos(yaw) - forward*sin(yaw), worldZ = strafe*sin(yaw) + forward*cos(yaw),
        // same one EntityAccessor.invokeMovementInputToVelocity/SprintModule's own Instant-mode
        // comment use -- not re-derived abstractly this time, the previous attempt's abstract trig
        // had a sign error that only showed up in-game):
        //   Let side = -1 if A is down (report LEFT, GrimAC strafe=+1) else +1 if D is down (report
        //   RIGHT, GrimAC strafe=-1) -- i.e. side matches whichever real strafe key is actually
        //   pressed. Reporting keys=forward+that-side at
        //       targetYaw = realYaw + moveAngle + (side < 0 ? +45 : -45)
        //   reproduces the exact real-world displacement of the real key combo. Sanity check: for
        //   real W+A/W+D (moveAngle = -45/+45), this collapses to targetYaw == realYaw -- i.e. the
        //   formula naturally reduces to "no fake needed", exactly matching the old exemption, which
        //   is why it was safe to fold that case into this same formula instead of skipping it.
        // Confirmed numerically for S+A (moveAngle=-135, side=-1 -> +45 -> targetYaw=realYaw-90):
        // fake world vector == real world vector exactly (not just proportional). S+D mirrors it by
        // left/right symmetry (moveAngle=+135, side=+1 -> -45 -> targetYaw=realYaw+90).
        float moveAngle = (float) Math.toDegrees(Math.atan2(inputX, inputZ));
        float targetYaw;
        int strafe = 0;

        if (inputX != 0 && inputZ != 0) {
            strafe = inputX < 0 ? 1 : -1; // +1 = report left (A down), -1 = report right (D down)
            targetYaw = Mth.wrapDegrees(mc.player.getYRot() + moveAngle + (strafe > 0 ? 45.0f : -45.0f));
        } else {
            targetYaw = Mth.wrapDegrees(mc.player.getYRot() + moveAngle);
        }

        // Real pitch: pitch plays no part in horizontal movement, and reporting it unchanged keeps
        // the rotation packet's pitch honest.
        pendingYaw = targetYaw;
        pendingPitch = mc.player.getXRot();
        grimStrafe = strafe;
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
        // simply stops being called), so without it a disconnect could leave all three consumers
        // thinking the fake is live.
        if (mc.player == null || !isToggled() || !isGrim() || !grimQueued) return false;
        return EUClient.ROTATION_MANAGER.getRotation() != null && EUClient.ROTATION_MANAGER.getRotationOwner() == this;
    }

    /** The faked yaw currently on the wire. Only meaningful while isGrimCompensating(). */
    public float getGrimYaw() {
        return EUClient.ROTATION_MANAGER.getRotation().getYaw();
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
        // the Rage/Instant branch bypasses entirely by design (that's the whole
        // point of those modes). But bypassing the DIRECTION/wall gating vanilla does isn't the
        // same as bypassing THESE -- blindness, fall-flying, and lava are each their own GrimAC
        // check (SprintD/SprintF/an equivalent lava check), and low hunger is SprintA
        // ("Sprinting with too low hunger") -- none of those are about movement legitimacy, they're
        // just "is sprinting even POSSIBLE right now", true regardless of which mode is forcing the
        // flag. Missing here meant Rage/Instant could force-sprint through all four
        // states unconditionally, each one individually flaggable server-side.
        if (mc.player.isInLava()) return false;
        if (mc.player.onClimbable()) return false;
        if (mc.player.hasEffect(MobEffects.BLINDNESS)) return false;
        if (mc.player.isFallFlying()) return false;
        if (mc.player.getFoodData().getFoodLevel() <= 6) return false;

        // Was Legit-only (see below) -- Rage/Instant/Grim force-sprinting straight through eating
        // regardless of NoSlow's own "should items still slow me down" setting meant this force-
        // sprint fought vanilla's real per-tick isUsingItem-driven speed/state correction the whole
        // time an item was in use, instead of respecting the same NoSlow toggle every other mode
        // does. Reported as "ăn đồ ăn bị giật giật" (jitter while eating) -- move it up here so it's
        // actually unconditional, matching what the comment below already claimed.
        if (mc.player.isUsingItem() && (!EUClient.MODULE_MANAGER.getModule(NoSlowModule.class).isToggled() || !EUClient.MODULE_MANAGER.getModule(NoSlowModule.class).items.getValue()))
            return false;

        if (mode.getValue().equalsIgnoreCase("Rage") || mode.getValue().equalsIgnoreCase("Instant") || isGrim()) {
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
            // Blindness/fall-flying/lava/ladder/hunger/using-item are already checked above,
            // unconditionally for every mode -- not duplicated here.
            if (!((ClientPlayerEntityAccessor) mc.player).invokeIsWalking()) return false;
            if (mc.player.horizontalCollision && !mc.player.minorHorizontalCollision) return false;
            return mc.player.input.hasForwardImpulse();
        }
    }
}
