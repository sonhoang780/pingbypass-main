package eu.client.pingbypass.server;

import com.google.common.collect.Sets;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Holder;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Set;

/**
 * Sends a minimal fake "lobby" world to the client when the proxy is not
 * connected to any real Minecraft server.
 */
public class LobbyWorldSender {
    private static final Logger LOGGER = LoggerFactory.getLogger(LobbyWorldSender.class);

    private LobbyWorldSender() {
    }

    /**
     * Sends a minimal world using the provided registry manager (must contain dimension_type).
     */
    public static void sendLobbyWorld(Connection toClient, RegistryAccess registryManager) {
        LOGGER.info("Sending lobby world to client...");
        try {
            Registry<DimensionType> dimRegistry = registryManager.lookupOrThrow(Registries.DIMENSION_TYPE);
            Holder<DimensionType> overworldType = dimRegistry.getOrThrow(BuiltinDimensionTypes.OVERWORLD);

            Set<ResourceKey<Level>> dimensionIds = Sets.newHashSet(Level.OVERWORLD, Level.NETHER, Level.END);

            CommonPlayerSpawnInfo spawnInfo = new CommonPlayerSpawnInfo(
                    overworldType, Level.OVERWORLD, 0L, GameType.SPECTATOR,
                    null, false, true, Optional.empty(), 0, 63);

            send(toClient, new ClientboundLoginPacket(
                    1337, false, dimensionIds, 1, 16, 16,
                    false, true, false, spawnInfo, false));

            send(toClient, new ClientboundPlayerAbilitiesPacket(createLobbyAbilities()));
            send(toClient, new ClientboundSetHeldSlotPacket(0));
            send(toClient, new ClientboundPlayerPositionPacket(1,
                    new PositionMoveRotation(new Vec3(0, 240, 0), Vec3.ZERO, 0f, 0f),
                    Set.of()));

            // Signal that chunks are coming (even though we send none) so the client
            // leaves "Loading Terrain" screen
            send(toClient, new ClientboundGameEventPacket(ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0.0f));

            LOGGER.info("Lobby world sent successfully.");
        } catch (Exception e) {
            LOGGER.error("Failed to send lobby world", e);
        }
    }

    private static void send(Connection connection, Packet<?> packet) {
        if (connection.isConnected()) {
            connection.send(packet);
        }
    }

    private static Abilities createLobbyAbilities() {
        Abilities abilities = new Abilities();
        abilities.mayfly = true;
        abilities.flying = true;
        abilities.invulnerable = true;
        return abilities;
    }
}
