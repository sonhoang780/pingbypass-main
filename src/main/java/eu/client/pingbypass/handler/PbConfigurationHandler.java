package eu.client.pingbypass.handler;

import com.mojang.authlib.GameProfile;
import eu.client.EUClient;
import eu.client.pingbypass.server.ProxyServer;
import eu.client.pingbypass.server.RegistryCache;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.network.TickablePacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ServerboundAcceptCodeOfConductPacket;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.core.RegistryAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Handles the CONFIGURATION phase for the PingBypass proxy.
 * Uses cached registry data from RegistryCache (loaded at startup via SaveLoading)
 * to send proper registries and tags to the connecting client.
 */
public class PbConfigurationHandler implements ServerConfigurationPacketListener, TickablePacketListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(PbConfigurationHandler.class);
    private static final GameProtocols.Context NO_INFINITE_MATERIALS = () -> false;

    private final ProxyServer proxyServer;
    private final Connection connection;
    private final GameProfile profile;
    private boolean knownPacksSent;

    public PbConfigurationHandler(ProxyServer proxyServer, Connection connection, GameProfile profile) {
        this.proxyServer = proxyServer;
        this.connection = connection;
        this.profile = profile;
    }

    @Override
    public void tick() {
        if (!knownPacksSent) {
            knownPacksSent = true;
            RegistryCache cache = proxyServer.getRegistryCache();
            if (cache.isLoaded()) {
                connection.send(new ClientboundSelectKnownPacks(cache.getKnownPacks()));
            } else {
                // Fallback: send empty known packs
                connection.send(new ClientboundSelectKnownPacks(List.of()));
            }
            LOGGER.info("Sent SelectKnownPacks to {}", profile.name());
        }
    }

    @Override
    public void handleSelectKnownPacks(ServerboundSelectKnownPacks packet) {
        LOGGER.info("Client {} responded with known packs, sending registries", profile.name());

        RegistryCache cache = proxyServer.getRegistryCache();
        if (cache.isLoaded()) {
            // Replay all cached registry + tag packets
            for (Packet<?> p : cache.getRegistryPackets()) {
                connection.send(p);
            }
        }

        connection.send(ClientboundFinishConfigurationPacket.INSTANCE);
        LOGGER.info("Sent {} registry packets and FINISH_CONFIGURATION to {}",
                cache.isLoaded() ? cache.getRegistryPackets().size() : 0, profile.name());
    }

    @Override
    public void handleConfigurationFinished(ServerboundFinishConfigurationPacket packet) {
        LOGGER.info("Client {} acknowledged FINISH_CONFIGURATION, transitioning to PLAY", profile.name());

        RegistryCache cache = proxyServer.getRegistryCache();
        RegistryAccess registryManager = cache.isLoaded()
                ? cache.getRegistryManager() : RegistryAccess.EMPTY;

        this.connection.setupOutboundProtocol(
                GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registryManager)));

        if (EUClient.PINGBYPASS_CONFIG.hasPassword()) {
            this.connection.setupInboundProtocol(
                    GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY), NO_INFINITE_MATERIALS),
                    new PbPasswordHandler(this.proxyServer, this.connection, this.profile, registryManager));
            LOGGER.info("Transitioning {} to password verification", profile.name());
        } else {
            this.connection.setupInboundProtocol(
                    GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY), NO_INFINITE_MATERIALS),
                    new PbWaitingHandler(this.proxyServer, this.connection, this.profile, registryManager));
            LOGGER.info("Transitioning {} to waiting state", profile.name());
        }
    }

    @Override
    public void handleAcceptCodeOfConduct(ServerboundAcceptCodeOfConductPacket packet) {}

    @Override public void onDisconnect(DisconnectionDetails info) {
        LOGGER.info("Client {} disconnected during configuration: {}", profile.name(), info.reason());
    }
    @Override public boolean isAcceptingMessages() { return this.connection.isConnected(); }
    @Override public void handleClientInformation(ServerboundClientInformationPacket p) {}
    @Override public void handleKeepAlive(ServerboundKeepAlivePacket p) {}
    @Override public void handlePong(ServerboundPongPacket p) {}
    @Override public void handleResourcePackResponse(ServerboundResourcePackPacket p) {}
    @Override public void handleCookieResponse(ServerboundCookieResponsePacket p) {}
    @Override public void handleCustomPayload(ServerboundCustomPayloadPacket p) {}
    @Override public void handleCustomClickAction(ServerboundCustomClickActionPacket p) {}
}
