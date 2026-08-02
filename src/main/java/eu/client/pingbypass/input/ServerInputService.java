package eu.client.pingbypass.input;

import eu.client.pingbypass.protocol.packets.C2SInputKeyPacket;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Proxy-side: replays a real client's raw input into this proxy's own LocalPlayer,
 * so the normal vanilla tick loop (movement, attack, mining) runs exactly once,
 * driven by data instead of hardware, and emits every gameplay packet itself.
 */
public class ServerInputService {
    private final Minecraft mc = Minecraft.getInstance();
    private final KeyState keyState = new KeyState();
    private float pendingYawDelta = 0f;
    private float pendingPitchDelta = 0f;

    public void onKeyTransition(C2SInputKeyPacket.Key key, C2SInputKeyPacket.Action action) {
        keyState.apply(key, action);
        applyToMapping(key);
    }

    public void onLookDelta(float deltaX, float deltaY) {
        pendingYawDelta += deltaX;
        pendingPitchDelta += deltaY;
    }

    /** Call once per proxy tick, before the vanilla player tick runs. */
    public void tick() {
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (pendingYawDelta != 0f || pendingPitchDelta != 0f) {
            player.turn(pendingYawDelta, pendingPitchDelta);
            pendingYawDelta = 0f;
            pendingPitchDelta = 0f;
        }
    }

    private void applyToMapping(C2SInputKeyPacket.Key key) {
        boolean down = keyState.isHeld(key);
        var options = mc.options;
        KeyMapping mapping = switch (key) {
            case FORWARD -> options.keyUp;
            case BACK -> options.keyDown;
            case LEFT -> options.keyLeft;
            case RIGHT -> options.keyRight;
            case JUMP -> options.keyJump;
            case SNEAK -> options.keyShift;
            case SPRINT -> options.keySprint;
            case ATTACK -> options.keyAttack;
            case USE -> options.keyUse;
        };
        mapping.setDown(down);
    }
}
