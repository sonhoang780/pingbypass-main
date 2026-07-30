package eu.client.pingbypass.server;

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
import net.minecraft.network.protocol.PacketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom Connection subclass for the PingBypass proxy server.
 * Overrides setupInboundProtocol/setupOutboundProtocol to perform direct pipeline
 * manipulation instead of writing configuration messages through the pipeline,
 * avoiding conflicts with Fabric API's packet splitter/merger handlers.
 */
public class PbSession extends Connection {
    private static final Logger LOGGER = LoggerFactory.getLogger(PbSession.class);

    public PbSession() {
        super(PacketFlow.SERVERBOUND);
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T listener) {
        if (protocol.flow() != this.getReceiving()) {
            throw new IllegalStateException("Invalid inbound protocol: " + protocol.id());
        }

        ConnectionAccessor self = (ConnectionAccessor) (Object) this;
        self.setPacketListener(listener);
        io.netty.channel.Channel channel = self.getChannel();

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
                LOGGER.error("[PbSession] Failed inbound transition to {}", protocol.id(), e);
            }
        };

        if (channel.eventLoop().inEventLoop()) swap.run();
        else { try { channel.eventLoop().submit(swap).sync(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    }

    @Override
    public void setupOutboundProtocol(ProtocolInfo<?> protocol) {
        if (protocol.flow() != this.getSending()) {
            throw new IllegalStateException("Invalid outbound protocol: " + protocol.id());
        }

        ConnectionAccessor self = (ConnectionAccessor) (Object) this;
        io.netty.channel.Channel channel = self.getChannel();
        self.setSendLoginDisconnect(protocol.id() == ConnectionProtocol.LOGIN);

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
                LOGGER.error("[PbSession] Failed outbound transition to {}", protocol.id(), e);
            }
        };

        if (channel.eventLoop().inEventLoop()) swap.run();
        else { try { channel.eventLoop().submit(swap).sync(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    }
}
