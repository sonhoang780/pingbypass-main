package eu.client.pingbypass.protocol.packets;

import eu.client.pingbypass.protocol.PbPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Server → Client: Send an error message.
 * Packet ID: 7
 * Payload: message (String)
 */
public class S2CErrorPacket extends PbPacket {
    public static final int ID = 7;

    private final String message;

    public S2CErrorPacket(String message) {
        this.message = message;
    }

    public S2CErrorPacket(FriendlyByteBuf buf) {
        this.message = buf.readUtf();
    }

    @Override
    public int getPacketId() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(message);
    }

    public String getMessage() {
        return message;
    }
}
