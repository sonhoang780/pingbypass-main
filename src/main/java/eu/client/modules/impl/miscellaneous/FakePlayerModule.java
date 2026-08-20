package eu.client.modules.impl.miscellaneous;

import com.mojang.authlib.GameProfile;
import lombok.Getter;
import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.ClientConnectEvent;
import eu.client.events.impl.PlayerDeathEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.events.impl.RenderWorldEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.*;
import eu.client.utils.minecraft.WorldUtils;
import eu.client.utils.rotations.RotationUtils;
import eu.client.utils.system.MathUtils;
import eu.client.utils.system.Timer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.GameType;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

@RegisterModule(name = "FakePlayer", description = "Spawns in a fake player entity that you can use to test modules on.", category = Module.Category.MISCELLANEOUS, proxyEnhanced = true)
public class FakePlayerModule extends Module {
    public StringSetting name = new StringSetting("Name", "The name that will be assigned to the fake player.", "Dummy");
    public NumberSetting health = new NumberSetting("Health", "The amount of health that will be assigned to the fake player.", 20.0f, 1.0f, 20.0f);
    public NumberSetting absorption = new NumberSetting("Absorption", "The amount of absorption that will be assigned to the fake player.", 16, 0, 16);

    public CategorySetting movementCategory = new CategorySetting("Movement", "The category that contains settings related to movement.");
    public ModeSetting movementMode = new ModeSetting("Movement", "Mode", "The mode that will be used for the fake player's movement.", new CategorySetting.Visibility(movementCategory), "None", new String[]{"None", "Random"});
    public NumberSetting velocity = new NumberSetting("Velocity", "Velocity", "The velocity to apply on the fake player.", new CategorySetting.Visibility(movementCategory), 0.3f, 0.1f, 0.4f);
    public BooleanSetting changeDirection = new BooleanSetting("ChangeDirection", "Changes the player's direction when a specified amount of time has passed.", new CategorySetting.Visibility(movementCategory), false);
    public NumberSetting timeout = new NumberSetting("Timeout", "The amount of time that it takes for the player to change direction.", new BooleanSetting.Visibility(changeDirection, true), 5, 1, 15);

    @Getter private RemotePlayer player = null;

    private double[] direction = generateDirection();
    private final Timer timer = new Timer();
    private boolean stepping = false;
    private final Timer stepTimer = new Timer();

    private enum RecordState { NONE, RECORDING, PLAYING }
    @Getter private RecordState recordState = RecordState.NONE;
    private final List<RecordFrame> frames = new ArrayList<>();
    private int playIndex = 0;

    public record RecordFrame(double x, double y, double z, float yRot, float xRot, float yHeadRot, Pose pose) {}

    public void startRecording() {
        if (player == null) {
            EUClient.CHAT_MANAGER.warn("Spawn a fake player first before recording!");
            return;
        }
        frames.clear();
        recordState = RecordState.RECORDING;
        EUClient.CHAT_MANAGER.tagged("Started recording movements...", "FakePlayer");
    }

    public void stopRecording() {
        recordState = RecordState.NONE;
        EUClient.CHAT_MANAGER.tagged("Record!", "FakePlayer");
    }

    public void startPlaying() {
        if (frames.isEmpty()) {
            EUClient.CHAT_MANAGER.warn("No recorded frames to play! Use '.fakeplayer record' first.");
            return;
        }
        if (player == null) {
            setToggled(true); 
        }
        recordState = RecordState.PLAYING;
        playIndex = 0;
        EUClient.CHAT_MANAGER.tagged("Playing recorded movements in a loop...", "FakePlayer");
    }

    @SubscribeEvent
    public void onDisconnect(ClientConnectEvent event) {
        if (isToggled()) {
            setToggled(false);
        }
    }

