package eu.client.pingbypass.protocol.packets;

import eu.client.pingbypass.protocol.PbPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Client → Server: Send password for authentication.
 * Packet ID: 2
 * Payload: password (String)
 */
public class C2SPasswordPacket extends PbPacket {
    public static final int ID = 2;

    private final String password;

    public C2SPasswordPacket(String password) {
        this.password = password;
    }

    public C2SPasswordPacket(FriendlyByteBuf buf) {
        this.password = buf.readUtf();
    }

    @Override
    public int getPacketId() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(password);
    }

    public String getPassword() {
        return password;
    }
}
