package eu.client.modules.impl.miscellaneous;

import com.mojang.authlib.GameProfile;
import lombok.Getter;
import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerUpdateEvent;
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

import java.util.Collections;
import java.util.EnumSet;
import java.util.UUID;

// proxyEnhanced so the toggle reaches the proxy: combat modules like AutoCrystal
// run their real logic on the proxy's own mc.level (the actual connection to the
// real server) and scan mc.level.players() there -- a dummy spawned only on this
// client's own (mostly unused, pre-real-connection) level is invisible to them.
// shouldRunOnProxy() below skips the client-side spawn while proxying so there's
// only ever one copy, spawned on whichever side is actually driving gameplay.
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

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (player == null) return;
        if (!movementMode.getValue().equalsIgnoreCase("Random")) return;

        BlockPos position = player.blockPosition();
        boolean changeDirection = false;

        if (this.changeDirection.getValue() && timer.hasTimeElapsed(timeout.getValue().longValue() * 1000L)) {
            changeDirection = true;
            timer.reset();
        }

        if (hasObstruction(position)) {
            for (Direction direction : Direction.values()) {
                BlockPos offsetPosition = position.relative(direction);
                if ((WorldUtils.blocksMovement(mc.level.getBlockState(offsetPosition)) || WorldUtils.blocksMovement(mc.level.getBlockState(offsetPosition.above()))) && WorldUtils.blocksMovement(mc.level.getBlockState(offsetPosition.above().above())) && player.getDirection().equals(direction)) {
                    changeDirection = true;
                    timer.reset();
                }
            }
        }

        if (changeDirection) {
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

    /** Mirrors the fake player's current position/rotation to the connected client. */
    private void broadcastPosition() {
        if (!isRunningOnProxy() || EUClient.PROXY_SERVER == null) return;

        var teleportPacket = ClientboundTeleportEntityPacket.teleport(
                player.getId(), net.minecraft.world.entity.PositionMoveRotation.of(player), java.util.Set.of(), player.onGround());
        for (Connection conn : EUClient.PROXY_SERVER.getConnections()) {
            if (conn.isConnected()) conn.send(teleportPacket);
        }
    }

    @Override
    public void onEnable() {
        if (shouldRunOnProxy()) return;
        if (mc.level == null) {
            setToggled(false);
            return;
        }

        player = new RemotePlayer(mc.level, new GameProfile(UUID.randomUUID(), name.getValue()));
        player.copyPosition(mc.player);
        player.setId(-673);
        player.restoreFrom(mc.player);
        player.setHealth(health.getValue().floatValue());
        player.setAbsorptionAmount(absorption.getValue().floatValue());

        net.minecraft.world.level.storage.TagValueOutput valueOutput = net.minecraft.world.level.storage.TagValueOutput.createWithoutContext(net.minecraft.util.ProblemReporter.DISCARDING);
        mc.player.saveWithoutId(valueOutput);
        player.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, mc.level.registryAccess(), valueOutput.buildResult()));

        // The NBT round-trip above restores the Inventory tag, but that's the STORAGE-side
        // inventory -- the RENDER-facing equipment LivingEntity actually draws the model from
        // (getItemBySlot/setItemSlot) isn't guaranteed to be populated just by loading Inventory
        // back on a freshly constructed entity outside the normal server tick/sync path that
        // usually keeps the two in step. Set it explicitly so the spawned model actually shows
        // armor/held items instead of a bare-handed dummy.
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            player.setItemSlot(slot, mc.player.getItemBySlot(slot).copy());
        }

        mc.level.addEntity(player);

        player.tick();
        timer.reset();

        broadcastSpawn();
    }

    @Override
    public void onDisable() {
        if (shouldRunOnProxy()) return;
        if (mc.level == null || player == null) return;

        broadcastDespawn();
        mc.level.removeEntity(player.getId(), Entity.RemovalReason.DISCARDED);
    }

    /**
     * The fake player is spawned via mc.level.addEntity() directly -- a purely
     * local operation that never produces a real ClientboundAddEntityPacket, so
     * S2CForwarder (which only relays packets actually received from the real
     * server) has nothing to forward. When running on the proxy, synthesize and
     * send the same packets a real spawn would produce so the connected client
     * actually sees the model.
     */
    private void broadcastSpawn() {
        if (!isRunningOnProxy() || EUClient.PROXY_SERVER == null) return;

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
        double[] direction = new double[]{-Math.sin(angle), Math.cos(angle)};

        return new double[]{direction[0] * velocity.getValue().floatValue(), direction[1] * velocity.getValue().floatValue()};
    }
}