    // Requested: auto-off on quit/die -- FakePlayer replaying old movement/rotation over a dead
    // or gone player is nonsense state to keep armed.
    @SubscribeEvent
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (event.getPlayer() != mc.player) return; // fires for ANY player death, filter to local
        if (isToggled()) {
            setToggled(false);
        }
    }

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (mc.player == null || mc.level == null) return;

        // Ghi lại chuyển động ở mức Game Tick (20 Hz)
        if (recordState == RecordState.RECORDING) {
            frames.add(new RecordFrame(
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    mc.player.getYRot(), mc.player.getXRot(), mc.player.getYHeadRot(),
                    mc.player.getPose()
            ));
            return;
        }

        // Tăng chỉ số frame theo Game Tick khi đang phát lại
        if (recordState == RecordState.PLAYING) {
            if (player == null || frames.isEmpty()) return;
            playIndex++;
            if (playIndex >= frames.size()) {
                playIndex = 0;
            }
            broadcastPosition();
            return;
        }

        // Logic chuyển động ngẫu nhiên (Random)
        if (player == null) return;
        if (!movementMode.getValue().equalsIgnoreCase("Random")) return;

        BlockPos position = player.blockPosition();
        boolean changeDir = false;

        if (this.changeDirection.getValue() && timer.hasTimeElapsed(timeout.getValue().longValue() * 1000L)) {
            changeDir = true;
            timer.reset();
        }

        if (hasObstruction(position)) {
            for (Direction dir : Direction.values()) {
                BlockPos offsetPosition = position.relative(dir);
                if ((WorldUtils.blocksMovement(mc.level.getBlockState(offsetPosition)) || WorldUtils.blocksMovement(mc.level.getBlockState(offsetPosition.above()))) && WorldUtils.blocksMovement(mc.level.getBlockState(offsetPosition.above().above())) && player.getDirection().equals(dir)) {
                    changeDir = true;
                    timer.reset();
                }
            }
        }

        if (changeDir) {
            double[] newDirection = generateDirection();
            if (direction == newDirection) newDirection = generateDirection();
            direction = newDirection;
        }

        if (stepping && stepTimer.hasTimeElapsed(500L)) {
            stepping = false;
            stepTimer.reset();
        }

        player.jumpFromGround();

        float[] rotations = RotationUtils.getRotations(player, player.getX() + direction[0], player.getY() + fixAxisY(player), player.getZ() + direction[1]);

        player.setYRot(rotations[0]);
        player.yHeadRot = rotations[0];
        player.setXRot(0.0f);

        player.setPos(player.getX() + direction[0], player.getY() + fixAxisY(player), player.getZ() + direction[1]);

        broadcastPosition();
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        // Nội suy tuyến tính theo từng Render Frame (FPS)
        if (recordState != RecordState.PLAYING || player == null || frames.isEmpty()) return;

        int currentIndex = playIndex;
        int nextIndex = (currentIndex + 1) % frames.size();

        RecordFrame current = frames.get(currentIndex);
        RecordFrame next = frames.get(nextIndex);

        float delta = event.getTickDelta();
        if (nextIndex == 0) {
            delta = 0.0f; // Tránh trượt ngược khi kết thúc vòng lặp
        }

        double x = Mth.lerp(delta, current.x(), next.x());
        double y = Mth.lerp(delta, current.y(), next.y());
        double z = Mth.lerp(delta, current.z(), next.z());
        float yRot = Mth.rotLerp(delta, current.yRot(), next.yRot());
        float xRot = Mth.lerp(delta, current.xRot(), next.xRot());
        float yHeadRot = Mth.rotLerp(delta, current.yHeadRot(), next.yHeadRot());

        player.setPos(x, y, z);
        player.setYRot(yRot);
        player.setXRot(xRot);
        player.yHeadRot = yHeadRot;
        player.yBodyRot = yRot;

        player.setOldPosAndRot();
        player.yHeadRotO = yHeadRot;
        player.yBodyRotO = yRot;

        player.setPose(current.pose());
        player.refreshDimensions();
    }

    private void broadcastPosition() {
        if (!isRunningOnProxy() || EUClient.PROXY_SERVER == null) return;
        if (player == null) return;

        var teleportPacket = ClientboundTeleportEntityPacket.teleport(
                player.getId(), net.minecraft.world.entity.PositionMoveRotation.of(player), java.util.Set.of(), player.onGround());
        for (Connection conn : EUClient.PROXY_SERVER.getConnections()) {
            if (conn.isConnected()) conn.send(teleportPacket);
        }
    }

    @Override
    public void onEnable() {
        if (shouldRunOnProxy()) return;
        if (mc.level == null || mc.player == null) {
            setToggled(false);
            return;
        }

        player = new RemotePlayer(mc.level, new GameProfile(UUID.randomUUID(), name.getValue()));
        player.copyPosition(mc.player);
        player.setId(-673);
        player.restoreFrom(mc.player);
        player.setHealth(health.getValue().floatValue());
        player.setAbsorptionAmount(absorption.getValue().floatValue());

        for (EquipmentSlot slot : EquipmentSlot.values()) {
            player.setItemSlot(slot, mc.player.getItemBySlot(slot).copy());
        }

        mc.level.addEntity(player);
        player.tick();
        timer.reset();

        broadcastSpawn();
        EUClient.CHAT_MANAGER.tagged("Spawned fake player " + name.getValue(), "FakePlayer");
    }

    @Override
    public void onDisable() {
        recordState = RecordState.NONE;
        if (shouldRunOnProxy()) return;
        if (mc.level == null || player == null) return;

        broadcastDespawn();
        mc.level.removeEntity(player.getId(), Entity.RemovalReason.DISCARDED);
        player = null;
        EUClient.CHAT_MANAGER.tagged("Removed fake player.", "FakePlayer");
    }

    private void broadcastSpawn() {
        if (!isRunningOnProxy() || EUClient.PROXY_SERVER == null) return;
        if (player == null) return;

        var entries = java.util.List.of(new ClientboundPlayerInfoUpdatePacket.Entry(
                player.getUUID(), player.getGameProfile(), false, 0,
                GameType.SURVIVAL, null, false, 0, null));
        var infoPacket = new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER), Collections.emptyList());
        ((eu.client.mixins.accessors.PlayerListS2CPacketAccessor) infoPacket)
                .setActions(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER));
        ((eu.client.mixins.accessors.PlayerListS2CPacketAccessor) infoPacket).setEntries(entries);

        var spawnPacket = new ClientboundAddEntityPacket(
                player.getId(), player.getUUID(),
                player.getX(), player.getY(), player.getZ(),
                player.getXRot(), player.getYRot(),
                player.getType(), 0,
                player.getDeltaMovement(), player.getYHeadRot());

        for (Connection conn : EUClient.PROXY_SERVER.getConnections()) {
            if (!conn.isConnected()) continue;
            conn.send(infoPacket);
            conn.send(spawnPacket);
        }
    }

    private void broadcastDespawn() {
        if (!isRunningOnProxy() || EUClient.PROXY_SERVER == null) return;
        if (player == null) return;

        var removePacket = new ClientboundRemoveEntitiesPacket(player.getId());
        var infoRemovePacket = new net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket(
                java.util.List.of(player.getUUID()));

        for (Connection conn : EUClient.PROXY_SERVER.getConnections()) {
            if (!conn.isConnected()) continue;
            conn.send(removePacket);
            conn.send(infoRemovePacket);
        }
    }

    public boolean hasObstruction(BlockPos position) {
        for (Direction direction : Direction.values()) {
            BlockPos offsetPosition = position.relative(direction);
            if (WorldUtils.blocksMovement(mc.level.getBlockState(offsetPosition))) return true;
            if (WorldUtils.blocksMovement(mc.level.getBlockState(offsetPosition.above()))) return true;
        }
        return false;
    }

    public float fixAxisY(Player player) {
        if (mc.level.getBlockState(player.blockPosition().below()).getBlock() == Blocks.AIR && !stepping) return -1;

        if (hasObstruction(player.blockPosition())) {
            for (Direction direction : Direction.values()) {
                BlockPos offsetPosition = player.blockPosition().relative(direction);

                if (WorldUtils.blocksMovement(mc.level.getBlockState(offsetPosition)) && !WorldUtils.blocksMovement(mc.level.getBlockState(offsetPosition.above())) && player.getDirection().equals(direction) && !WorldUtils.blocksMovement(mc.level.getBlockState(offsetPosition.above().above()))) {
                    stepping = true;
                    stepTimer.reset();
                    return 1;
                }

                if (WorldUtils.blocksMovement(mc.level.getBlockState(offsetPosition.above())) && player.getDirection().equals(direction) && !WorldUtils.blocksMovement(mc.level.getBlockState(offsetPosition.above().above()))) {
                    stepping = true;
                    stepTimer.reset();
                    return 2;
                }
            }
            return 0;
        }
        return 0;
    }

    public double[] generateDirection() {
        double angle = MathUtils.random(2 * Math.PI, 0);
        double[] dir = new double[]{-Math.sin(angle), Math.cos(angle)};
        return new double[]{dir[0] * velocity.getValue().floatValue(), dir[1] * velocity.getValue().floatValue()};
    }
}