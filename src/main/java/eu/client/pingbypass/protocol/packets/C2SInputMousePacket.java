package eu.client.pingbypass.protocol.packets;

import eu.client.pingbypass.protocol.PbPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Client -> Proxy: a single raw mouse button transition (any button, matches the real
 * 3arthh4ck ClientInputService's MouseEvent forwarding). Resolved on the proxy via
 * InputConstants.Type.MOUSE.getOrCreate(button) + KeyMapping.set(...), same as keyboard.
 * Packet ID: 17.
 */
public class C2SInputMousePacket extends PbPacket {
    public static final int ID = 17;

    private final int button;
    private final int modifiers;
    private final boolean pressed;

    public C2SInputMousePacket(int button, int modifiers, boolean pressed) {
        this.button = button;
        this.modifiers = modifiers;
        this.pressed = pressed;
    }

    public C2SInputMousePacket(FriendlyByteBuf buf) {
        this.button = buf.readVarInt();
        this.modifiers = buf.readVarInt();
        this.pressed = buf.readBoolean();
    }

    @Override public int getPacketId() { return ID; }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(button);
        buf.writeVarInt(modifiers);
        buf.writeBoolean(pressed);
    }

    public int getButton() { return button; }
    public int getModifiers() { return modifiers; }
    public boolean isPressed() { return pressed; }
}
