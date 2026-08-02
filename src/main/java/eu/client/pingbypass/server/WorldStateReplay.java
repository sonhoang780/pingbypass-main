package eu.client.pingbypass.server;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Replays the current world state from the HeadlessMC proxy to the client.
 * Modeled after PingBypass's JoinWorldService — sends packets in the exact
 * order that a vanilla server would during PlayerList#placeNewPlayer.
 *
 * Returns a negative teleport ID that the client must confirm before the
 * proxy starts forwarding live S2C packets.
 */
public class WorldStateReplay {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldStateReplay.class);
    private static final Random RANDOM = new Random();

    /**
     * Replays the full world state. Returns the initialTeleportId that the
     * client will send back in a TeleportConfirmC2SPacket.
     */
    public static int replay(Connection toClient, net.minecraft.core.RegistryAccess registryAccess) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel world = mc.level;
        ClientPacketListener handler = mc.getConnection();

        if (player == null || world == null || handler == null) {
            LOGGER.error("Cannot replay world state: player/world/handler is null");
            return 0;
        }

        LOGGER.info("Replaying world state to client...");
        long start = System.currentTimeMillis();

        // Generate a negative teleport ID (vanilla never uses negative IDs)
        int initialTeleportId = -Math.abs(RANDOM.nextInt());
        if (initialTeleportId == 0) initialTeleportId = -1;

        GameType gameMode = mc.gameMode != null
                ? mc.gameMode.getPlayerMode() : GameType.SURVIVAL;
        GameType prevGameMode = mc.gameMode != null
                ? mc.gameMode.getPreviousPlayerMode() : null;

        // 1. GameJoin
        Set<ResourceKey<Level>> dimensionIds = handler.levels();
        CommonPlayerSpawnInfo spawnInfo = createSpawnInfo(world, gameMode, prevGameMode, player, registryAccess);

        send(toClient, new ClientboundLoginPacket(
                player.getId(), world.getLevelData().isHardcore(), dimensionIds,
                1, 16, 16,
                false, true, false, spawnInfo, false));

        // 2. Respawn to clear lobby world state (clears view entity, prevents falling)
        send(toClient, new ClientboundRespawnPacket(spawnInfo, (byte) 0));

        // 3. Difficulty
        send(toClient, new ClientboundChangeDifficultyPacket(world.getLevelData().getDifficulty(),
                world.getLevelData().isDifficultyLocked()));

        // 4. Abilities + slot
        send(toClient, new ClientboundPlayerAbilitiesPacket(player.getAbilities()));
        send(toClient, new ClientboundSetHeldSlotPacket(player.getInventory().getSelectedSlot()));

        // 5. Player list (must come before chunks so skins load)
        sendPlayerInfo(toClient, handler);

        // 6. Level info: world border, time, spawn, weather
        sendLevelInfo(toClient, world);

        // 7. Signal chunk loading start
        send(toClient, new ClientboundGameEventPacket(ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0.0f));

        // 8. Chunks
        sendChunks(toClient, player, world);

        // 9. Health, food, experience
        send(toClient, new ClientboundSetHealthPacket(player.getHealth(),
                player.getFoodData().getFoodLevel(),
                player.getFoodData().getSaturationLevel()));
        send(toClient, new ClientboundSetExperiencePacket(player.experienceProgress,
                player.totalExperience, player.experienceLevel));

        // 10. Inventory (full container contents)
        send(toClient, new ClientboundContainerSetContentPacket(player.inventoryMenu.containerId,
                player.inventoryMenu.incrementStateId(),
                player.inventoryMenu.getItems(),
                player.inventoryMenu.getCarried()));

        // 11. Entities
        sendEntities(toClient, world, player);

        // 12. Initial teleport with our negative ID — client must confirm this
        send(toClient, new ClientboundPlayerPositionPacket(initialTeleportId,
                new PositionMoveRotation(player.position(), Vec3.ZERO, player.getYRot(), player.getXRot()),
                Set.of()));

        // 13. Motion
        send(toClient, new ClientboundSetEntityMotionPacket(player));

        long elapsed = System.currentTimeMillis() - start;
        LOGGER.info("Level state replay completed in {}ms (teleportId={})", elapsed, initialTeleportId);
        return initialTeleportId;
    }

    private static void sendLevelInfo(Connection toClient, ClientLevel world) {
        send(toClient, new ClientboundInitializeBorderPacket(world.getWorldBorder()));
        send(toClient, new ClientboundSetTimePacket(world.getLevelData().getGameTime(), Map.of()));
        send(toClient, new ClientboundSetDefaultSpawnPositionPacket(world.getLevelData().getRespawnData()));

        if (world.isRaining()) {
            send(toClient, new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0.0f));
            send(toClient, new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE,
                    world.getRainLevel(1.0f)));
            send(toClient, new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE,
                    world.getThunderLevel(1.0f)));
        }
    }

    private static void sendPlayerInfo(Connection toClient, ClientPacketListener handler) {
        var playerList = handler.getOnlinePlayers();
        if (playerList.isEmpty()) return;

        var entries = new ArrayList<ClientboundPlayerInfoUpdatePacket.Entry>();
        for (var info : playerList) {
            entries.add(new ClientboundPlayerInfoUpdatePacket.Entry(
                    info.getProfile().id(),
                    info.getProfile(),
                    handler.getListedOnlinePlayers().contains(info),
                    info.getLatency(),
                    info.getGameMode(),
                    info.getTabListDisplayName(),
                    false, 0, null));
        }

        EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions = EnumSet.of(
                ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME);

        // Use mixin accessor to set private final fields
        var packet = new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER),
                Collections.emptyList());
        ((eu.client.mixins.accessors.PlayerListS2CPacketAccessor) packet).setActions(actions);
        ((eu.client.mixins.accessors.PlayerListS2CPacketAccessor) packet).setEntries(entries);

        send(toClient, packet);
        LOGGER.info("Sent {} player list entries to client", entries.size());
    }

    private static CommonPlayerSpawnInfo createSpawnInfo(ClientLevel world, GameType gameMode,
                                                         GameType prevGameMode, LocalPlayer player,
                                                         net.minecraft.core.RegistryAccess registryAccess) {
        // Don't trust world.dimensionTypeRegistration() -- that Holder was baked into this
        // ClientLevel object back when it was constructed and never changes afterward
        // (Level.registryAccess is a final field). If the real server pushed a live
        // dimension_type/registry reload since then, the encoder gets re-bound to a NEWER
        // registryAccess (see PbWaitingHandler) whose IdMap doesn't contain that older
        // Holder object -> "Can't find id for 'Reference{...dimension_type/overworld}'".
        // Re-resolve the holder fresh from the exact registryAccess instance the encoder
        // was just bound to, by key, so identity always matches.
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.dimension.DimensionType> dimensionTypeKey =
                world.dimensionTypeRegistration().unwrapKey().orElseThrow();
        net.minecraft.core.Holder<net.minecraft.world.level.dimension.DimensionType> dimensionType =
                registryAccess.lookupOrThrow(net.minecraft.core.registries.Registries.DIMENSION_TYPE)
                        .getOrThrow(dimensionTypeKey);
        return new CommonPlayerSpawnInfo(
                dimensionType,
                world.dimension(),
                0L,
                gameMode, prevGameMode,
                world.isDebug(),
                false, // ponytail: ClientLevel exposes no public isFlat() getter, flat-world flag is cosmetic only
                player.getLastDeathLocation(),
                player.getPortalCooldown(),
                world.getSeaLevel());
    }

    private static void sendEntities(Connection toClient, ClientLevel world, LocalPlayer localPlayer) {
        int count = 0;
        for (Entity entity : world.entitiesForRendering()) {
            if (entity == localPlayer) continue;

            try {
                // Send spawn packet using the full constructor
                send(toClient, new ClientboundAddEntityPacket(
                        entity.getId(), entity.getUUID(),
                        entity.getX(), entity.getY(), entity.getZ(),
                        entity.getXRot(), entity.getYRot(),
                        entity.getType(), 0,
                        entity.getDeltaMovement(), entity.getYHeadRot()));

                // Send tracked data (metadata)
                List<SynchedEntityData.DataValue<?>> entries = entity.getEntityData().getNonDefaultValues();
                if (entries != null && !entries.isEmpty()) {
                    send(toClient, new ClientboundSetEntityDataPacket(entity.getId(), entries));
                }

                // Send velocity
                send(toClient, new ClientboundSetEntityMotionPacket(entity));

                // Send head yaw for living entities
                if (entity instanceof LivingEntity living) {
                    send(toClient, new ClientboundRotateHeadPacket(entity, (byte) (living.getYHeadRot() * 256.0f / 360.0f)));

                    // Send equipment
                    var equipment = new ArrayList<com.mojang.datafixers.util.Pair<net.minecraft.world.entity.EquipmentSlot, net.minecraft.world.item.ItemStack>>();
                    for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
                        var stack = living.getItemBySlot(slot);
                        if (!stack.isEmpty()) {
                            equipment.add(new com.mojang.datafixers.util.Pair<>(slot, stack));
                        }
                    }
                    if (!equipment.isEmpty()) {
                        send(toClient, new ClientboundSetEquipmentPacket(entity.getId(), equipment));
                    }
                }

                count++;
            } catch (Exception e) {
                LOGGER.warn("Failed to send entity {} (ID: {})", entity.getType().getDescriptionId(), entity.getId(), e);
            }
        }
        LOGGER.info("Sent {} entities to client", count);
    }

    private static void sendChunks(Connection toClient, LocalPlayer player, ClientLevel world) {
        int cx = player.chunkPosition().x();
        int cz = player.chunkPosition().z();

        // Chunk center must come before chunk data
        send(toClient, new ClientboundSetChunkCacheCenterPacket(cx, cz));

        int radius = Math.min(Minecraft.getInstance().options.renderDistance().get(), 8);
        int sent = 0;
        for (int z = cz - radius; z <= cz + radius; z++) {
            for (int x = cx - radius; x <= cx + radius; x++) {
                LevelChunk chunk = world.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null) {
                    send(toClient, new ClientboundLevelChunkWithLightPacket(chunk, world.getChunkSource().getLightEngine(), null, null));
                    sent++;
                }
            }
        }
        LOGGER.info("Sent {} chunks to client", sent);
    }

    private static void send(Connection connection, Packet<?> packet) {
        if (connection.isConnected()) {
            connection.send(packet);
        }
    }
}
