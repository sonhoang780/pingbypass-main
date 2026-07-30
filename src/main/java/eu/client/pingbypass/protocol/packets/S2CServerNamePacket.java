package eu.client.pingbypass.protocol.packets;

import eu.client.pingbypass.protocol.PbPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Server → Client: Inform the client which server the proxy is connected to.
 * Packet ID: 8
 * Payload: serverIp (String)
 */
public class S2CServerNamePacket extends PbPacket {
    public static final int ID = 8;

    private final String serverIp;

    public S2CServerNamePacket(String serverIp) {
        this.serverIp = serverIp;
    }

    public S2CServerNamePacket(FriendlyByteBuf buf) {
        this.serverIp = buf.readUtf();
    }

    @Override
    public int getPacketId() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(serverIp);
    }

    public String getServerIp() {
        return serverIp;
    }
}
