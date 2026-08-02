package eu.client.pingbypass.protocol.packets;

import eu.client.pingbypass.protocol.PbPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Client -> Proxy: a single raw key/mouse-button transition (not a continuous state poll).
 * Packet ID: 15 (10=S2CRenderPosition, 11=S2CBlockRender, 12=S2CMiningState,
 * 13=S2CSlotSync, 14=C2SFriendSync are already taken).
 */
public class C2SInputKeyPacket extends PbPacket {
    public static final int ID = 15;

    public enum Key { FORWARD, BACK, LEFT, RIGHT, JUMP, SNEAK, SPRINT, ATTACK, USE }
    public enum Action { PRESS, RELEASE }

    private final Key key;
    private final Action action;

    public C2SInputKeyPacket(Key key, Action action) {
        this.key = key;
        this.action = action;
    }

    public C2SInputKeyPacket(FriendlyByteBuf buf) {
        this.key = buf.readEnum(Key.class);
        this.action = buf.readEnum(Action.class);
    }

    @Override public int getPacketId() { return ID; }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeEnum(key);
        buf.writeEnum(action);
    }

    public Key getKey() { return key; }
    public Action getAction() { return action; }
}
