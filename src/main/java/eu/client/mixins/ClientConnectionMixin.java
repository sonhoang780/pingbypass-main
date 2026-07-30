package eu.client.mixins;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import eu.client.EUClient;
import eu.client.events.impl.ClientDisconnectEvent;
import eu.client.events.impl.PacketReceiveEvent;
import eu.client.events.impl.PacketSendEvent;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public class ClientConnectionMixin {

    // PORT: PacketSendListener (Yarn) is no longer the send() callback param type; Connection.send
    // now funnels every overload into send(Packet, ChannelFutureListener, boolean) which we hook here.
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"), cancellable = true)
    private void send$HEAD(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush, CallbackInfo info) {
        PacketSendEvent event = new PacketSendEvent(packet);
        EUClient.EVENT_HANDLER.post(event);
        if (event.isCancelled()) info.cancel();
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("TAIL"), cancellable = true)
    private void send$TAIL(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush, CallbackInfo info) {
        EUClient.EVENT_HANDLER.post(new PacketSendEvent.Post(packet));
    }

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void channelRead0(ChannelHandlerContext channelHandlerContext, Packet<?> packet, CallbackInfo info) {
        PacketReceiveEvent event = new PacketReceiveEvent(packet, (Connection)(Object)this);
        EUClient.EVENT_HANDLER.post(event);
        if (packet instanceof ClientboundBundlePacket bundleS2CPacket) {
            for (Packet<?> subPacket : bundleS2CPacket.subPackets()) {
                EUClient.EVENT_HANDLER.post(new PacketReceiveEvent(subPacket, (Connection)(Object)this));
            }
        }
        if (event.isCancelled()) info.cancel();
    }

    @Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"))
    private void disconnect(DisconnectionDetails disconnectionInfo, CallbackInfo info) {
        EUClient.EVENT_HANDLER.post(new ClientDisconnectEvent());
    }

}
