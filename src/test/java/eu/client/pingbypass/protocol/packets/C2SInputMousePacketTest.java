package eu.client.pingbypass.protocol.packets;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class C2SInputMousePacketTest {
    @Test
    void roundTrip_preservesButtonState() {
        C2SInputMousePacket original = new C2SInputMousePacket(0, 0, true);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buf);

        C2SInputMousePacket decoded = new C2SInputMousePacket(buf);

        assertEquals(0, decoded.getButton());
        assertTrue(decoded.isPressed());
        assertEquals(17, decoded.getPacketId());
    }

    @Test
    void roundTrip_releaseState() {
        C2SInputMousePacket original = new C2SInputMousePacket(1, 0, false);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buf);

        C2SInputMousePacket decoded = new C2SInputMousePacket(buf);

        assertEquals(1, decoded.getButton());
        assertFalse(decoded.isPressed());
    }
}
