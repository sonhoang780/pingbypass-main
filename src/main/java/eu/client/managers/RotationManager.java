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

        // 2026-08-14: ONE rotation, one yaw swap per tick -- exactly like bản gốc 1.21.4, which had a
        // single PriorityBlockingQueue<Rotation> and a single onUpdateMovement/POST pair. The port had
        // grown a SECOND, independent swap (the legacyQueue's own onUpdateMovement$Legacy at the same
        // MAX_VALUE priority) racing this one for mc.player's yaw field with undefined ordering --
        // that alone is a per-tick coin flip between two different reported yaws, i.e. stutter.
        // The legacy queue is now just another SOURCE for the single `rotation` below; ClientRotationEvent
        // (MovementSync / Sprint Grim) still wins when anyone cancels it, otherwise the queue's head
        // (Rotate=Normal) is used, otherwise there is no rotation at all.
        legacyQueue.removeIf(r -> System.currentTimeMillis() - r.getTime() > 100);
        eu.client.utils.rotations.LegacyRotation legacy = legacyQueue.peek();

        Rotation snapshot = new Rotation(mc.player);
        ClientRotationEvent rotationEvent = new ClientRotationEvent(snapshot);
        EUClient.EVENT_HANDLER.post(rotationEvent);

        if (rotationEvent.isCancelled()) {
            rotation = snapshot;
            rotationOwner = rotationEvent.getOwner();
            lastRenderTime = System.currentTimeMillis();
        } else if (legacy != null) {
            rotation = new Rotation(legacy.getYaw(), legacy.getPitch());
            rotationOwner = legacy.getModule();
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
        LEGACY_PRIORITIES.put("SelfFill", 4);
    }

    public int getLegacyModulePriority(Module module) {
        return LEGACY_PRIORITIES.getOrDefault(module.getName(), 0);
    }

    private int compareLegacyRotations(eu.client.utils.rotations.LegacyRotation target, eu.client.utils.rotations.LegacyRotation rotation) {
        if (target.getPriority() == rotation.getPriority()) return -Long.compare(target.getTime(), rotation.getTime());
        return -Integer.compare(target.getPriority(), rotation.getPriority());
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

    // ---- MovementFix: DELETED 2026-08-14 ------------------------------------------------------
    //
    // bản gốc 1.21.4 has a MovementFix too (RotationsModule.movementFix -> onUpdateVelocity /
    // onKeyboardTick / onPlayerJump) and it is DEFAULT OFF, i.e. the known-good behaviour the user
    // is comparing against is: silent rotation touches mc.player yaw/pitch ONLY for the
    // UpdateMovementEvent -> UpdateMovementEvent.Post window that encloses sendPosition, and the
    // real WASD input, the reported key bits and the local physics yaw are never touched at all.
    //
    // The port had grown an always-on octant remap on top of that: it rewrote ClientInput.moveVector
    // AND keyPresses every tick a rotation was held, swapped local physics onto the spoofed yaw
    // (LivingEntityMixin) and force-desprinted whenever the snapped octant had no forward component.
    // That is the rubberband/stutter: the player physically walks up to 22.5 deg off from where they
    // pressed, the reported key bits flip between octants as the aim target swings around them, and
    // sprint is toggled on/off tick by tick. None of it existed in bản gốc. Removed outright rather
    // than put behind a toggle -- Rotate=Packet stays available for the cases that genuinely want a
    // rotation the movement packet never carries.

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
        // 2026-08-14: back to PosRot (= bản gốc's PlayerMoveC2SPacket.Full), with the SERVER-side
        // position from POSITION_MANAGER, byte-for-byte what the known-good 1.21.4 version sends.
        // It had been switched to .Rot on a GrimAC theory; the version the user is comparing against
        // has always sent the full variant and does not rubberband, so the extra-position theory was
        // not the cause and the divergence is reverted rather than kept.
        eu.client.pingbypass.server.ProxyServerTickListener.allowSend(() ->
                mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
                        EUClient.POSITION_MANAGER.getServerX(), EUClient.POSITION_MANAGER.getServerY(),
                        EUClient.POSITION_MANAGER.getServerZ(), yaw, pitch,
                        EUClient.POSITION_MANAGER.isServerOnGround(), mc.player.horizontalCollision)));
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
