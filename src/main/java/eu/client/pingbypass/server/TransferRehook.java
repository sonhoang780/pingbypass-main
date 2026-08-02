package eu.client.pingbypass.server;

import com.mojang.authlib.GameProfile;
import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.pingbypass.handler.PbPlayHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.GameProtocols;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects when the proxy's own connection to the real server gets replaced by an entirely
 * new Connection object -- happens on a native ClientboundTransferPacket (backend
 * load-balancing; e.g. 6b6t moving players between "worker" nodes) since
 * ClientCommonPacketListenerImpl.handleTransfer() disconnects the old Connection and opens a
 * fresh one via ConnectScreen.startConnecting(), entirely inside vanilla code we never see a
 * callback for.
 *
 * Nothing else in the codebase knew about that swap: EUClient.PROXY_SERVER's tracked
 * serverConnection, every connected client's S2CForwarder, and every client's PbPlayHandler
 * all kept referencing the OLD, now-disconnected Connection. No further real-server traffic
 * ever reached a connected client again after a transfer -- it would just silently stop
 * responding and eventually time out ("End of stream", no error logged), which is exactly
 * what showed up as random kicks right after a "you're now playing on ..." backend-switch
 * chat message.
 */
public class TransferRehook {
    private static final Logger LOGGER = LoggerFactory.getLogger(TransferRehook.class);

    public TransferRehook() {
        EUClient.EVENT_HANDLER.subscribe(this);
    }

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (EUClient.PINGBYPASS_CONFIG == null || !EUClient.PINGBYPASS_CONFIG.isServer()) return;
        if (!eu.client.pingbypass.PingBypassFlags.proxyForwardingActive) return;
        if (EUClient.PROXY_SERVER == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || mc.player == null || mc.level == null) return;

        Connection current = mc.getConnection().getConnection();
        Connection tracked = EUClient.PROXY_SERVER.getServerConnection();
        if (current == tracked || !current.isConnected()) return;

        LOGGER.warn("[PB] Proxy's own server connection changed underneath us (transfer/backend switch) -- rewiring {} connected client(s)",
                EUClient.PROXY_SERVER.getConnections().size());

        EUClient.PROXY_SERVER.setServerConnection(current);

        RegistryAccess registry = mc.level.registryAccess();

        for (Connection clientConnection : EUClient.PROXY_SERVER.getConnections()) {
            if (!clientConnection.isConnected()) continue;
            if (!(clientConnection.getPacketListener() instanceof PbPlayHandler oldHandler)) continue;

            GameProfile profile = oldHandler.getProfile();

            try {
                clientConnection.setupOutboundProtocol(
                        GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registry)));

                S2CForwarder s2cForwarder = new S2CForwarder(clientConnection);
                s2cForwarder.start();

                int initialTeleportId = WorldStateReplay.replay(clientConnection, registry);

                PbPlayHandler newHandler = new PbPlayHandler(EUClient.PROXY_SERVER, clientConnection, profile, s2cForwarder, initialTeleportId);
                clientConnection.setupInboundProtocol(
                        GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registry), () -> false),
                        newHandler);

                LOGGER.info("[PB] Rewired {} onto the new server connection after transfer", profile.name());
            } catch (Exception e) {
                LOGGER.error("[PB] Failed to rewire {} after transfer", profile.name(), e);
            }
        }
    }
}
