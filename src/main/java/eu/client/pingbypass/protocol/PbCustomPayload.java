package eu.client.pingbypass.protocol;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;

/**
 * Custom payload for the euclient:pingbypass plugin channel.
 * Carries raw bytes that encode our custom packet protocol:
 * [packetId: VarInt][payload: bytes]
 */
public record PbCustomPayload(byte[] data) implements CustomPacketPayload {
    public static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("euclient", "pingbypass");
    public static final CustomPacketPayload.Type<PbCustomPayload> ID = new CustomPacketPayload.Type<>(CHANNEL);

    public static final int C2S_JOIN = 0;
    public static final int S2C_PASSWORD_REQUEST = 1;
    public static final int C2S_PASSWORD = 2;
    public static final int S2C_ERROR = 7;

    public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, PbCustomPayload> CODEC = new StreamCodec<>() {
        @Override
        public PbCustomPayload decode(net.minecraft.network.RegistryFriendlyByteBuf buf) {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return new PbCustomPayload(bytes);
        }

        @Override
        public void encode(net.minecraft.network.RegistryFriendlyByteBuf buf, PbCustomPayload payload) {
            buf.writeBytes(payload.data());
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public FriendlyByteBuf toBuf() {
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
    }

    public static PbCustomPayload fromPacket(PbPacket packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(packet.getPacketId());
        packet.write(buf);
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        buf.release();
        return new PbCustomPayload(bytes);
    }

    /**
     * Creates a C2S packet for sending to the proxy. PbCustomPayload is NOT registered
     * with Fabric's PayloadTypeRegistry for C2S, so this bypasses Fabric's networking
     * layer which would otherwise intercept and break the send.
     */
    public static ServerboundCustomPayloadPacket createC2SPacket(PbPacket packet) {
        return new ServerboundCustomPayloadPacket(fromPacket(packet));
    }

    public static PbCustomPayload passwordRequest() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(S2C_PASSWORD_REQUEST);
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        buf.release();
        return new PbCustomPayload(bytes);
    }

    public static PbCustomPayload error(String message) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(S2C_ERROR);
        buf.writeUtf(message);
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        buf.release();
        return new PbCustomPayload(bytes);
    }
}
