package eu.client.pingbypass.handler;

import eu.client.EUClient;
import eu.client.pingbypass.server.ProxyServer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.status.ServerStatusPacketListener;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
import net.minecraft.network.protocol.status.ServerboundStatusRequestPacket;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Handles server list ping (STATUS) requests for the PingBypass proxy.
 * Responds with the proxy's current state so the client can show
 * whether the proxy is idle or connected to a server.
 */
public class PbStatusHandler implements ServerStatusPacketListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(PbStatusHandler.class);

    private final ProxyServer proxyServer;
    private final Connection connection;
    private boolean responseSent;

    public PbStatusHandler(ProxyServer proxyServer, Connection connection) {
        this.proxyServer = proxyServer;
        this.connection = connection;
    }

    @Override
    public void handleStatusRequest(ServerboundStatusRequestPacket packet) {
        if (responseSent) {
            connection.disconnect(Component.literal("Status already sent"));
            return;
        }
        responseSent = true;

        // Build MOTD showing proxy state
        Component description = buildDescription();

        ServerStatus metadata = new ServerStatus(
                description,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false);

        connection.send(new ClientboundStatusResponsePacket(metadata));
    }

    @Override
    public void handlePingRequest(ServerboundPingRequestPacket packet) {
        connection.send(new ClientboundPongResponsePacket(packet.getTime()));
        connection.disconnect(Component.literal("Ping done"));
    }

    @Override
    public void onDisconnect(DisconnectionDetails info) {}

    @Override
    public boolean isAcceptingMessages() {
        return connection.isConnected();
    }

    private Component buildDescription() {
        Minecraft mc = Minecraft.getInstance();
        StringBuilder motd = new StringBuilder();
        motd.append("§dEUClient PingBypass§r\n");

        if (mc.getConnection() != null && mc.player != null && mc.level != null) {
            // Proxy is connected to a server
            String serverBrand = mc.getConnection().serverBrand();
            motd.append("§aConnected§r");
            if (proxyServer.getServerConnection() != null) {
                motd.append(" — ").append(mc.getCurrentServer() != null
                        ? mc.getCurrentServer().ip : "unknown");
            }
        } else {
            motd.append("§7Idle§r — not connected to any server");
        }

        return Component.literal(motd.toString());
    }
}
