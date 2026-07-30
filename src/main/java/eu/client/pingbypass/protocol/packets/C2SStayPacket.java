package eu.client.pingbypass.protocol.packets;

import eu.client.pingbypass.protocol.PbPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Client → Server: Tell the proxy whether to stay connected when the client disconnects.
 * Packet ID: 9
 * Payload: stay (Boolean)
 */
public class C2SStayPacket extends PbPacket {
    public static final int ID = 9;

    private final boolean stay;

    public C2SStayPacket(boolean stay) {
        this.stay = stay;
    }

    public C2SStayPacket(FriendlyByteBuf buf) {
        this.stay = buf.readBoolean();
    }

    @Override
    public int getPacketId() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(stay);
    }

    public boolean isStay() {
        return stay;
    }
}
