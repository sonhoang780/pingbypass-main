package eu.client.pingbypass.protocol.packets;

import eu.client.pingbypass.protocol.PbPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Client -> Proxy: accumulated mouse-look delta since the last tick.
 * Packet ID: 16.
 */
public class C2SInputLookPacket extends PbPacket {
    public static final int ID = 16;

    private final float deltaX;
    private final float deltaY;

    public C2SInputLookPacket(float deltaX, float deltaY) {
        this.deltaX = deltaX;
        this.deltaY = deltaY;
    }

    public C2SInputLookPacket(FriendlyByteBuf buf) {
        this.deltaX = buf.readFloat();
        this.deltaY = buf.readFloat();
    }

    @Override public int getPacketId() { return ID; }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeFloat(deltaX);
        buf.writeFloat(deltaY);
    }

    public float getDeltaX() { return deltaX; }
    public float getDeltaY() { return deltaY; }
}
