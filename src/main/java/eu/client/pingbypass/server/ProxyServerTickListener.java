package eu.client.pingbypass.server;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PacketSendEvent;
import eu.client.events.impl.TickEvent;
import eu.client.pingbypass.PingBypassFlags;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;

/**
 * Subscribes to the EUClient TickEvent and forwards ticks to the ProxyServer.
 * This ensures queued packets on proxy connections are processed each client tick.
 * Also suppresses duplicate packets that the proxy's own tick loop would send.
 */
public class ProxyServerTickListener {
    private final ProxyServer proxyServer;

    public ProxyServerTickListener(ProxyServer proxyServer) {
        this.proxyServer = proxyServer;
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        EUClient.PB_SERVER_INPUT.tick();
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

    @SubscribeEvent
    public void onPacketSend(PacketSendEvent event) {
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
                || packet instanceof net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket) {
            event.setCancelled(true);
        }
    }
}
