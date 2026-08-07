package eu.client.pingbypass.input;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.MouseInputEvent;
import eu.client.events.impl.TickEvent;
import eu.client.pingbypass.PingBypassFlags;
import eu.client.pingbypass.protocol.PbCustomPayload;
import eu.client.pingbypass.protocol.packets.C2SInputPacket;
import eu.client.pingbypass.protocol.packets.C2SOpenInventoryPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * Forwards mouse input from the client to the proxy server.
 * The proxy replays these inputs through its game loop, generating
 * properly sequenced packets (block placement, item use, etc.).
 */
public class ClientInputForwarder {
    // Mirrors earthhack's InventoryService "open" flag.
    private boolean inventoryOpen = false;

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (!PingBypassFlags.proxyForwardingActive) return;
        if (EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;

        boolean nowOpen = mc.screen instanceof InventoryScreen inv && inv.getMenu() == mc.player.inventoryMenu;
        if (nowOpen == inventoryOpen) return;

        inventoryOpen = nowOpen;
        try {
            var payload = PbCustomPayload.fromPacket(new C2SOpenInventoryPacket(nowOpen));
            mc.getConnection().getConnection().send(new ServerboundCustomPayloadPacket(payload));
        } catch (Exception e) {
            EUClient.LOGGER.warn("[PingBypass] Failed to send inventory-open state", e);
        }
    }

    @SubscribeEvent
    public void onMouseInput(MouseInputEvent event) {
        if (!PingBypassFlags.proxyForwardingActive) return;
        if (EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;

        int button = event.getButton();
        // Only forward attack (0) and use (1) buttons
        if (button != 0 && button != 1) return;

        // Send press event
        sendInput(mc, C2SInputPacket.TYPE_MOUSE, button, C2SInputPacket.ACTION_PRESS);
    }

    private void sendInput(Minecraft mc, int type, int button, int action) {
        try {
            var payload = PbCustomPayload.fromPacket(new C2SInputPacket(type, button, action));
            mc.getConnection().getConnection().send(new ServerboundCustomPayloadPacket(payload));
        } catch (Exception e) {
            EUClient.LOGGER.warn("[PingBypass] Failed to send input", e);
        }
    }

    public void start() {
        EUClient.EVENT_HANDLER.subscribe(this);
        EUClient.LOGGER.info("[PingBypass] Client input forwarder started");
    }

    public void stop() {
        EUClient.EVENT_HANDLER.unsubscribe(this);
    }
}
