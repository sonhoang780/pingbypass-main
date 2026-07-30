package eu.client.pingbypass.server;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import eu.client.pingbypass.PingBypassConfig;
import eu.client.pingbypass.modules.ProxyModuleManager;
import eu.client.pingbypass.protocol.PbProtocolHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Netty-based proxy server that speaks the Minecraft protocol.
 * Accepts client connections and manages the pipeline using vanilla codec classes.
 */
public class ProxyServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyServer.class);

    private static final Lazy<NioEventLoopGroup> SERVER_NIO_EVENTLOOP = new Lazy<>(() ->
            new NioEventLoopGroup(0, new ThreadFactoryBuilder()
                    .setNameFormat("PingBypass Server IO #%d")
                    .setDaemon(true)
                    .build()));

    private static final Lazy<EpollEventLoopGroup> SERVER_EPOLL_EVENTLOOP = new Lazy<>(() ->
            new EpollEventLoopGroup(0, new ThreadFactoryBuilder()
                    .setNameFormat("PingBypass Epoll Server IO #%d")
                    .setDaemon(true)
                    .build()));

    private final List<ChannelFuture> endpoints = Collections.synchronizedList(Lists.newArrayList());
    private final List<Connection> connections = Collections.synchronizedList(Lists.newArrayList());
    private final PingBypassConfig config;
    private final PbProtocolHandler protocolHandler;
    private final ProxyModuleManager moduleManager;
    private volatile boolean alive;
    private volatile boolean stayConnected;
    private volatile Connection serverConnection;
    private final RegistryCache registryCache;

    public ProxyServer(PingBypassConfig config) {
        this.config = config;
        this.alive = true;
        this.protocolHandler = new PbProtocolHandler();
        this.moduleManager = new ProxyModuleManager();
        this.registryCache = new RegistryCache();
        this.protocolHandler.registerStayHandler(this);
    }

    /**
     * Initializes the proxy module manager and registers module protocol handlers.
     * Must be called after {@code EUClient.MODULE_MANAGER} is initialized, since
     * the proxy reuses the real module instances from the client's ModuleManager.
     */
    public void initModules() {
        this.moduleManager.init();
        this.protocolHandler.registerModuleHandlers(this.moduleManager);
    }

    /**
     * Binds the proxy server to the given address and port using Netty ServerBootstrap.
     * Uses Epoll transport on Linux when available, NIO fallback otherwise.
     */
    public void bind(InetAddress address, int port) throws IOException {
        LOGGER.info("PingBypass proxy binding to {}:{}", address, port);
        synchronized (this.endpoints) {
            Class<? extends ServerChannel> channelClass;
            EventLoopGroup eventLoopGroup;

            if (Epoll.isAvailable()) {
                channelClass = EpollServerSocketChannel.class;
                eventLoopGroup = SERVER_EPOLL_EVENTLOOP.get();
                LOGGER.info("Using epoll channel type");
            } else {
                channelClass = NioServerSocketChannel.class;
                eventLoopGroup = SERVER_NIO_EVENTLOOP.get();
                LOGGER.info("Using default NIO channel type");
            }

            this.endpoints.add(new ServerBootstrap()
                    .channel(channelClass)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel channel) {
                            try {
                                channel.config().setOption(ChannelOption.TCP_NODELAY, Boolean.TRUE);
                            } catch (ChannelException ignored) {
                            }

                            ChannelPipeline pipeline = channel.pipeline()
                                    .addLast("timeout", new ReadTimeoutHandler(30));

                            // Add the Minecraft codec chain: splitter → decoder → prepender → encoder
                            Connection.configureSerialization(pipeline, PacketFlow.SERVERBOUND, false, null);

                            // Create a new PbSession for this incoming connection.
                            // PbSession overrides setupOutboundProtocol/setupInboundProtocol to
                            // bypass Fabric's Lambda-based pipeline transitions.
                            Connection connection = new PbSession();
                            ProxyServer.this.connections.add(connection);

                            // Add the connection as the packet handler in the pipeline
                            connection.configurePacketHandler(pipeline);

                            // Set the initial handshake handler
                            connection.setListenerForServerboundHandshake(new eu.client.pingbypass.handler.PbHandshakeHandler(
                                    ProxyServer.this, connection));
                        }
                    })
                    .group(eventLoopGroup)
                    .localAddress(address, port)
                    .bind()
                    .syncUninterruptibly());

            LOGGER.info("PingBypass proxy is now listening on {}:{}", address, port);
        }
    }

    /**
     * Called each server tick to process queued packets on all active connections.
     */
    public void tick() {
        synchronized (this.connections) {
            Iterator<Connection> iterator = this.connections.iterator();
            while (iterator.hasNext()) {
                Connection connection = iterator.next();
                if (!connection.isConnected()) {
                    iterator.remove();
                    connection.handleDisconnection();
                } else {
                    try {
                        connection.tick();
                    } catch (Exception e) {
                        LOGGER.warn("Failed to handle packet for {}",
                                connection.getLoggableAddress(false), e);
                        connection.send(
                                new net.minecraft.network.protocol.common.ClientboundDisconnectPacket(
                                        Component.literal("Internal server error")));
                        connection.disconnect(Component.literal("Internal server error"));
                    }
                }
            }
        }
    }

    /**
     * Shuts down the proxy server, closing all channel endpoints and releasing resources.
     */
    public void shutdown() {
        this.alive = false;
        LOGGER.info("Shutting down PingBypass proxy server...");

        for (ChannelFuture endpoint : this.endpoints) {
            try {
                endpoint.channel().close().sync();
            } catch (InterruptedException e) {
                LOGGER.error("Interrupted whilst closing channel");
            }
        }

        this.endpoints.clear();

        synchronized (this.connections) {
            for (Connection connection : this.connections) {
                connection.disconnect(Component.literal("Proxy server shutting down"));
            }
            this.connections.clear();
        }

        LOGGER.info("PingBypass proxy server shut down.");
    }

    public boolean isAlive() {
        return alive;
    }

    public List<Connection> getConnections() {
        return connections;
    }

    public List<ChannelFuture> getEndpoints() {
        return endpoints;
    }

    public PingBypassConfig getConfig() {
        return config;
    }

    public PbProtocolHandler getProtocolHandler() {
        return protocolHandler;
    }

    public ProxyModuleManager getModuleManager() {
        return moduleManager;
    }

    public boolean isStayConnected() {
        return stayConnected;
    }

    public void setStayConnected(boolean stayConnected) {
        this.stayConnected = stayConnected;
    }

    public Connection getServerConnection() {
        return serverConnection;
    }

    public void setServerConnection(Connection serverConnection) {
        this.serverConnection = serverConnection;
    }

    public RegistryCache getRegistryCache() {
        return registryCache;
    }

    /**
     * Simple lazy initializer for event loop groups.
     */
    private static class Lazy<T> {
        private final java.util.function.Supplier<T> supplier;
        private volatile T value;

        Lazy(java.util.function.Supplier<T> supplier) {
            this.supplier = supplier;
        }

        T get() {
            if (value == null) {
                synchronized (this) {
                    if (value == null) {
                        value = supplier.get();
                    }
                }
            }
            return value;
        }
    }
}
