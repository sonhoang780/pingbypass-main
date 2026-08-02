package eu.client.pingbypass.input;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.events.impl.UnfilteredKeyInputEvent;
import eu.client.events.impl.UnfilteredMouseInputEvent;
import eu.client.events.impl.UnfilteredMouseMoveEvent;
import eu.client.pingbypass.PingBypassFlags;
import eu.client.pingbypass.protocol.PbCustomPayload;
import eu.client.pingbypass.protocol.packets.C2SInputKeyPacket;
import eu.client.pingbypass.protocol.packets.C2SInputLookPacket;
import eu.client.pingbypass.protocol.packets.C2SInputMousePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * Client-side raw input capture. Forwards EVERY key/mouse transition to the proxy while
 * PingBypassFlags.rawInputForwardingActive is true -- matches the real 3arthh4ck
 * ClientInputService, which forwards all KeyboardEvent/MouseEvent unconditionally (not a
 * fixed semantic set), so the proxy's generic KeyMapping.set(...) resolves whichever binding
 * the key/button is actually mapped to (movement, hotbar slots, inventory, drop, chat, ...).
 */
public class ClientInputService {
    private final Minecraft mc = Minecraft.getInstance();
    private float accumulatedDeltaX = 0f;
    private float accumulatedDeltaY = 0f;

    @SubscribeEvent
    public void onKey(UnfilteredKeyInputEvent event) {
        if (!active()) return;
        // GLFW: 1 = PRESS, 0 = RELEASE, 2 = REPEAT (ignore repeats, no new state)
        if (event.getAction() == 2) return;
        send(new C2SInputKeyPacket(event.getKey(), event.getScancode(), event.getModifiers(), event.getAction() == 1));
    }

    @SubscribeEvent
    public void onMouseButton(UnfilteredMouseInputEvent event) {
        if (!active()) return;
        if (event.getAction() == 2) return;
        send(new C2SInputMousePacket(event.getButton(), event.getMods(), event.getAction() == 1));
    }

    @SubscribeEvent
    public void onMouseMove(UnfilteredMouseMoveEvent event) {
        if (!active()) return;
        accumulatedDeltaX += (float) event.getDeltaX();
        accumulatedDeltaY += (float) event.getDeltaY();
    }

    @SubscribeEvent
    public void onTick(PlayerUpdateEvent event) {
        flushLookDelta();
    }

    private void flushLookDelta() {
        if (!active()) return;
        if (accumulatedDeltaX == 0f && accumulatedDeltaY == 0f) return;
        send(new C2SInputLookPacket(accumulatedDeltaX, accumulatedDeltaY));
        accumulatedDeltaX = 0f;
        accumulatedDeltaY = 0f;
    }

    private boolean active() {
        return PingBypassFlags.rawInputForwardingActive
                && (EUClient.PINGBYPASS_CONFIG == null || !EUClient.PINGBYPASS_CONFIG.isServer());
    }

    private void send(eu.client.pingbypass.protocol.PbPacket packet) {
        try {
            if (mc.getConnection() == null) return;
            var payload = PbCustomPayload.fromPacket(packet);
            mc.getConnection().getConnection().send(new ServerboundCustomPayloadPacket(payload));
        } catch (Exception e) {
            EUClient.LOGGER.warn("[PingBypass] Failed to forward raw input", e);
        }
    }

    public void start() {
        EUClient.EVENT_HANDLER.subscribe(this);
        EUClient.LOGGER.info("[PingBypass] Client input service started");
    }

    public void stop() {
        EUClient.EVENT_HANDLER.unsubscribe(this);
    }
}
