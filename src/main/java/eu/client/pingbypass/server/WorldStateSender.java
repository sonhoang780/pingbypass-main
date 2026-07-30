package eu.client.pingbypass.server;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends the full current world state to a reconnecting client.
 * Used when a client connects to the proxy while Stay Connected is active
 * and the proxy is already joined to a Minecraft server.
 */
public class WorldStateSender {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldStateSender.class);

    private WorldStateSender() {
    }

    /**
     * Sends the complete world state to the given client connection.
     * This allows a reconnecting client to see the current world as-is.
     * Delegates to WorldStateReplay which has the full implementation.
     *
     * @param world    the current ClientLevel on the proxy
     * @param player   the proxy's LocalPlayer
     * @param toClient the connection to the reconnecting client
     */
    public static void sendWorld(ClientLevel world, LocalPlayer player, Connection toClient) {
        LOGGER.info("Sending world state to reconnecting client...");

        try {
            if (world == null || player == null || toClient == null) {
                LOGGER.warn("Cannot send world state: null parameter(s)");
                return;
            }
            // Delegate to WorldStateReplay which has the complete implementation
            WorldStateReplay.replay(toClient);
            LOGGER.info("World state sent successfully.");
        } catch (Exception e) {
            LOGGER.error("Error sending world state to client", e);
        }
    }
}
