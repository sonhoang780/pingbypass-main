package eu.client.pingbypass.server;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PacketReceiveEvent;
import eu.client.events.impl.PacketSendEvent;
import eu.client.events.impl.TickEvent;
import eu.client.pingbypass.PingBypassFlags;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Subscribes to the EUClient TickEvent and forwards ticks to the ProxyServer.
 * This ensures queued packets on proxy connections are processed each client tick.
 * Also suppresses duplicate packets that the proxy's own tick loop would send.
 */
public class ProxyServerTickListener {
    private static final Logger LOGGER = LoggerFactory.getLogger("EUClient/Rubberband");
    private final ProxyServer proxyServer;

    public ProxyServerTickListener(ProxyServer proxyServer) {
        this.proxyServer = proxyServer;
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        EUClient.PB_MODULE_MANAGER.tick();
        if (proxyServer.isAlive()) {
            proxyServer.tick();
        }
    }

    // ThreadLocal flag: when true, packets are allowed through (module-authorized)
    private static final ThreadLocal<Boolean> ALLOW_SEND = ThreadLocal.withInitial(() -> false);

    /** Call this to temporarily allow packets through the filter (for module use) */
    public static void allowSend(Runnable action) {
        ALLOW_SEND.set(true);
        try {
            action.run();
        } finally {
            ALLOW_SEND.set(false);
        }
    }

    // Diagnostic for the rubberband investigation (see SESSION_2026-08-05.md): logs both sides of
    // the teleport handshake so a live rubberband can be correlated against what actually got
    // sent/received around it. Cheap (teleports are rare), remove once rubberband is confirmed gone.
    @SubscribeEvent
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!PingBypassFlags.proxyForwardingActive) return;
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket p) {
            LOGGER.info("[PB] Real server sent teleport/correction id={} pos={} rel={} onGround(from client, stale on ghost until next move)={}",
                    p.id(), p.change().position(), p.relatives(), PingBypassFlags.clientOnGround);
        }
    }

    @SubscribeEvent
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacket() instanceof ServerboundAcceptTeleportationPacket p) {
            // allowSend=true means this is the REAL client's own confirm, forwarded by
            // PbPlayHandler.handleAcceptTeleportPacket -- the intended path. allowSend=false means
            // this is the ghost's own auto-confirm (fired the instant LocalPlayer applies the
            // teleport, before the real client even saw it) and gets cancelled right below.
            LOGGER.info("[PB] AcceptTeleportation id={} isRealClientConfirm(allowSend)={}",
                    p.getId(), ALLOW_SEND.get());
        }
        if (!PingBypassFlags.proxyForwardingActive) return;
        if (ALLOW_SEND.get()) return; // Module-authorized, let through

        // Block specific known-duplicate packet types that the proxy's own
        // tick loop sends automatically. The client's versions are forwarded
        // by PbPlayHandler.
        var packet = event.getPacket();
        if (packet instanceof ServerboundClientTickEndPacket
                || packet instanceof net.minecraft.network.protocol.game.ServerboundPlayerInputPacket
                || packet instanceof net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket
                || packet instanceof net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket
                || packet instanceof net.minecraft.network.protocol.common.ServerboundClientInformationPacket
                || packet instanceof net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket
                || packet instanceof net.minecraft.network.protocol.game.ServerboundContainerClosePacket
                || packet instanceof net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
                // Matches earthhack's Pb2SManager blacklist (CPacketPlayer*/CPacketConfirmTeleport
                // blocked for the ghost unless explicitly authorized). Without these, the ghost's
                // own LocalPlayer.tick()->sendPosition() fires an EXTRA movement packet to the real
                // server every tick on top of the explicit one PbPlayHandler.handleMovePlayer0
                // already sends (now wrapped in allowSend), and the ghost's own teleport-confirm
                // (fired the instant it applies a teleport, before the real client has even seen
                // it) beat the real client's confirm to the server -- see handleAcceptTeleportPacket.
                || packet instanceof net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
                || packet instanceof net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket
                || packet instanceof net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket) {
            event.setCancelled(true);
        }
    }
}
