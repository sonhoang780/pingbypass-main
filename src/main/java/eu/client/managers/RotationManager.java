package eu.client.managers;

import lombok.Getter;
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

import java.util.HashMap;
import java.util.concurrent.PriorityBlockingQueue;

public class RotationManager implements IMinecraft {
    private final PriorityBlockingQueue<Rotation> queue = new PriorityBlockingQueue<>(11, this::compareRotations);
    @Getter private Rotation rotation = null;

    private float prevFixYaw;

    private float prevYaw;
    private float prevPitch;

    @Getter private float serverYaw;
    @Getter private float serverPitch;

    private float prevRenderYaw, prevRenderPitch;
    private long lastRenderTime = 0L;

    private static final HashMap<String, Integer> PRIORITIES = new HashMap<>();
    static {
        PRIORITIES.put("KillAura", 1);
        PRIORITIES.put("AutoCrystal", 2);
        PRIORITIES.put("SpeedMine", 3);
        PRIORITIES.put("SelfFill", 4);
    }

    public RotationManager() {
        EUClient.EVENT_HANDLER.subscribe(this);
    }

    @SubscribeEvent(priority = Integer.MIN_VALUE)
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        queue.removeIf(rotation -> System.currentTimeMillis() - rotation.getTime() > 100);
        rotation = queue.peek();

        if (rotation == null) return;
        lastRenderTime = System.currentTimeMillis();
    }

    @SubscribeEvent(priority = Integer.MAX_VALUE)
    public void onUpdateMovement(UpdateMovementEvent event) {
        if (rotation == null) return;
        // On the proxy, don't modify mc.player's yaw/pitch — the client sends
        // its own movement packets and we don't want the proxy's player entity
        // to visibly rotate. "Normal" mode relies on that temporary yaw/pitch
        // change riding along on the next movement packet, which never happens
        // here, so it was a silent no-op -- send an equivalent rotation packet
        // directly instead, same as "Packet" mode does.
        if (eu.client.pingbypass.PingBypassFlags.proxyForwardingActive
                && EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer()) {
            packetRotate(rotation.getYaw(), rotation.getPitch());
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

    public void rotate(float[] rotations, int priority) {
        rotate(rotations[0], rotations[1], priority);
    }

    public void rotate(float yaw, float pitch, int priority) {
        queue.removeIf(rotation -> rotation.getModule() == null && rotation.getPriority() == priority);
        queue.add(new Rotation(yaw, pitch, priority));
    }

    public void rotate(float[] rotations, Module module) {
        rotate(rotations[0], rotations[1], module);
    }

    public void rotate(float yaw, float pitch, Module module) {
        queue.removeIf(rotation -> rotation.getModule() == module);
        queue.add(new Rotation(yaw, pitch, module, getModulePriority(module)));
    }

    public void rotate(float[] rotations, Module module, int priority) {
        rotate(rotations[0], rotations[1], module, priority);
    }

    public void rotate(float yaw, float pitch, Module module, int priority) {
        queue.removeIf(rotation -> rotation.getModule() == module);
        queue.add(new Rotation(yaw, pitch, module, priority));
    }

    public void packetRotate(float[] rotations) {
        packetRotate(rotations[0], rotations[1]);
    }

    public void packetRotate(float yaw, float pitch) {
        if (serverYaw == yaw && serverPitch == pitch) return;
        // On the proxy, send the rotation directly to the server connection,
        // bypassing mc.getConnection() so the proxy's local player state
        // is never touched. The proxy's player entity doesn't visibly rotate
        // because onUpdateMovement is also skipped when proxy is active.
        if (eu.client.pingbypass.PingBypassFlags.proxyForwardingActive
                && EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer()
                && EUClient.PROXY_SERVER != null) {
            var serverConn = EUClient.PROXY_SERVER.getServerConnection();
            if (serverConn != null && serverConn.isConnected()) {
                // Rot-only (no X/Y/Z) -- the client's own movement packets are already being
                // forwarded straight through by PbPlayHandler's dumb pipe. Sending a PosRot
                // here too, built from mc.player's proxy-side mirrored coordinates (which can
                // be a tick stale relative to whatever the client's own movement packet already
                // in flight says), races that forward: the real server sees two conflicting
                // position reports close together and corrects/rubberbands the player. Rotation
                // alone can't conflict with position at all.
                // Echo the flags the client itself last reported rather than reading them off
                // the proxy's ghost player -- see PingBypassFlags.clientOnGround. Contradicting
                // the client's own forwarded movement packets is what rubberbands the player.
                var packet = new ServerboundMovePlayerPacket.Rot(
                        yaw, pitch,
                        eu.client.pingbypass.PingBypassFlags.clientOnGround,
                        eu.client.pingbypass.PingBypassFlags.clientHorizontalCollision);
                eu.client.pingbypass.server.ProxyServerTickListener.allowSend(() -> serverConn.send(packet));
                return;
            }
        }
        mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
                EUClient.POSITION_MANAGER.getServerX(), EUClient.POSITION_MANAGER.getServerY(),
                EUClient.POSITION_MANAGER.getServerZ(), yaw, pitch,
                EUClient.POSITION_MANAGER.isServerOnGround(), mc.player.horizontalCollision));
    }

    public boolean inRenderTime() {
        return System.currentTimeMillis() - lastRenderTime < 1000;
    }

    public float[] getRenderRotations() {
        float from = MathUtils.wrapAngle(prevRenderYaw), to = MathUtils.wrapAngle(rotation == null ? mc.player.getYRot() : getServerYaw());
        float delta = to - from;
        if(delta > 180) delta -= 380;
        else if(delta < -180) delta += 360;

        float yaw = Mth.lerp(Easing.toDelta(lastRenderTime, 1000), from, from + delta);
        float pitch = Mth.lerp(Easing.toDelta(lastRenderTime, 1000), prevRenderPitch, rotation == null ? mc.player.getXRot() : getServerPitch());
        prevRenderYaw = yaw;
        prevRenderPitch = pitch;

        return new float[]{yaw, pitch};
    }

    public int getModulePriority(Module module) {
        return PRIORITIES.getOrDefault(module.getName(), 0);
    }

    /** Same lookup as getModulePriority(Module), for PbModule (proxy-only, not a client Module). */
    public int getModulePriority(String moduleName) {
        return PRIORITIES.getOrDefault(moduleName, 0);
    }

    private int compareRotations(Rotation target, Rotation rotation) {
        if (target.getPriority() == rotation.getPriority()) return -Long.compare(target.getTime(), rotation.getTime());
        return -Integer.compare(target.getPriority(), rotation.getPriority());
    }
}
