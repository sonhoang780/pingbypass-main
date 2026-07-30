package eu.client.pingbypass.server;

import eu.client.pingbypass.protocol.PbCustomPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Intercepts packets flowing through the proxy and applies necessary corrections.
 * Handles bidirectional forwarding between the client and the real Minecraft server.
 */
public class PacketForwarder {
    private static final Logger LOGGER = LoggerFactory.getLogger(PacketForwarder.class);

    private final Connection proxyToClient;
    private final Connection proxyToServer;

    public PacketForwarder(Connection proxyToClient, Connection proxyToServer) {
        this.proxyToClient = proxyToClient;
        this.proxyToServer = proxyToServer;
    }

    /**
     * Forward a serverbound packet from the client to the real server.
     * Applies special handling for certain packet types.
     */
    public void forwardToServer(Packet<?> packet) {
        try {
            // Filter custom payload packets on the euclient:pingbypass channel
            if (packet instanceof ServerboundCustomPayloadPacket customPayload) {
                CustomPacketPayload payload = customPayload.payload();
                if (PbCustomPayload.CHANNEL.equals(payload.type().id())) {
                    // Dispatch to protocol handler, don't forward to server
                    handlePingBypassPayload(customPayload);
                    return;
                }
            }

            // ServerboundMovePlayerPacket: schedule on main thread for ordering
            if (packet instanceof ServerboundMovePlayerPacket) {
                Minecraft.getInstance().execute(() -> {
                    if (proxyToServer.isConnected()) {
                        proxyToServer.send(packet);
                    }
                });
                return;
            }

            // ServerboundContainerClickPacket: re-create with fresh revision from server-side container
            if (packet instanceof ServerboundContainerClickPacket clickSlot) {
                handleClickSlot(clickSlot);
                return;
            }

            // ServerboundSetCarriedItemPacket: forward and sync server-side slot
            if (packet instanceof ServerboundSetCarriedItemPacket selectedSlot) {
                handleSelectedSlot(selectedSlot);
                return;
            }

            // Default: forward directly to server
            proxyToServer.send(packet);
        } catch (Exception e) {
            LOGGER.error("Error forwarding packet to server: {}", packet.getClass().getSimpleName(), e);
        }
    }

    /**
     * Forward a clientbound packet from the real server to the client.
     */
    public void forwardToClient(Packet<?> packet) {
        try {
            proxyToClient.send(packet);
        } catch (Exception e) {
            LOGGER.error("Error forwarding packet to client: {}", packet.getClass().getSimpleName(), e);
        }
    }

    /**
     * Handle ServerboundContainerClickPacket. The stateId re-sync this used to do requires
     * building a fresh HashedStack (server-side hash generator context we don't have here);
     * just forward as-is, matching the exception fallback path this used to have anyway.
     */
    private void handleClickSlot(ServerboundContainerClickPacket clickSlot) {
        if (proxyToServer.isConnected()) {
            proxyToServer.send(clickSlot);
        }
    }

    /**
     * Handle ServerboundSetCarriedItemPacket by forwarding it and syncing
     * the server-side player's selected slot.
     */
    private void handleSelectedSlot(ServerboundSetCarriedItemPacket selectedSlot) {
        // Forward the packet to the real server
        proxyToServer.send(selectedSlot);

        // Sync the server-side player's held item slot
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.getInventory().setSelectedSlot(selectedSlot.getSlot());
            }
        });
    }

    /**
     * Handle custom payload packets on the euclient:pingbypass channel.
     * Dispatches to PbProtocolHandler (to be created in Task 8.2).
     */
    private void handlePingBypassPayload(ServerboundCustomPayloadPacket packet) {
        CustomPacketPayload payload = packet.payload();
        if (payload instanceof PbCustomPayload pbPayload) {
            // TODO: Dispatch to PbProtocolHandler once created in Task 8.2
            LOGGER.debug("Received PingBypass protocol packet, dispatching to protocol handler");
        }
    }

    public Connection getProxyToClient() {
        return proxyToClient;
    }

    public Connection getProxyToServer() {
        return proxyToServer;
    }
}
