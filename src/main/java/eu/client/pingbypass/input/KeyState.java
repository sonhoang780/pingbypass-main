package eu.client.pingbypass.input;

import eu.client.pingbypass.protocol.packets.C2SInputKeyPacket;

import java.util.EnumMap;
import java.util.Map;

/**
 * Tracks which semantic keys are currently held, purely as state -- no Minecraft
 * dependency, so it's unit-testable. ServerInputService applies this state to the
 * proxy's real KeyMapping objects each transition.
 */
public class KeyState {
    private final Map<C2SInputKeyPacket.Key, Boolean> held = new EnumMap<>(C2SInputKeyPacket.Key.class);

    public void apply(C2SInputKeyPacket.Key key, C2SInputKeyPacket.Action action) {
        held.put(key, action == C2SInputKeyPacket.Action.PRESS);
    }

    public boolean isHeld(C2SInputKeyPacket.Key key) {
        return held.getOrDefault(key, false);
    }
}
