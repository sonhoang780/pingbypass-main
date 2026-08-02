package eu.client.pingbypass.server;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PacketReceiveEvent;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Forwards S2C packets from the real server to the connected client.
 * Implements a lock/unlock mechanism (like PingBypass's S2PB2CPipeline) to
 * queue packets during critical state transitions (e.g., world state replay)
 * and flush them afterward, preventing out-of-order delivery and desync.
 */
public class S2CForwarder {
    private static final Logger LOGGER = LoggerFactory.getLogger(S2CForwarder.class);

    private final Connection clientConnection;
    private final List<Packet<?>> queuedPackets = new CopyOnWriteArrayList<>();
    private final AtomicBoolean locked = new AtomicBoolean(false);
    private volatile boolean active;

    public S2CForwarder(Connection clientConnection) {
        this.clientConnection = clientConnection;
        this.active = false;
    }

    public void start() {
        this.active = true;
        eu.client.pingbypass.PingBypassFlags.proxyForwardingActive = true;
        EUClient.EVENT_HANDLER.subscribe(this);
        LOGGER.info("S2C forwarder started");
    }

    public void stop() {
        this.active = false;
        eu.client.pingbypass.PingBypassFlags.proxyForwardingActive = false;
        EUClient.EVENT_HANDLER.unsubscribe(this);
        LOGGER.info("S2C forwarder stopped");
    }

    /**
     * Locks the pipeline — incoming S2C packets will be queued instead of forwarded.
     * Use this before replaying world state to prevent interleaving.
     */
    public void lock() {
        synchronized (locked) {
            locked.set(true);
        }
    }

    /**
     * Unlocks the pipeline and flushes all queued packets to the client in order.
     * Call this after world state replay is complete.
     */
    public void unlockAndFlush() {
        synchronized (locked) {
            for (Packet<?> packet : queuedPackets) {
                sendToClient(packet);
            }
            queuedPackets.clear();
            locked.set(false);
        }
    }

    /** Forward S2C packets from real server to client */
    @SubscribeEvent
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!active || !clientConnection.isConnected()) return;

        // Only forward packets that actually came from the shared real-server connection.
        // "!= clientConnection" alone isn't enough: the proxy is multi-tenant (multiple real
        // players connect to it concurrently, log shows several players active at once), and
        // EUClient.EVENT_HANDLER is global -- every S2CForwarder instance receives
        // PacketReceiveEvent for EVERY connection's inbound traffic, including OTHER players'
        // own connections to the proxy. Those aren't "clientConnection" either, so the old
        // check let a stray ClientIntentionPacket from a DIFFERENT player's handshake get
        // treated as "from the real server" and forwarded straight into this player's already
        // PLAY-state connection, crashing it ("Sending unknown packet
        // 'serverbound/minecraft:intention'").
        Connection serverConnection = EUClient.PROXY_SERVER != null ? EUClient.PROXY_SERVER.getServerConnection() : null;
        if (serverConnection == null || event.getConnection() != serverConnection) return;

        Packet<?> packet = event.getPacket();

        // Never forward keepalive/ping — proxy handles these itself
        if (packet instanceof ClientboundKeepAlivePacket || packet instanceof ClientboundPingPacket) {
            return;
        }

        // Never forward protocol-state-changing packets. The proxy's own vanilla
        // connection (mc.getConnection()) handles these itself -- reconnecting to
        // a transferred backend, or cycling back to CONFIGURATION for a resource
        // pack/feature-flag push -- entirely transparently to the real player, who
        // stays in an uninterrupted PLAY session with the proxy the whole time.
        // Forwarding one of these onto the client's connection (already in PLAY
        // with the proxy) makes the real client try to process a state transition
        // the proxy-side fake PLAY session was never updated for, desyncing it and
        // causing it to send a fresh handshake that crashes the connection
        // ("Sending unknown packet 'serverbound/minecraft:intention'").
        if (packet instanceof ClientboundTransferPacket || packet instanceof ClientboundStartConfigurationPacket) {
            LOGGER.info("Suppressed {} from being forwarded to client (proxy handles it internally)",
                    packet.getClass().getSimpleName());
            // The proxy's own connection to the real server is about to switch protocol
            // (PLAY -> CONFIGURATION) to handle this. Anything still queued to send on that
            // same connection from the game's normal per-tick PLAY-state traffic (e.g.
            // ServerboundClientTickEndPacket, sent automatically every tick) races the switch
            // and crashes the connection outright if it loses. Give ClientConnectionMixin a
            // window to drop those instead of letting them kill the session.
            eu.client.pingbypass.ProtocolTransitionTracker.mark();
            return;
        }

        // Backstop: the packet-class blacklist above only covers the two packets that
        // TRIGGER a reconfigure. Once the proxy's own connection actually enters
        // CONFIGURATION, the real server also sends a whole run of other CONFIGURATION-only
        // packets on that same connection (update_enabled_features, select_known_packs,
        // registry_data, update_tags, finish_configuration...) before returning to PLAY.
        // clientConnection is still sitting in PLAY the whole time, so forwarding ANY of
        // those crashes its encoder the same way ("Sending unknown packet ..."). Rather than
        // blacklist every CONFIGURATION packet type by hand, just check the proxy's own
        // connection's live protocol and drop everything while it isn't in PLAY.
        var proxyListener = EUClient.PROXY_SERVER != null && EUClient.PROXY_SERVER.getServerConnection() != null
                ? EUClient.PROXY_SERVER.getServerConnection().getPacketListener() : null;
        if (proxyListener != null && proxyListener.protocol() != net.minecraft.network.ConnectionProtocol.PLAY) {
            LOGGER.info("Suppressed {} while proxy's server connection is mid-reconfigure (protocol={})",
                    packet.getClass().getSimpleName(), proxyListener.protocol());
            eu.client.pingbypass.ProtocolTransitionTracker.mark();
            return;
        }

        // Don't forward ClientboundBundlePacket as a whole — the ClientConnectionMixin
        // fires individual PacketReceiveEvent for each sub-packet inside the bundle,
        // so those will be forwarded individually. Forwarding the bundle itself would
        // cause duplicates.
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundBundlePacket) {
            return;
        }

        synchronized (locked) {
            if (locked.get()) {
                queuedPackets.add(packet);
            } else {
                sendToClient(packet);
            }
        }

        // Do NOT cancel — let the proxy process all S2C packets for full state sync.
    }

    private void sendToClient(Packet<?> packet) {
        if (clientConnection.isConnected()) {
            clientConnection.send(packet);
        }
    }
}
