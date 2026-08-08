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
        // Same call on both sides -- mc.getConnection() on the proxy IS the real server
        // connection (see ProxyServer.setServerConnection callers), so this needs no
        // proxy-specific branch. POSITION_MANAGER mirrors mc.player's own coordinates on
        // both sides too (the proxy's mc.player is kept positioned by PbPlayHandler).
        // ProxyServerTickListener now blacklists ServerboundMovePlayerPacket by default (matches
        // earthhack's Pb2SManager, see PbPlayHandler.handleMovePlayer0) -- this send needs the
        // same explicit authorization, or the proxy silently drops every packet-rotate from
        // AutoCrystal/other proxy-side aim modules. allowSend() is a no-op on the client (that
        // listener is only ever subscribed on the proxy), so this is safe unconditionally.
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
