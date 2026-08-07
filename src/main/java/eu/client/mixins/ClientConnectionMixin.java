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
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("EUClient/Disconnect");

    // PORT: PacketSendListener (Yarn) is no longer the send() callback param type; Connection.send
    // now funnels every overload into send(Packet, ChannelFutureListener, boolean) which we hook here.
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"), cancellable = true)
    private void send$HEAD(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush, CallbackInfo info) {
        // Diagnostic: a ClientIntentionPacket (handshake) has been observed getting sent on an
        // already-PLAY-state connection, crashing it with "Sending unknown packet
        // 'serverbound/minecraft:intention'". No code here should ever construct/send one
        // directly -- log the call stack so the real call site shows up next time it happens.
        if (packet instanceof net.minecraft.network.protocol.handshake.ClientIntentionPacket) {
            Connection self = (Connection) (Object) this;
            net.minecraft.network.PacketListener packetListener = self.getPacketListener();
            boolean genuinelyPastHandshake = packetListener != null
                    && packetListener.protocol() != net.minecraft.network.ConnectionProtocol.HANDSHAKING;
            if (genuinelyPastHandshake) {
                LOGGER.error("[PB] Caught an outgoing ClientIntentionPacket on {} -- this connection is already past handshake, this send will crash it",
                        self.getLoggableAddress(false), new Throwable("call site"));
            }
        }

        PacketSendEvent event = new PacketSendEvent(packet);
        EUClient.EVENT_HANDLER.post(event);
        if (event.isCancelled()) info.cancel();
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("TAIL"), cancellable = true)
    private void send$TAIL(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush, CallbackInfo info) {
        EUClient.EVENT_HANDLER.post(new PacketSendEvent.Post(packet));
    }

    private static final long PROTOCOL_TRANSITION_WINDOW_MS = 1000L;

    // The crash's actual stack goes through doSendPacket (called via the queued
    // sendPacket() path, e.g. from a different thread than the one that queued it),
    // NOT through send() above -- that hook never actually caught this packet.
    @Inject(method = "doSendPacket", at = @At("HEAD"), cancellable = true)
    private void doSendPacket$HEAD(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush, CallbackInfo info) {
        boolean inTransitionWindow = System.currentTimeMillis() - eu.client.pingbypass.ProtocolTransitionTracker.lastTransitionAt() < PROTOCOL_TRANSITION_WINDOW_MS;
        // Was a hardcoded 2-item blacklist (ClientIntentionPacket, ServerboundClientTickEndPacket)
        // -- but ANY PLAY-only packet queued in the same window races the same way (seen crash
        // with ServerboundCommandSuggestionPacket too, and there's no reason to believe those two
        // are the only callers that can still have something queued right as a reconfigure starts).
        // PLAY packets live exclusively under .protocol.game (and handshake's ClientIntentionPacket
        // under .protocol.handshake); CONFIGURATION-phase traffic lives under .protocol.configuration
        // plus the shared .protocol.common packets used by BOTH protocols -- so filtering by package
        // catches every PLAY-assuming packet our own code can produce without ever touching the
        // legitimate reconfigure traffic that needs to go through during this same window.
        String pkg = packet.getClass().getPackageName();
        boolean isPlayOnlyPacket = pkg.equals("net.minecraft.network.protocol.game")
                || pkg.equals("net.minecraft.network.protocol.handshake");

        if (inTransitionWindow && isPlayOnlyPacket) {
            LOGGER.warn("[PB] Dropped {} queued right as the connection was mid protocol-transition -- would have crashed the session",
                    packet.getClass().getSimpleName());
            info.cancel();
            return;
        }

        if (packet instanceof net.minecraft.network.protocol.handshake.ClientIntentionPacket) {
            // A ClientIntentionPacket is also the legitimate FIRST-EVER packet on any brand new
            // connection (e.g. the server-list ping connections used to fetch MOTD/ping for each
            // configured target) -- that's not a bug, don't cry wolf for it. Only genuinely
            // suspicious when the connection already has a listener bound past HANDSHAKING.
            Connection self = (Connection) (Object) this;
            net.minecraft.network.PacketListener packetListener = self.getPacketListener();
            boolean genuinelyPastHandshake = packetListener != null
                    && packetListener.protocol() != net.minecraft.network.ConnectionProtocol.HANDSHAKING;
            if (genuinelyPastHandshake) {
                // Log-only, do NOT cancel: briefly tried cancelling here (matches earthhack's
                // principle -- this packet is never legitimate on our own PB connections at this
                // point) but this mixin fires for EVERY Connection in the JVM, not just PB's own.
                // Other mods doing their own protocol-version negotiation over the same vanilla
                // Connection/ClientIntentionPacket path (e.g. ViaFabricPlus retrying a handshake
                // with a different protocol version on what it still considers a live connection)
                // got silently blocked by the cancel, which broke server pinging entirely for
                // every server, PingBypass or not. Back to just recording the call site.
                LOGGER.error("[PB] Caught an outgoing ClientIntentionPacket in doSendPacket on {} -- this connection is already past handshake, this send will crash it",
                        self.getLoggableAddress(false), new Throwable("call site"));
                return;
            }
        }
    }

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void channelRead0(ChannelHandlerContext channelHandlerContext, Packet<?> packet, CallbackInfo info) {
        // Previously cancelled ClientboundStartConfigurationPacket here entirely on the proxy's
        // own server connection, because processing it crashed via Connection.setupOutboundProtocol
        // writing an UnconfiguredPipelineHandler task through the pipeline that fabric-networking-api-v1
        // leaves unconsumed. ConnectionSafeReconfigureMixin now overrides setupOutboundProtocol/
        // setupInboundProtocol on Connection generally to do a direct pipeline-handler swap instead
        // (the same technique PbSession already used for the client-facing side) -- the reconfigure
        // actually works now, so it no longer needs to be skipped.
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
        Connection self = (Connection) (Object) this;
        String reason = disconnectionInfo.reason() != null ? disconnectionInfo.reason().getString() : "unknown";

        boolean isServerLink = EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer()
                && EUClient.PROXY_SERVER != null && self == EUClient.PROXY_SERVER.getServerConnection();
        if (isServerLink) {
            LOGGER.warn("[PB] Proxy's connection to the real server dropped: {}", reason);
            // FakePlayer's spawned entity lives only in mc.level, which gets torn down along
            // with this connection -- if it's left toggled on, the next join reconnects with
            // the module's toggle state still "true" but no actual entity spawned (state and
            // reality diverge), requiring a manual .fp off+on to fix. Reset it here so the
            // next join starts clean.
            var fakePlayer = EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.miscellaneous.FakePlayerModule.class);
            if (fakePlayer != null && fakePlayer.isToggled()) {
                fakePlayer.setToggled(false);
            }
        } else {
            LOGGER.info("Connection disconnected ({}): {}", self.getLoggableAddress(false), reason);
        }

        EUClient.EVENT_HANDLER.post(new ClientDisconnectEvent());
    }

}
