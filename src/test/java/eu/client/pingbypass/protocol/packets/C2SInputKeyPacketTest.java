package eu.client.pingbypass.protocol.packets;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class C2SInputKeyPacketTest {
    @Test
    void roundTrip_preservesKeyAndAction() {
        C2SInputKeyPacket original = new C2SInputKeyPacket(C2SInputKeyPacket.Key.FORWARD, C2SInputKeyPacket.Action.PRESS);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buf);

        C2SInputKeyPacket decoded = new C2SInputKeyPacket(buf);

        assertEquals(C2SInputKeyPacket.Key.FORWARD, decoded.getKey());
        assertEquals(C2SInputKeyPacket.Action.PRESS, decoded.getAction());
        assertEquals(15, decoded.getPacketId());
    }

    @Test
    void roundTrip_releaseAction() {
        C2SInputKeyPacket original = new C2SInputKeyPacket(C2SInputKeyPacket.Key.ATTACK, C2SInputKeyPacket.Action.RELEASE);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buf);

        C2SInputKeyPacket decoded = new C2SInputKeyPacket(buf);

        assertEquals(C2SInputKeyPacket.Key.ATTACK, decoded.getKey());
        assertEquals(C2SInputKeyPacket.Action.RELEASE, decoded.getAction());
    }
}
