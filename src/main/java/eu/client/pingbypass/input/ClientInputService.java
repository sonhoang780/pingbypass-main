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
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * Client-side raw input capture. When PingBypassFlags.rawInputForwardingActive is true,
 * every semantic key transition and accumulated mouse-look delta is forwarded to the proxy
 * instead of letting the local LocalPlayer act on it directly (see ClientPlayerEntityMixin
 * for the corresponding local-action cancellation). Reuses the existing
 * UnfilteredKeyInputEvent/UnfilteredMouseInputEvent pipeline (KeyboardMixin/MouseMixin)
 * rather than adding new accessor mixins.
 */
public class ClientInputService {
    private final Minecraft mc = Minecraft.getInstance();
    private float accumulatedDeltaX = 0f;
    private float accumulatedDeltaY = 0f;

    @SubscribeEvent
    public void onKey(UnfilteredKeyInputEvent event) {
        if (!active()) return;
        C2SInputKeyPacket.Key key = mapKey(event.getKey(), event.getScancode(), event.getModifiers());
        if (key == null) return;
        // GLFW: 1 = PRESS, 0 = RELEASE, 2 = REPEAT (ignore repeats, no new state)
        if (event.getAction() == 2) return;
        send(new C2SInputKeyPacket(key, event.getAction() == 1 ? C2SInputKeyPacket.Action.PRESS : C2SInputKeyPacket.Action.RELEASE));
    }

    @SubscribeEvent
    public void onMouseButton(UnfilteredMouseInputEvent event) {
        if (!active()) return;
        C2SInputKeyPacket.Key key = switch (event.getButton()) {
            case 0 -> C2SInputKeyPacket.Key.ATTACK;
            case 1 -> C2SInputKeyPacket.Key.USE;
            default -> null;
        };
        if (key == null || event.getAction() == 2) return;
        send(new C2SInputKeyPacket(key, event.getAction() == 1 ? C2SInputKeyPacket.Action.PRESS : C2SInputKeyPacket.Action.RELEASE));
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

    private C2SInputKeyPacket.Key mapKey(int key, int scancode, int modifiers) {
        var options = mc.options;
        var event = new net.minecraft.client.input.KeyEvent(key, scancode, modifiers);
        if (options.keyUp.matches(event)) return C2SInputKeyPacket.Key.FORWARD;
        if (options.keyDown.matches(event)) return C2SInputKeyPacket.Key.BACK;
        if (options.keyLeft.matches(event)) return C2SInputKeyPacket.Key.LEFT;
        if (options.keyRight.matches(event)) return C2SInputKeyPacket.Key.RIGHT;
        if (options.keyJump.matches(event)) return C2SInputKeyPacket.Key.JUMP;
        if (options.keyShift.matches(event)) return C2SInputKeyPacket.Key.SNEAK;
        if (options.keySprint.matches(event)) return C2SInputKeyPacket.Key.SPRINT;
        return null;
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
