package eu.client.pingbypass.protocol.packets;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class C2SInputKeyPacketTest {
    @Test
    void roundTrip_preservesRawKeyData() {
        C2SInputKeyPacket original = new C2SInputKeyPacket(65, 30, 0, true);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buf);

        C2SInputKeyPacket decoded = new C2SInputKeyPacket(buf);

        assertEquals(65, decoded.getKey());
        assertEquals(30, decoded.getScancode());
        assertEquals(0, decoded.getModifiers());
        assertTrue(decoded.isPressed());
        assertEquals(15, decoded.getPacketId());
    }

    @Test
    void roundTrip_releaseState() {
        C2SInputKeyPacket original = new C2SInputKeyPacket(32, 57, 1, false);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buf);

        C2SInputKeyPacket decoded = new C2SInputKeyPacket(buf);

        assertEquals(32, decoded.getKey());
        assertFalse(decoded.isPressed());
    }
}
