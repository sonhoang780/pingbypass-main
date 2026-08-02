package eu.client.mixins;

import eu.client.mixins.accessors.ConnectionAccessor;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketBundlePacker;
import net.minecraft.network.PacketBundleUnpacker;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.BundlerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla's Connection.setupOutboundProtocol/setupInboundProtocol reconfigure the pipeline by
 * writing a special UnconfiguredPipelineHandler$*ConfigurationTask object through the pipeline
 * (Connection.syncAfterConfigurationChange) and relying on a handler further down to intercept
 * and consume it. Under fabric-networking-api-v1's PacketEncoder mixin wrapping in this
 * environment, nothing consumes it: it falls through 3 MessageToByteEncoder-based handlers
 * unconsumed and hits the raw Epoll channel, which rejects it
 * ("unsupported message type... expected ByteBuf") and kills the connection
 * ("Network Protocol Error") -- this is what crashed the proxy's own outbound connection to the
 * real server every time it pushed a mid-session reconfigure (resource pack / feature flag /
 * registry sync, or a deliberate anti-bot probe).
 *
 * PbSession (this repo's own Connection subclass, used for the proxy's client-facing side) has
 * carried a fix for exactly this since the original 1.21.4 PingBypass: instead of writing a
 * task through the pipeline, it directly replaces the "encoder"/"decoder" pipeline handlers
 * in-place on the channel's event loop. That completely bypasses the broken task-consumption
 * path. It was only ever wired up for the client-facing side (ProxyServer accepts real players
 * via `new PbSession()`); the proxy's own connection to the real server is a plain vanilla
 * Connection created by ConnectScreen.startConnecting(), so it never got this protection.
 *
 * This mixin applies the same direct-pipeline-swap technique to Connection itself, so every
 * connection gets it -- PbSession's own @Override still wins for PbSession instances via normal
 * virtual dispatch, this only ever runs for connections that don't override it.
 */
@Mixin(Connection.class)
public class ConnectionSafeReconfigureMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("EUClient/SafeReconfigure");

    @Inject(method = "setupOutboundProtocol", at = @At("HEAD"), cancellable = true)
    private void setupOutboundProtocol$safe(ProtocolInfo<?> protocol, CallbackInfo info) {
        Connection self = (Connection) (Object) this;
        if (protocol.flow() != self.getSending()) {
            throw new IllegalStateException("Invalid outbound protocol: " + protocol.id());
        }

        ConnectionAccessor accessor = (ConnectionAccessor) (Object) this;
        io.netty.channel.Channel channel = accessor.getChannel();
        accessor.setSendLoginDisconnect(protocol.id() == ConnectionProtocol.LOGIN);

        Runnable swap = () -> {
            try {
                ChannelPipeline p = channel.pipeline();
                if (p.get("fabric:splitter") != null) p.remove("fabric:splitter");
                PacketEncoder<?> enc = new PacketEncoder<>(protocol);
                if (p.get("encoder") != null) p.replace("encoder", "encoder", enc);
                else if (p.get("outbound_config") != null) p.replace("outbound_config", "encoder", enc);
                else p.addAfter("prepender", "encoder", enc);
                BundlerInfo bh = protocol.bundlerInfo();
                if (bh != null) {
                    PacketBundleUnpacker u = new PacketBundleUnpacker(bh);
                    if (p.get("unbundler") != null) p.replace("unbundler", "unbundler", u);
                    else p.addAfter("encoder", "unbundler", u);
                }
            } catch (Exception e) {
                LOGGER.error("[PB] Failed safe outbound transition to {}", protocol.id(), e);
            }
        };

        if (channel.eventLoop().inEventLoop()) swap.run();
        else {
            try {
                channel.eventLoop().submit(swap).sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        info.cancel();
    }

    @Inject(method = "setupInboundProtocol", at = @At("HEAD"), cancellable = true)
    private <T extends PacketListener> void setupInboundProtocol$safe(ProtocolInfo<T> protocol, T listener, CallbackInfo info) {
        Connection self = (Connection) (Object) this;
        if (protocol.flow() != self.getReceiving()) {
            throw new IllegalStateException("Invalid inbound protocol: " + protocol.id());
        }

        ConnectionAccessor accessor = (ConnectionAccessor) (Object) this;
        accessor.setPacketListener(listener);
        io.netty.channel.Channel channel = accessor.getChannel();

        Runnable swap = () -> {
            try {
                ChannelPipeline p = channel.pipeline();
                if (p.get("fabric:merger") != null) p.remove("fabric:merger");
                PacketDecoder<?> dec = new PacketDecoder<>(protocol);
                if (p.get("decoder") != null) p.replace("decoder", "decoder", dec);
                else if (p.get("inbound_config") != null) p.replace("inbound_config", "decoder", dec);
                channel.config().setAutoRead(true);
                BundlerInfo bh = protocol.bundlerInfo();
                if (bh != null) {
                    PacketBundlePacker b = new PacketBundlePacker(bh);
                    if (p.get("bundler") != null) p.replace("bundler", "bundler", b);
                    else p.addAfter("decoder", "bundler", b);
                }
            } catch (Exception e) {
                LOGGER.error("[PB] Failed safe inbound transition to {}", protocol.id(), e);
            }
        };

        if (channel.eventLoop().inEventLoop()) swap.run();
        else {
            try {
                channel.eventLoop().submit(swap).sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        info.cancel();
    }
}
