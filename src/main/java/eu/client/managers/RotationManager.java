package eu.client.managers;

import lombok.Getter;
import lombok.Setter;
import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.*;
import eu.client.mixins.accessors.EntityAccessor;
import eu.client.modules.Module;
import eu.client.modules.impl.core.RotationsModule;
import eu.client.utils.IMinecraft;
import eu.client.utils.animations.Easing;
import eu.client.utils.rotations.Rotation;
import eu.client.utils.system.MathUtils;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;

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

    // Raw (pre-jitter) target of the last packetRotate/silentRotate call -- dedup compares against
    // THIS, not serverYaw/serverPitch (which also gets overwritten by real incoming server sync
    // packets, :301-302, and would make the dedup's meaning depend on unrelated server traffic).
    // Comparing raw target means "don't resend, jitter is noise, not the actual target" without
    // that noise defeating the check -- previously applyJitter ran BEFORE the dedup, so two
    // consecutive calls at the exact same target almost never compared equal and the guard fired
    // basically never. This ordering isn't a nami port (nami's performSilent has no dedup at all,
    // see RotationManager class doc/history) -- purely our own call.
    private float lastPacketTargetYaw = Float.NaN, lastPacketTargetPitch = Float.NaN;
    private float lastSilentTargetYaw = Float.NaN, lastSilentTargetPitch = Float.NaN;

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

        // 2026-08-14: ONE rotation, one yaw swap per tick -- exactly like bản gốc 1.21.4, which had a
        // single PriorityBlockingQueue<Rotation> and a single onUpdateMovement/POST pair. The port had
        // grown a SECOND, independent swap (the legacyQueue's own onUpdateMovement$Legacy at the same
        // MAX_VALUE priority) racing this one for mc.player's yaw field with undefined ordering --
        // that alone is a per-tick coin flip between two different reported yaws, i.e. stutter. Fixed
        // by folding both sources into one swap (below).
        //
        // 2026-08-14, third pass: legacyQueue IS bản gốc's own rotate()/PriorityBlockingQueue
        // mechanism, ported verbatim (LegacyRotation == bản gốc's Rotation.java field-for-field, see
        // its own doc) -- "Rotate=Normal" on every module (KillAura/AutoCrystal/SpeedMine) goes
        // through it. ClientRotationEvent/MovementSync is this project's own addition on top, with
        // no equivalent in bản gốc at all (its callers -- Sprint Grim, AutoCrystal MovementSync --
        // never touched RotationManager in bản gốc's own source, confirmed against the Desktop
        // copy).
        //
        // 2026-08-14, fourth pass: "legacyQueue always wins outright" was too blunt -- Sprint's
        // Grim mode needs to win over AutoCrystal/SpeedMine's Rotate=Normal specifically (its
        // reported yaw has to be EXACTLY what it computed for GrimAC's diagonal-speed prediction,
        // see SprintModule's own class doc; losing that tick's rotation to a lower-stakes aim
        // module is a guaranteed setback, not just a cosmetic loss). Back to a real priority
        // comparison, but keyed off the SAME table legacyQueue itself already uses -- Sprint is
        // just another priority in it now (4, between SpeedMine=3 and SelfFill=5), not a special
        // case. Only SprintModule ever calls ClientRotationEvent.setOwner() (see that class's own
        // doc), so every OTHER ClientRotationEvent caller (KillAura/AutoCrystal-MovementSync/
        // AutoWeb/SelfFill/...) still defaults to priority 0 and keeps losing to legacyQueue exactly
        // as before -- this only changes Sprint's own standing.
        legacyQueue.removeIf(r -> System.currentTimeMillis() - r.getTime() > 100);
        eu.client.utils.rotations.LegacyRotation legacy = legacyQueue.peek();

        Rotation snapshot = new Rotation(mc.player);
        ClientRotationEvent rotationEvent = new ClientRotationEvent(snapshot);
        EUClient.EVENT_HANDLER.post(rotationEvent);

        int eventPriority = rotationEvent.isCancelled() && rotationEvent.getOwner() != null
                ? getLegacyModulePriority(rotationEvent.getOwner()) : rotationEvent.isCancelled() ? 0 : -1;

        if (legacy != null && legacy.getPriority() > eventPriority) {
            rotation = new Rotation(legacy.getYaw(), legacy.getPitch());
            rotationOwner = legacy.getModule();
            lastRenderTime = System.currentTimeMillis();
        } else if (rotationEvent.isCancelled()) {
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
        // On the proxy, don't modify mc.player's yaw/pitch -- the client sends its own movement
        // packets and we don't want the proxy's player entity to visibly rotate. Packet rotations are
        // sent directly to the server. Verbatim from bản gốc's own onUpdateMovement.
        if (eu.client.pingbypass.PingBypassFlags.proxyForwardingActive
                && EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer()) {
            return;
        }

        prevYaw = mc.player.getYRot();
        prevPitch = mc.player.getXRot();

        mc.player.setYRot(rotation.getYaw());
        mc.player.setXRot(rotation.getPitch());
    }

    @SubscribeEvent(priority = Integer.MIN_VALUE)
    public void onUpdateMovement$POST(UpdateMovementEvent.Post event) {
        if (rotation == null) return;
        if (eu.client.pingbypass.PingBypassFlags.proxyForwardingActive
                && EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer()) {
            return;
        }

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
    private static final java.util.HashMap<String, Integer> LEGACY_PRIORITIES = new java.util.HashMap<>();
    static {
        LEGACY_PRIORITIES.put("KillAura", 1);
        LEGACY_PRIORITIES.put("AutoCrystal", 2);
        LEGACY_PRIORITIES.put("SpeedMine", 3);
        // Sprint (Grim mode, via ClientRotationEvent -- not a legacyQueue caller itself) ranked
        // above AutoCrystal/SpeedMine's Normal on request: its reported yaw has to be exactly what
        // grimUpdate() computed for GrimAC's prediction to stay exact, so losing arbitration to a
        // lower-stakes aim module is a guaranteed setback. See onPlayerUpdate's own note.
        LEGACY_PRIORITIES.put("Sprint", 4);
        LEGACY_PRIORITIES.put("SelfFill", 5);
    }

    public int getLegacyModulePriority(Module module) {
        return LEGACY_PRIORITIES.getOrDefault(module.getName(), 0);
    }

    private int compareLegacyRotations(eu.client.utils.rotations.LegacyRotation target, eu.client.utils.rotations.LegacyRotation rotation) {
        if (target.getPriority() == rotation.getPriority()) return -Long.compare(target.getTime(), rotation.getTime());
        return -Integer.compare(target.getPriority(), rotation.getPriority());
    }


    // 2026-08-15: gate for Rotate=Normal callers (AutoCrystal/Surround/SpeedMine) only.
    // Packet/Silent already send synchronously right before their own action packet -- same
    // shape as Nami's SILENT (RotationRequestHandler.performSilent), no race possible. Normal
    // queues into legacyQueue and is resolved on the NEXT PlayerUpdateEvent tick, which a
    // higher-priority ClientRotationEvent caller (Sprint=Grim) can win outright, leaving that
    // tick's actual sendPosition() reporting a DIFFERENT rotation than the one this legacyRotate
    // call asked for -- exactly the Sprint-Grim + AutoCrystal flag report. Nami has no equivalent
    // (their Sprint never touches rotation at all, confirmed against SprintFeature.java), so this
    // gate is this project's own addition, not a port -- loosely modeled on Nami's placeBlock
    // canPlace check (verify the target is actually where you're looking before firing), applied
    // here to both place AND attack rather than just place. Callers still call legacyRotate()
    // unconditionally every attempt (keeps the queued aim advancing toward target); this only
    // gates the ACTION packet that follows, skipping (retry next tick) when the wire hasn't
    // caught up yet.
    private static final float NORMAL_ROTATION_THRESHOLD = 8f;

    public boolean isRotationReached(float[] target) {
        float yawDiff = Mth.wrapDegrees(target[0] - serverYaw);
        float pitchDiff = target[1] - serverPitch;
        return Math.abs(yawDiff) <= NORMAL_ROTATION_THRESHOLD && Math.abs(pitchDiff) <= NORMAL_ROTATION_THRESHOLD;
    }

    public void legacyRotate(float[] rotations, int priority) {
        legacyRotate(rotations[0], rotations[1], priority);
    }

    public void legacyRotate(float yaw, float pitch, int priority) {
        legacyQueue.removeIf(r -> r.getModule() == null && r.getPriority() == priority);
        legacyQueue.add(new eu.client.utils.rotations.LegacyRotation(yaw, pitch, priority));
    }

    public void legacyRotate(float[] rotations, Module module, int priority) {
        legacyRotate(rotations[0], rotations[1], module, priority);
    }

    public void legacyRotate(float yaw, float pitch, Module module, int priority) {
        legacyQueue.removeIf(r -> r.getModule() == module);
        legacyQueue.add(new eu.client.utils.rotations.LegacyRotation(yaw, pitch, module, priority));
    }

    // ---- MovementFix: bản gốc 1.21.4's own, restored verbatim 2026-08-14 ----------------------
    //
    // What was deleted on 2026-08-14 was the PORT's own invention: an ALWAYS-ON octant remap
    // (computeMoveFix/OCTANTS) that rewrote ClientInput.moveVector AND keyPresses every tick a
    // rotation was held, swapped local physics onto the spoofed yaw and force-desprinted whenever
    // the snapped octant had no forward component. That was correctly removed -- it is the
    // rubberband/stutter source under Rotate=Normal and bản gốc never had anything like it.
    //
    // But bản gốc DOES have a MovementFix, a completely different and much smaller one, gated
    // behind RotationsModule.movementFix (default OFF), and the port deleted that too. These three
    // handlers are it, ported field-for-field from bản gốc's RotationManager:
    //   * onUpdateVelocity  -- recompute the movement vector at the spoofed yaw instead of the real
    //                          one, via Entity.getInputVector (bản gốc: movementInputToVelocity);
    //   * onKeyboardTick    -- rotate the real (forward, sideways) input by (realYaw - spoofedYaw)
    //                          and Math.round() it, so what is reported still resembles key input;
    //   * onPlayerJump/POST -- swap the yaw across jumpFromGround, which reads getYRot() itself and
    //                          sits outside the UpdateMovementEvent window entirely.
    // Note this touches keyPresses NOWHERE -- bản gốc doesn't, so neither does this. Verbatim, not
    // a reinterpretation: the port already tried "improving" this shape once and that is what the
    // deleted computeMoveFix was.

    private float prevFixYaw;

    @SubscribeEvent
    public void onUpdateVelocity(UpdateVelocityEvent event) {
        if (mc.player == null) return;
        if (!EUClient.MODULE_MANAGER.getModule(RotationsModule.class).movementFix.getValue()) return;
        if (rotation == null) return;

        event.setVelocity(EntityAccessor.invokeMovementInputToVelocity(event.getMovementInput(), event.getSpeed(), rotation.getYaw()));
        event.setCancelled(true);
    }

    @SubscribeEvent
    public void onKeyboardTick(KeyboardTickEvent event) {
        if (mc.player == null || mc.level == null || mc.player.isPassenger()) return;
        if (!EUClient.MODULE_MANAGER.getModule(RotationsModule.class).movementFix.getValue()) return;
        if (rotation == null) return;

        float movementForward = event.getMovementForward();
        float movementSideways = event.getMovementSideways();

        float delta = (mc.player.getYRot() - rotation.getYaw()) * Mth.DEG_TO_RAD;

        float cos = Mth.cos(delta);
        float sin = Mth.sin(delta);

        event.setMovementForward(Math.round(movementForward * cos + movementSideways * sin));
        event.setMovementSideways(Math.round(movementSideways * cos - movementForward * sin));
        event.setCancelled(true);
    }

    @SubscribeEvent
    public void onPlayerJump(PlayerJumpEvent event) {
        if (mc.player == null || mc.level == null || mc.player.isPassenger()) return;
        if (!EUClient.MODULE_MANAGER.getModule(RotationsModule.class).movementFix.getValue()) return;
        if (rotation == null) return;

        prevFixYaw = mc.player.getYRot();
        mc.player.setYRot(rotation.getYaw());
    }

    @SubscribeEvent
    public void onPlayerJump$POST(PlayerJumpEvent.Post event) {
        if (mc.player == null || mc.level == null || mc.player.isPassenger()) return;
        if (!EUClient.MODULE_MANAGER.getModule(RotationsModule.class).movementFix.getValue()) return;
        if (rotation == null) return;

        mc.player.setYRot(prevFixYaw);
    }

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

    // See RotationsModule.jitter's own doc for the reasoning; the offset formulas (Grim/Normal) are
    // ported from Nami's JitterMode inside performSilent(). The ordering here is NOT a nami port,
    // though -- nami's performSilent has no dedup at all, so there's nothing there for jitter to
    // run before or after. Our own dedup (packetRotate/silentRotate's lastXTarget check) runs
    // BEFORE this on the raw pre-jitter target, so jitter can't defeat it by making two calls at
    // the same real target compare unequal.
    private float[] applyJitter(float yaw, float pitch) {
        if (pitch <= -89.5f || pitch >= 89.5f) {
            return new float[]{yaw, pitch};
        }
        String mode = EUClient.MODULE_MANAGER.getModule(RotationsModule.class).jitter.getValue();
        if ("Grim".equalsIgnoreCase(mode)) {
            float f = (float) ((Math.random() * 2.0 - 1.0) * 0.001f);
            pitch = Mth.clamp(pitch + f, -90.0F, 90.0F);
        } else if ("Normal".equalsIgnoreCase(mode)) {
            float minJitter = 1.25f;
            float maxJitter = 2.5f;
            float jitterYaw = minJitter + (float) (Math.random() * (maxJitter - minJitter));
            float jitterPitch = minJitter + (float) (Math.random() * (maxJitter - minJitter));
            jitterYaw *= Math.random() < 0.5 ? -1 : 1;
            jitterPitch *= Math.random() < 0.5 ? -1 : 1;
            yaw += jitterYaw;
            pitch = Mth.clamp(pitch + jitterPitch, -90.0F, 90.0F);
        }
        return new float[]{yaw, pitch};
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

    public void packetRotate(float yawIn, float pitchIn) {
        if (lastPacketTargetYaw == yawIn && lastPacketTargetPitch == pitchIn) return;
        lastPacketTargetYaw = yawIn;
        lastPacketTargetPitch = pitchIn;
        float[] jittered = applyJitter(yawIn, pitchIn);
        final float yaw = jittered[0], pitch = jittered[1];
        lastPacketRotateTime = System.currentTimeMillis();
        serverYaw = yaw;
        serverPitch = pitch;
        
        double x = (eu.client.pingbypass.PingBypassFlags.proxyForwardingActive && EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer())
                ? EUClient.POSITION_MANAGER.getServerX() : mc.player.getX();
        double y = (eu.client.pingbypass.PingBypassFlags.proxyForwardingActive && EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer())
                ? EUClient.POSITION_MANAGER.getServerY() : mc.player.getY();
        double z = (eu.client.pingbypass.PingBypassFlags.proxyForwardingActive && EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer())
                ? EUClient.POSITION_MANAGER.getServerZ() : mc.player.getZ();
        boolean onGround = (eu.client.pingbypass.PingBypassFlags.proxyForwardingActive && EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer())
                ? EUClient.POSITION_MANAGER.isServerOnGround() : mc.player.onGround();

        eu.client.pingbypass.server.ProxyServerTickListener.allowSend(() ->
                mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
                        x, y, z, yaw, pitch, onGround, mc.player.horizontalCollision)));
    }

    public void silentRotate(float[] rotations) {
        silentRotate(rotations[0], rotations[1]);
    }

    // Read by ClientPlayerEntityMixin's sendPosition HEAD/RETURN hooks -- ported verbatim from
    // Nami's RotationStateHandler.silentSyncRequired / MixinLocalPlayer.sendMovementPackets1/2.
    // Set true the instant silentRotate() fires its own immediate packet (below); the VERY NEXT
    // sendPosition() call (same tick -- LocalPlayer.tick() calls it unconditionally every tick)
    // sees this flag, nudges xRotLast/XRot so vanilla's own per-tick packet doesn't independently
    // report a contradicting REAL camera rotation in the same tick as the fake one just sent, then
    // clears it. Nami's own comment on this: "Do not ask me exactly why is it so weird, it just
    // works" -- ported as-is rather than reinterpreted, given this project's last two attempts to
    // improve on the exact wire mechanics here both regressed.
    @Getter @Setter private boolean silentSyncRequired = false;

    // "Silent" rotate mode. Sends ServerboundMovePlayerPacket.PosRot immediately (byte-for-byte
    // Nami's own RotationRequestHandler.performSilent()) and arms silentSyncRequired so the
    // sendPosition hook (see that flag's own doc) can keep vanilla's OWN per-tick packet from
    // contradicting it.
    public void silentRotate(float yawIn, float pitchIn) {
        if (lastSilentTargetYaw == yawIn && lastSilentTargetPitch == pitchIn) return;
        lastSilentTargetYaw = yawIn;
        lastSilentTargetPitch = pitchIn;
        float[] jittered = applyJitter(yawIn, pitchIn);
        final float yaw = jittered[0], pitch = jittered[1];
        lastPacketRotateTime = System.currentTimeMillis();
        silentSyncRequired = true;
        serverYaw = yaw;
        serverPitch = pitch;

        double x = (eu.client.pingbypass.PingBypassFlags.proxyForwardingActive && EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer())
                ? EUClient.POSITION_MANAGER.getServerX() : mc.player.getX();
        double y = (eu.client.pingbypass.PingBypassFlags.proxyForwardingActive && EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer())
                ? EUClient.POSITION_MANAGER.getServerY() : mc.player.getY();
        double z = (eu.client.pingbypass.PingBypassFlags.proxyForwardingActive && EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer())
                ? EUClient.POSITION_MANAGER.getServerZ() : mc.player.getZ();
        boolean onGround = (eu.client.pingbypass.PingBypassFlags.proxyForwardingActive && EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer())
                ? EUClient.POSITION_MANAGER.isServerOnGround() : mc.player.onGround();

        eu.client.pingbypass.server.ProxyServerTickListener.allowSend(() ->
                mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
                        x, y, z, yaw, pitch, onGround, mc.player.horizontalCollision)));
    }

    // Rotate=Packet and Rotate=Silent are both "fire a standalone rotation packet at this target".
    // One dispatcher so every call site just names the mode string instead of re-testing it.
    //
    // CORRECTED 2026-08-20. This used to claim "only the packet shape differs (PosRot vs Rot-only)".
    // That was wrong on both counts and misled a previous investigation:
    //  - BOTH modes send ServerboundMovePlayerPacket.PosRot. Neither sends Rot-only; the Rot
    //    subclass is never constructed anywhere in this class.
    //  - PosRot is CORRECT and must stay. Verified against the real nami-public clone:
    //    RotationRequestHandler.performSilent() sends PosRot too, so silentRotate() already matches
    //    upstream byte-for-byte. Do NOT "optimise" this to Rot-only -- that would be a divergence
    //    from nami, not a fix.
    // The actual difference between the two modes is silentSyncRequired (armed only by
    // silentRotate) and the sendPosition pitch nudge it drives in ClientPlayerEntityMixin.
    //
    // Note on call frequency: nami has NO dedup or coalescing in performSilent() either -- it sends
    // unconditionally per submit(). Upstream keeps it to one packet per tick purely by CALLER
    // DISCIPLINE (submit()'s own doc: "YOU SHOULD NEVER SUBMIT MORE THEN ONE DYNAMIC REQUEST"). So
    // the place to enforce "one rotate per tick" is the calling module, never a coalescer here.
    public void wireRotate(String mode, float[] rotations) {
        if ("Silent".equalsIgnoreCase(mode)) silentRotate(rotations);
        else packetRotate(rotations);
    }

    public boolean inRenderTime() {
        return System.currentTimeMillis() - lastRenderTime < 1000;
    }

    public float[] getRenderRotations() {
        float from = MathUtils.wrapAngle(prevRenderYaw), to = MathUtils.wrapAngle(rotation == null ? mc.player.getYRot() : getServerYaw());
        float delta = to - from;
        if(delta > 180) delta -= 380;
        else if(delta < -180) delta += 360;

        // 1000ms, same as bản gốc -- this is what makes the third-person model SMOOTHLY and
        // CONTINUOUSLY track the target instead of snapping to it and back (100ms was a port-local
        // change). Purely cosmetic: own player's THIRD-PERSON MODEL only, see LivingEntityRendererMixin,
        // never touches the real getYRot()/packets/game logic, which is exactly why the real camera is
        // unaffected by any of this.
        float yaw = Mth.lerp(Easing.toDelta(lastRenderTime, 1000), from, from + delta);
        float pitch = Mth.lerp(Easing.toDelta(lastRenderTime, 1000), prevRenderPitch, rotation == null ? mc.player.getXRot() : getServerPitch());
        prevRenderYaw = yaw;
        prevRenderPitch = pitch;

        return new float[]{yaw, pitch};
    }
}
