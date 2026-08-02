package eu.client.pingbypass.handler;

import eu.client.pingbypass.server.ProxyServer;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.handshake.ServerHandshakePacketListener;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.status.StatusProtocols;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the initial Minecraft handshake packet for the PingBypass proxy.
 * Validates protocol version, enforces single-connection invariant,
 * and transitions to the appropriate next handler based on client intent.
 */
public class PbHandshakeHandler implements ServerHandshakePacketListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(PbHandshakeHandler.class);

    // PORT: this was still 769 (1.21.4's protocol number) after the 26.1.2 port -- rejected every
    // real 26.1.2 client with "Incompatible client! Please use 1.21.4" since the handshake compared
    // against the wrong version. 775 is 26.1.2's actual RELEASE_NETWORK_PROTOCOL_VERSION.
    private static final int PROTOCOL_VERSION = 775;

    private final ProxyServer proxyServer;
    private final Connection connection;

    public PbHandshakeHandler(ProxyServer proxyServer, Connection connection) {
        this.proxyServer = proxyServer;
        this.connection = connection;
    }

    @Override
    public void handleIntention(ClientIntentionPacket packet) {
        switch (packet.intention()) {
            case LOGIN -> handleLogin(packet);
            case STATUS -> handleStatus();
            default -> throw new UnsupportedOperationException(
                    "Invalid intention " + packet.intention());
        }
    }

    private void handleLogin(ClientIntentionPacket packet) {
        this.connection.setupOutboundProtocol(LoginProtocols.CLIENTBOUND);

        if (packet.protocolVersion() != PROTOCOL_VERSION) {
            Component message;
            if (packet.protocolVersion() < PROTOCOL_VERSION) {
                message = Component.translatable(
                        "multiplayer.disconnect.outdated_client", "26.1.2");
            } else {
                message = Component.translatable(
                        "multiplayer.disconnect.incompatible", "26.1.2");
            }

            this.connection.send(new ClientboundLoginDisconnectPacket(message));
            this.connection.disconnect(message);
            LOGGER.info("Rejected connection from {}: wrong protocol version {}",
                    this.connection.getLoggableAddress(false), packet.protocolVersion());
            return;
        }

        // Single-connection invariant: reject if a client is already connected
        if (hasActivePlayConnection()) {
            Component message = Component.literal("This PingBypass server is already in use!");
            this.connection.send(new ClientboundLoginDisconnectPacket(message));
            this.connection.disconnect(message);
            LOGGER.info("Rejected connection from {}: another client is already connected",
                    this.connection.getLoggableAddress(false));
            return;
        }

        // Transition to login handler
        this.connection.setupInboundProtocol(LoginProtocols.SERVERBOUND,
                new PbLoginHandler(this.proxyServer, this.connection));
        LOGGER.info("Client {} starting login",
                this.connection.getLoggableAddress(false));
    }

    private void handleStatus() {
        this.connection.setupOutboundProtocol(StatusProtocols.CLIENTBOUND);
        this.connection.setupInboundProtocol(StatusProtocols.SERVERBOUND,
                new PbStatusHandler(this.proxyServer, this.connection));
        LOGGER.debug("Received STATUS handshake, serving proxy status");
    }

    /**
     * Checks whether there is already an active play-state connection
     * by inspecting the proxy server's connections list.
     */
    private boolean hasActivePlayConnection() {
        for (Connection conn : this.proxyServer.getConnections()) {
            if (conn == this.connection) {
                continue;
            }
            if (conn.isConnected() && conn.getPacketListener() != null
                    && conn.getPacketListener().protocol() == ConnectionProtocol.PLAY) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onDisconnect(DisconnectionDetails info) {
        // Nothing to clean up during handshake
    }

    @Override
    public boolean isAcceptingMessages() {
        return this.connection.isConnected();
    }
}
