package eu.client.pingbypass.input;

import org.junit.jupiter.api.Test;

import static eu.client.pingbypass.protocol.packets.C2SInputKeyPacket.*;
import static org.junit.jupiter.api.Assertions.*;

class KeyStateTest {
    @Test
    void unpressedKey_defaultsToNotHeld() {
        assertFalse(new KeyState().isHeld(Key.FORWARD));
    }

    @Test
    void press_thenRelease_returnsToNotHeld() {
        KeyState state = new KeyState();
        state.apply(Key.FORWARD, Action.PRESS);
        assertTrue(state.isHeld(Key.FORWARD));
        state.apply(Key.FORWARD, Action.RELEASE);
        assertFalse(state.isHeld(Key.FORWARD));
    }

    @Test
    void keysAreIndependent() {
        KeyState state = new KeyState();
        state.apply(Key.FORWARD, Action.PRESS);
        assertFalse(state.isHeld(Key.SPRINT));
    }
}
