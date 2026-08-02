package eu.client.pingbypass.protocol.packets;

import eu.client.pingbypass.protocol.PbPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Client -> Proxy: a single raw keyboard key transition (any key, not a fixed semantic set --
 * matches the real 3arthh4ck ClientInputService, which forwards every KeyboardEvent
 * unconditionally). The proxy resolves this to whichever KeyMapping(s) are actually bound to
 * it via InputConstants.getKey(...) + KeyMapping.set(...), so hotbar slots, inventory, drop,
 * chat, sprint-toggle etc. all work generically without a hardcoded enum.
 * Packet ID: 15.
 */
public class C2SInputKeyPacket extends PbPacket {
    public static final int ID = 15;

    private final int key;
    private final int scancode;
    private final int modifiers;
    private final boolean pressed;

    public C2SInputKeyPacket(int key, int scancode, int modifiers, boolean pressed) {
        this.key = key;
        this.scancode = scancode;
        this.modifiers = modifiers;
        this.pressed = pressed;
    }

    public C2SInputKeyPacket(FriendlyByteBuf buf) {
        this.key = buf.readVarInt();
        this.scancode = buf.readVarInt();
        this.modifiers = buf.readVarInt();
        this.pressed = buf.readBoolean();
    }

    @Override public int getPacketId() { return ID; }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(key);
        buf.writeVarInt(scancode);
        buf.writeVarInt(modifiers);
        buf.writeBoolean(pressed);
    }

    public int getKey() { return key; }
    public int getScancode() { return scancode; }
    public int getModifiers() { return modifiers; }
    public boolean isPressed() { return pressed; }
}
