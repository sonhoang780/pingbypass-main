package eu.client.pingbypass.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;

/**
 * Proxy-side: replays a real client's raw input into this proxy's own LocalPlayer, so the
 * normal vanilla tick loop (movement, attack, mining, hotbar switching, inventory, drop,
 * chat, ...) runs exactly once, driven by data instead of hardware, and emits every
 * gameplay packet itself. Matches the real 3arthh4ck approach of resolving ANY forwarded
 * key/button generically via KeyMapping.set(...) rather than a hardcoded semantic set --
 * every vanilla keybind works automatically, not just movement/attack/use.
 */
public class ServerInputService {
    private final Minecraft mc = Minecraft.getInstance();
    private float pendingYawDelta = 0f;
    private float pendingPitchDelta = 0f;

    public void onKeyTransition(int key, int scancode, int modifiers, boolean pressed) {
        InputConstants.Key mappedKey = InputConstants.getKey(new KeyEvent(key, scancode, modifiers));
        KeyMapping.set(mappedKey, pressed);
    }

    public void onMouseTransition(int button, int modifiers, boolean pressed) {
        InputConstants.Key mappedKey = InputConstants.Type.MOUSE.getOrCreate(button);
        KeyMapping.set(mappedKey, pressed);
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
}
