package eu.client.mixins;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.ProtocolSwapHandler;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents terminal packets from triggering pipeline state transitions
 * on CLIENT-SIDE connections during PingBypass proxy connections.
 * Our transitionInbound/transitionOutbound mixins handle pipeline swaps
 * directly, so onEncoded/onDecoded are redundant on the client side.
 *
 * PORT: Yarn's net.minecraft.network.handler.NetworkStateTransitionHandler
 * (onEncoded/onDecoded) has no Mojmap equivalent class. The terminal-packet
 * pipeline-swap logic now lives in the static default methods of
 * net.minecraft.network.ProtocolSwapHandler (handleInboundTerminalPacket /
 * handleOutboundTerminalPacket), invoked directly from PacketDecoder.decode
 * and PacketEncoder.encode (verified in .mcref). Retargeted onto those.
 */
@Mixin(ProtocolSwapHandler.class)
public interface NetworkStateTransitionHandlerMixin {

    @Inject(method = "handleInboundTerminalPacket", at = @At("HEAD"), cancellable = true)
    private static void handleInboundTerminalPacket(ChannelHandlerContext context, Packet<?> packet, CallbackInfo info) {
        if (!eu.client.pingbypass.PingBypassFlags.suppressEncoderErrors) return;
        // Only suppress on client-side connections
        if (context.pipeline().get("packet_handler") instanceof Connection cc
                && cc.getReceiving() == PacketFlow.CLIENTBOUND) {
            info.cancel();
        }
    }

    @Inject(method = "handleOutboundTerminalPacket", at = @At("HEAD"), cancellable = true)
    private static void handleOutboundTerminalPacket(ChannelHandlerContext context, Packet<?> packet, CallbackInfo info) {
        if (!eu.client.pingbypass.PingBypassFlags.suppressEncoderErrors) return;
        // Only suppress on client-side connections
        if (context.pipeline().get("packet_handler") instanceof Connection cc
                && cc.getReceiving() == PacketFlow.CLIENTBOUND) {
            info.cancel();
        }
    }
}
