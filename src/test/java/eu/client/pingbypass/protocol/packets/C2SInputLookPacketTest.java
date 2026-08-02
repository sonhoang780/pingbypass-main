package eu.client.pingbypass.protocol.packets;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class C2SInputLookPacketTest {
    @Test
    void roundTrip_preservesDeltas() {
        C2SInputLookPacket original = new C2SInputLookPacket(1.5f, -2.25f);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buf);

        C2SInputLookPacket decoded = new C2SInputLookPacket(buf);

        assertEquals(1.5f, decoded.getDeltaX(), 0.0001f);
        assertEquals(-2.25f, decoded.getDeltaY(), 0.0001f);
        assertEquals(16, decoded.getPacketId());
    }
}
