package eu.client.managers;

import lombok.Getter;
import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.*;
import eu.client.modules.Module;
import eu.client.utils.IMinecraft;
import eu.client.utils.animations.Easing;
import eu.client.utils.rotations.Rotation;
import eu.client.utils.system.MathUtils;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;

// Ported from Shoreline's rotation system (net.shoreline.client.impl.rotation.RotationManager +
// ClientRotationEvent), replacing the old time-decaying PriorityBlockingQueue<Rotation>. That queue
// held a Rotation until either a new one displaced it (same module) or 100ms of wall-clock elapsed --
// which meant "how long does a fake rotation visibly hold" depended on the queue's timeout matching
// how often the owning module happened to call rotate(), not on whether the module still wanted it.
// Two failure modes fell out of that in this exact session: dropping the timeout to 0ms (to fix
// Sprint Grim serving a one-tick-stale fake) broke every OTHER caller that doesn't queue every single
// tick -- AutoCrystal only calls rotate() on the specific tick it places/attacks, not continuously, so
// 0ms let the hold vanish between calls ("places one crystal, camera snaps back"); reverting to 100ms
// fixed that back but reintroduced Sprint's staleness for up to 2 ticks.
//
// Shoreline's model has no timeout at all: every tick, RotationManager asks (via ClientRotationEvent)
// "does anyone want to fake this tick's rotation", and whichever module currently has a live target
// answers by canceling the event with its own fresh yaw/pitch, recomputed from ITS OWN persistent
// per-tick target state (e.g. AutoCrystal's attackTarget/placeTarget, already recomputed every tick in
// its own onPlayerUpdate) -- not from whatever the last rotate() call happened to be. No target this
// tick -> nobody cancels -> the fake clears on the VERY NEXT tick, not up to 100ms later. Arbitration
// between competing modules (KillAura vs AutoCrystal, etc.) is now @SubscribeEvent priority order
// (RotationPriorities, highest runs first per EventHandler.insert()) instead of a Rotation's stored
// priority field -- the first subscriber to cancel wins, everyone after it must check
// event.isCancelled() and back off (mirrors Shoreline's own AuraModule/AutoCrystalModule doing the
// same isCanceled() short-circuit).
public class RotationManager implements IMinecraft {
    @Getter private Rotation rotation = null;
    // Our own addition on top of Shoreline's model -- see ClientRotationEvent's owner field doc.
    @Getter private Module rotationOwner = null;

    private float prevYaw;
    private float prevPitch;

    @Getter private float serverYaw;
    @Getter private float serverPitch;

    private float prevRenderYaw, prevRenderPitch;
    private long lastRenderTime = 0L;

    public RotationManager() {
        EUClient.EVENT_HANDLER.subscribe(this);
    }

    // Priority MIN_VALUE = runs LAST among PlayerUpdateEvent listeners (EventHandler.insert() sorts
    // highest-priority-first) -- every module that drives a rotation off this same event (AutoCrystal
    // recomputing attackTarget/placeTarget, Sprint's grimUpdate, etc.) needs its per-tick target state
    // already fresh before we ask who wants to fake this tick, same ordering guarantee the old queue
    // relied on.
    @SubscribeEvent(priority = Integer.MIN_VALUE)
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (mc.player == null) return;

        Rotation snapshot = new Rotation(mc.player);
        ClientRotationEvent rotationEvent = new ClientRotationEvent(snapshot);
        EUClient.EVENT_HANDLER.post(rotationEvent);

        if (rotationEvent.isCancelled()) {
            rotation = snapshot;
            rotationOwner = rotationEvent.getOwner();
            lastRenderTime = System.currentTimeMillis();
        } else {
            rotation = null;
            rotationOwner = null;
        }
    }

    @SubscribeEvent(priority = Integer.MAX_VALUE)
    public void onUpdateMovement(UpdateMovementEvent event) {
        if (rotation == null) return;

        prevYaw = mc.player.getYRot();
        prevPitch = mc.player.getXRot();

        mc.player.setYRot(rotation.getYaw());
        mc.player.setXRot(rotation.getPitch());
    }

    @SubscribeEvent(priority = Integer.MIN_VALUE)
    public void onUpdateMovement$POST(UpdateMovementEvent.Post event) {
        if (rotation == null) return;

        mc.player.setYRot(prevYaw);
        mc.player.setXRot(prevPitch);
    }

    // ---- legacyRotate: bản gốc 1.21.4's "Normal" rotate, ported verbatim -----------------------
    //
    // The OLD PriorityBlockingQueue<LegacyRotation> mechanism this project's ClientRotationEvent
    // rewrite deleted outright (see this class's own top-of-file doc) -- re-added here, unchanged,
    // specifically so SpeedMine/AutoCrystal's "Normal" mode is byte-for-byte what bản gốc did:
    // no computeMoveFix/octant-remap entanglement (that whole system didn't exist in bản gốc at
    // all -- it's this project's own addition to fix a GrimAC issue the CURRENT `rotation`/
    // ClientRotationEvent model introduces, and "Normal" was never meant to go through that
    // model), no onUpdateMovement$POST revert (bản gốc genuinely never had one -- see this
    // project's own RotationManager.java history/discussion), same queue expiry (100ms) and same
    // proxy skip in onUpdateMovement. Verbatim, not a reinterpretation.
    private final java.util.concurrent.PriorityBlockingQueue<eu.client.utils.rotations.LegacyRotation> legacyQueue =
            new java.util.concurrent.PriorityBlockingQueue<>(11, this::compareLegacyRotations);
    @Getter private eu.client.utils.rotations.LegacyRotation legacyRotation = null;

    private static final java.util.HashMap<String, Integer> LEGACY_PRIORITIES = new java.util.HashMap<>();
    static {
        LEGACY_PRIORITIES.put("KillAura", 1);
        LEGACY_PRIORITIES.put("AutoCrystal", 2);
        LEGACY_PRIORITIES.put("SpeedMine", 3);
        LEGACY_PRIORITIES.put("SelfFill", 4);
    }

    public int getLegacyModulePriority(Module module) {
        return LEGACY_PRIORITIES.getOrDefault(module.getName(), 0);
    }

    private int compareLegacyRotations(eu.client.utils.rotations.LegacyRotation target, eu.client.utils.rotations.LegacyRotation rotation) {
        if (target.getPriority() == rotation.getPriority()) return -Long.compare(target.getTime(), rotation.getTime());
        return -Integer.compare(target.getPriority(), rotation.getPriority());
    }

    @SubscribeEvent(priority = Integer.MIN_VALUE)
    public void onPlayerUpdate$Legacy(PlayerUpdateEvent event) {
        legacyQueue.removeIf(r -> System.currentTimeMillis() - r.getTime() > 100);
        legacyRotation = legacyQueue.peek();
    }

    private float prevLegacyYaw, prevLegacyPitch;
    private boolean legacySwapped;

    @SubscribeEvent(priority = Integer.MAX_VALUE)
    public void onUpdateMovement$Legacy(UpdateMovementEvent event) {
        legacySwapped = false;
        if (legacyRotation == null) return;
        // On the proxy, don't modify mc.player's yaw/pitch -- the client sends its own movement
        // packets and we don't want the proxy's player entity to visibly rotate. Verbatim from
        // bản gốc's own onUpdateMovement.
        if (eu.client.pingbypass.PingBypassFlags.proxyForwardingActive
                && EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer()) {
            return;
        }

        // This and the `rotation`-based onUpdateMovement above share the same MAX_VALUE priority
        // (tie-broken by subscribe order, unspecified) -- whichever runs second overwrites the
        // other's yaw right before sendPosition() reads it. Sprint's Grim mode depends on the
        // reported yaw being EXACTLY what it computed (its own class doc: "reported yaw and
        // physics yaw are the SAME value... never a private copy"), for the diagonal-speed
        // formula (20.62km/h) specifically -- legacyRotate clobbering it mid-tick broke that
        // silently whenever an aim module's Rotate=Normal was also active the same tick.
        // computeMoveFix already exempts Grim's own ticks from its own remap for the identical
        // reason; do the same here rather than letting two independent yaw fakes race.
        eu.client.modules.impl.movement.SprintModule sprint = EUClient.MODULE_MANAGER == null ? null
                : EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.movement.SprintModule.class);
        if (sprint != null && sprint.isGrimCompensating()) return;

        prevLegacyYaw = mc.player.getYRot();
        prevLegacyPitch = mc.player.getXRot();

        mc.player.setYRot(legacyRotation.getYaw());
        mc.player.setXRot(legacyRotation.getPitch());
        legacySwapped = true;
    }

    // bản gốc genuinely never had this revert (verified from its own source -- onUpdateMovement
    // sets and nothing ever sets it back), and it visibly locks the camera without one (Entity.turn()
    // is incremental, getYRot() + mouseDelta -- a fake with no revert bakes in as permanent drift
    // instead of "fading" on its own). Added on top of the verbatim port rather than left as-is --
    // this only touches mc.player's yaw/pitch for the same tick-scoped window
    // onUpdateMovement$Legacy already opened, entirely separate from `rotation`/ClientRotationEvent,
    // so it still can't trigger computeMoveFix's octant-remap (that's what "Normal" was ported to
    // specifically avoid).
    @SubscribeEvent(priority = Integer.MIN_VALUE)
    public void onUpdateMovement$LegacyPost(UpdateMovementEvent.Post event) {
        if (!legacySwapped) return;
        legacySwapped = false;

        mc.player.setYRot(prevLegacyYaw);
        mc.player.setXRot(prevLegacyPitch);
    }

    public void legacyRotate(float[] rotations, int priority) {
        legacyRotate(rotations[0], rotations[1], priority);
    }

    public void legacyRotate(float yaw, float pitch, int priority) {
        legacyQueue.removeIf(r -> r.getModule() == null && r.getPriority() == priority);
        legacyQueue.add(new eu.client.utils.rotations.LegacyRotation(yaw, pitch, priority));
    }

    // ---- MovementFix: octant remap, always on for a FOREIGN (aim-module) rotation ------------
    //
    // Scope, and nothing outside it: the ticks where somebody OTHER than Sprint's Grim mode owns
    // this tick's rotation (AutoCrystal/SpeedMine/KillAura at Rotate=Normal). Sprint Grim's own
    // ticks are lossless by construction (it PICKS the reported yaw from the real input, see
    // SprintModule's class doc) and are excluded below by the isGrimCompensating() check; ticks with
    // no rotation at all return null immediately. Both cases are bit-for-bit untouched.
    //
    // Why it is needed at all, on the ticks it does run. Local physics runs at the REAL yaw
    // (Entity.getInputVector, .mcref net/minecraft/world/entity/Entity.java:1660) while the movement
    // packet carries the SPOOFED yaw (onUpdateMovement below). GrimAC predicts from
    // (reported yaw, reported knownInput bits), so it simulates spoofedYaw + angle(realKeys) while we
    // actually move at realYaw + angle(realKeys): a full (realYaw - spoofedYaw) of prediction error,
    // i.e. a setback every single tick you move while an aim module is holding a rotation. That is
    // the "Sprint Grim bị lệch khi mặt hướng về module đang Rotate" report.
    //
    // What it does. Physics is swapped onto the spoofed yaw (LivingEntityMixin's travel() /
    // jumpFromGround() swaps, gated on isMoveFixActive()) and the input is re-expressed at that yaw:
    //   reported octant = nearest multiple of 45 deg to (realInputAngle + realYaw - spoofedYaw).
    // Prediction is then EXACT (we move precisely the discrete input Grim simulates) and the player's
    // actual direction is off by at most 22.5 deg -- the proven floor for a dictated reported yaw
    // (see the algebra block below). Minimised, not eliminated; that is the accepted trade.
    //
    // The three bugs the previous (deleted) implementation had, all of which made it WORSE than that
    // 22.5 floor and all of which are fixed here:
    //  1. It only rewrote ClientInput.moveVector, never keyPresses. On 1.21.2+ GrimAC reads the
    //     DISCRETE key bits of ServerboundPlayerInputPacket (knownInput), not our velocity -- so the
    //     server kept predicting the REAL keys at the SPOOFED yaw while the remap quietly bent the
    //     player's actual path. It fixed nothing server-side and introduced the drift for free.
    //     Concrete: realYaw 0, spoofed 100, pure W. Remap -> pure-left input, we move at world yaw
    //     10 (10 deg off, fine); Grim still sees "forward" at yaw 100 and predicts world yaw 100.
    //     90 deg of prediction error, every tick. Both fields are written together now, exactly the
    //     way Sprint Grim's block in KeyboardInputMixin already does it.
    //  2. It snapped with Math.round() on each rotated component instead of snapping the ANGLE. The
    //     component-0.5 boundary is 30 deg, not 22.5, and Math.round(-0.5) == 0 makes it asymmetric:
    //     realYaw 0, spoofed 25, pure W -> rotated (0.423, 0.906) -> rounds to pure forward -> 25 deg
    //     of drift, past the floor. Snapping sector = round(angle/45) puts every boundary at exactly
    //     22.5 deg.
    //  3. Math.round() also destroyed the magnitude: a snapped diagonal came out (1, 1), length
    //     sqrt(2) -- 41% overspeed, a setback all on its own. OCTANTS holds unit vectors.
    //
    // The one case that cannot be made exact, so it is skipped outright rather than faked badly:
    // GrimAC's loopVectors pins forward to +1 (and ignores backward) whenever isSprinting, so a
    // reported octant with no forward component is unpredictable-by-construction for a sprinting
    // player -- see SprintModule's class doc, point 2. Those ticks fall back to doing nothing at all
    // (status quo), rather than trading a fixed prediction for a broken one.
    //
    // {right, forward} unit vectors for sector*45 degrees, sector 0 = forward, increasing clockwise
    // (i.e. toward the player's right), matching Mth-free exact values so a pure axis stays a pure
    // axis and a diagonal stays unit length.
    private static final float[][] OCTANTS = {
            {0.0f, 1.0f}, {0.70710678f, 0.70710678f}, {1.0f, 0.0f}, {0.70710678f, -0.70710678f},
            {0.0f, -1.0f}, {-0.70710678f, -0.70710678f}, {-1.0f, 0.0f}, {-0.70710678f, 0.70710678f}
    };

    /** True iff computeMoveFix() remapped the input THIS tick. Read by LivingEntityMixin to decide
     *  whether local physics must run at the spoofed yaw. Never true on a Sprint Grim tick. */
    @Getter private boolean moveFixActive;
    /** The spoofed yaw the remap was computed against; only meaningful while moveFixActive. */
    @Getter private float moveFixYaw;

    /**
     * Called once per tick from the tail of KeyboardInput.tick(), which is the single place where
     * both the reported keys and the local movement input are written (see KeyboardInputMixin).
     *
     * @param inputRight   real input, right-positive (i.e. -moveVector.x)
     * @param inputForward real input, forward-positive (i.e. moveVector.y)
     * @return the {right, forward} unit vector to report and move by, or null to leave input alone.
     */
    public float[] computeMoveFix(float inputRight, float inputForward) {
        moveFixActive = false;
        if (rotation == null || mc.player == null || mc.player.isPassenger() || mc.player.isFallFlying()) return null;

        // Sprint Grim owns this tick -> it already reports a yaw derived from the real input, exactly,
        // and writes its own keys/moveVector right after us. Never double-compensate.
        eu.client.modules.impl.movement.SprintModule sprint = EUClient.MODULE_MANAGER == null ? null
                : EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.movement.SprintModule.class);
        if (sprint != null && sprint.isGrimCompensating()) return null;

        int right = (int) Math.signum(inputRight), forward = (int) Math.signum(inputForward);
        if (right == 0 && forward == 0) return null;

        float spoofYaw = rotation.getYaw();
        float target = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(right, forward))
                + mc.player.getYRot() - spoofYaw);
        float[] octant = OCTANTS[(Math.round(target / 45.0f) % 8 + 8) % 8];

        // No forward component while sprinting -> Grim would predict forward anyway. Bail.
        if (octant[1] <= 0.0f && mc.player.isSprinting()) return null;

        moveFixYaw = spoofYaw;
        moveFixActive = true;
        return octant;
    }

    // ---- Why the movement fix cannot be BETTER than +-22.5 deg (2026-08-12) ------------------
    //
    // The floor is real and is NOT the implementation bug list above -- both exist, and computeMoveFix
    // now hits the floor instead of blowing past it. The algebra, verified against this version's
    // actual sources rather than assumed:
    //
    //  * Local movement direction. Entity.moveRelative -> Entity.getInputVector (.mcref
    //    net/minecraft/world/entity/Entity.java) rotates the (strafe, forward) input vector by
    //    THIS ENTITY'S getYRot(). So world direction = realYaw + angle(realInput). Nothing else
    //    feeds into it.
    //  * Server-side prediction. GrimAC's PredictionEngine calls
    //    inputTransformer.getMovementResultFromInput(player, input, speed, player.yaw)
    //    (PredictionEngine.java:779) where `input` is built from the DISCRETE knownInput key bits of
    //    ServerboundPlayerInputPacket. So predicted direction = reportedYaw + k*45 for integer k.
    //
    //  Zero rubberband therefore requires realYaw + angle(realInput) == reportedYaw + k*45, i.e.
    //  (realYaw - reportedYaw) must itself be a multiple of 45. When we get to CHOOSE the reported
    //  yaw that is easy, and SprintModule's Grim mode does exactly that (see its class doc). When an
    //  aim module dictates the reported yaw -- AutoCrystal/SpeedMine/KillAura Rotate=Normal -- the
    //  delta is arbitrary, and the two conditions are simply incompatible. You get to pick one:
    //    - octant snap: prediction matches exactly, and the player physically walks up to 22.5
    //      degrees off from where they pressed (reported as "pure W drifts toward the mining
    //      target"; with a delta under 22.5 degrees it snaps to sector 0 and W walks along the
    //      SPOOFED yaw outright);
    //    - anything that preserves the player's intended direction: it is algebraically identical
    //      to doing nothing at all. Swapping physics to the spoofed yaw and pre-rotating the input
    //      by (realYaw - spoofedYaw) produces getInputVector(rot(input), speed, spoofedYaw) ==
    //      getInputVector(input, speed, realYaw) -- the same world velocity, the same reported keys,
    //      the same reported yaw as the untouched client. A no-op with extra steps.
    //
    //  So it is a straight choice between "exact direction, guaranteed setback" and "exact
    //  prediction, up to 22.5 deg of direction error", and the second one is what the user asked for
    //  ("giảm thiểu", minimise -- not eliminate). computeMoveFix above takes it, unconditionally,
    //  with no toggle to remember.
    //
    //  2026-08-12, second pass -- Shoreline reference, for context on why it ships the other choice.
    //  Shoreline (C:/Users/conng/Downloads/shoreline-main) has the same MovementFix idea
    //  (RotationManager.onKeyboardTick/onUpdateVelocity/onPlayerJump, lines 149-199) -- including bugs
    //  1-3 above -- and ships it DEFAULT OFF (RotationsModule.java:34,
    //  `new BooleanConfig("MovementFix", ..., false)`).
    //  With it off, Shoreline's rotation spoof is nothing but a yaw/pitch substitution inside
    //  ClientPlayerEntity.sendMovementPackets (MovementPacketsEvent, MixinClientPlayerEntity.java:110-190):
    //  the packet carries the fake yaw, mc.player.getYRot() is never touched, so local physics runs on
    //  the real yaw and WASD is bit-for-bit unaffected. Structurally identical to what onUpdateMovement /
    //  onUpdateMovement$POST below already do here -- that window opens AFTER AbstractClientPlayer.tick()
    //  (i.e. after aiStep/travel) and closes before ambientSoundHandlers, enclosing only sendPosition.
    //  The visible "head spins/snaps back and forth" the user reports from Shoreline is the direct,
    //  expected consequence: Shoreline consumes+removes its rotation request each tick
    //  (onMovementPackets -> removeRotation), so the reported yaw alternates fake/real tick by tick,
    //  and the third-person model is drawn from the SERVER yaw (onRenderPlayer, line 202) so you SEE it.
    //  getRenderRotations() below does the same thing here. Cosmetic, not a bug.
    //
    //  Rotate=Packet (packetRotate below) remains available and is still the right default for
    //  SpeedMine's long continuous holds -- GrimAC only runs movement prediction on packets carrying a
    //  position (CheckManagerListener.handleFlying: onPositionUpdate is inside `if (hasPosition)`), so a
    //  rotation-only spoof costs no prediction accuracy. But it is NOT a prerequisite for undrifted
    //  movement, and switching AutoCrystal onto it was an overreach: Normal already leaves movement
    //  alone now that MovementFix is gone.

    @SubscribeEvent
    public void onPacketSend(PacketSendEvent event) {
        if (mc.player == null) return;

        if (event.getPacket() instanceof ServerboundMovePlayerPacket packet) {
            if (!packet.hasRotation()) return;

            serverYaw = packet.getYRot(mc.player.getYRot());
            serverPitch = packet.getXRot(mc.player.getXRot());
        }
    }

    public void packetRotate(float[] rotations) {
        packetRotate(rotations[0], rotations[1]);
    }

    // Set whenever packetRotate actually sends a lie, read by ClientPlayNetworkHandlerMixin to
    // decide whether an incoming server position/rotation sync packet needs its rotation fields
    // thrown away. packetRotate has no "restore to real" follow-up of its own (Surround/SelfTrap
    // fire-and-forget it once per target, not every tick like the ClientRotationEvent-driven modules
    // do) -- if the real yaw genuinely isn't changing tick to tick (player standing still, precisely
    // aiming), sendPosition's own delta==0 dedup means nothing ever re-reports the real yaw to
    // correct the server's belief back. The server then keeps believing whatever was last faked
    // until something else prompts a sync -- and ClientboundPlayerPositionPacket (sent by vanilla
    // servers after knockback/damage among other things) echoes the player's OWN last-known
    // rotation back as part of that sync, which vanilla's handler applies unconditionally to the
    // real mc.player.setYRot()/setXRot() -- silently snapping the actual camera to whatever was
    // last faked. Reported as "euclient forces me to look at my own SelfTrap/Surround target,"
    // specifically only when standing still contesting a cell AND taking damage (exactly the two
    // conditions above). 500ms mirrors homovore's own isSilentActive() window for the equivalent
    // protection (MixinClientPlayNetworkHandler.onHandleMovePlayerPost).
    private long lastPacketRotateTime = 0L;

    public boolean isPacketRotateActive() {
        return System.currentTimeMillis() - lastPacketRotateTime < 500L;
    }

    public void packetRotate(float yaw, float pitch) {
        if (serverYaw == yaw && serverPitch == pitch) return;
        lastPacketRotateTime = System.currentTimeMillis();
        // Same call on both sides -- mc.getConnection() on the proxy IS the real server
        // connection (see ProxyServer.setServerConnection callers), so this needs no
        // proxy-specific branch. ProxyServerTickListener now blacklists ServerboundMovePlayerPacket
        // by default (matches earthhack's Pb2SManager, see PbPlayHandler.handleMovePlayer0) -- this
        // send needs the same explicit authorization, or the proxy silently drops every
        // packet-rotate from AutoCrystal/other proxy-side aim modules. allowSend() is a no-op on
        // the client (that listener is only ever subscribed on the proxy), so this is safe
        // unconditionally.
        //
        // 2026-08-13: was ServerboundMovePlayerPacket.PosRot (position + rotation). GrimAC's
        // handleFlying() (CheckManagerListener.java) runs a FULL onPositionUpdate() physics
        // prediction cycle for ANY packet with hasPosition() == true -- it has no way to tell "the
        // real per-tick movement packet" apart from an extra one we bolt on for a rotation-only
        // fake. Every packetRotate() call during AutoCrystal combat therefore fed GrimAC an extra,
        // spurious "movement tick" on top of vanilla's own real one for that same client tick --
        // GrimAC ends up simulating more physics ticks than the client actually ran, permanently
        // desyncing its prediction from ours -- constant mispredicted setbacks, i.e. "bật rotate
        // Packet thì rubberband, không di chuyển được". ServerboundMovePlayerPacket.Rot carries no
        // position at all (hasPosition() == false), so GrimAC only runs onRotationUpdate() for it,
        // never onPositionUpdate() -- exactly what a rotation-only spoof should touch, and the same
        // variant SpeedMineModule/PhaseModule already use for their own rotation-only packets.
        eu.client.pingbypass.server.ProxyServerTickListener.allowSend(() ->
                mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                        yaw, pitch, EUClient.POSITION_MANAGER.isServerOnGround(), mc.player.horizontalCollision)));
    }

    public boolean inRenderTime() {
        return System.currentTimeMillis() - lastRenderTime < 1000;
    }

    public float[] getRenderRotations() {
        float from = MathUtils.wrapAngle(prevRenderYaw), to = MathUtils.wrapAngle(rotation == null ? mc.player.getYRot() : getServerYaw());
        float delta = to - from;
        if(delta > 180) delta -= 380;
        else if(delta < -180) delta += 360;

        // Was 1000ms -- a full second to visually catch up to a silent rotation, way slower than
        // vanilla's own body/head turn (which settles within a tick or two, ~50-100ms at 20 TPS).
        // Purely cosmetic (own player's THIRD-PERSON MODEL only, see LivingEntityRendererMixin --
        // never touches the real getYRot()/packets/game logic), but 1000ms made silent rotation
        // visibly lag behind the actual (instant) aim for a full second, reading as "the model is
        // still turning" long after the shot/attack already landed.
        float yaw = Mth.lerp(Easing.toDelta(lastRenderTime, 100), from, from + delta);
        float pitch = Mth.lerp(Easing.toDelta(lastRenderTime, 100), prevRenderPitch, rotation == null ? mc.player.getXRot() : getServerPitch());
        prevRenderYaw = yaw;
        prevRenderPitch = pitch;

        return new float[]{yaw, pitch};
    }
}
