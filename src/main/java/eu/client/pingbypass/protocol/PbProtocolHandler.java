package eu.client.pingbypass.protocol;

import eu.client.pingbypass.protocol.packets.*;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Registry and dispatcher for the PingBypass custom packet protocol.
 * Maps packet IDs to factories (for deserialization) and handlers (for processing).
 * Packets are read from the euclient:pingbypass plugin channel.
 */
public class PbProtocolHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PbProtocolHandler.class);

    private final Map<Integer, Function<FriendlyByteBuf, PbPacket>> factories = new HashMap<>();
    private final Map<Integer, PbPacketHandler<?>> handlers = new HashMap<>();

    public PbProtocolHandler() {
        registerFactories();
    }

    /**
     * Registers a packet factory and handler for the given packet ID.
     */
    @SuppressWarnings("unchecked")
    public <T extends PbPacket> void register(int packetId, Function<FriendlyByteBuf, T> factory, PbPacketHandler<T> handler) {
        factories.put(packetId, (Function<FriendlyByteBuf, PbPacket>) (Function<?, ?>) factory);
        handlers.put(packetId, handler);
    }

    /**
     * Registers only a packet factory (no handler yet).
     * Handlers are registered later when the actual logic is wired up.
     */
    public <T extends PbPacket> void registerFactory(int packetId, Function<FriendlyByteBuf, T> factory) {
        factories.put(packetId, (Function<FriendlyByteBuf, PbPacket>) (Function<?, ?>) factory);
    }

    /**
     * Registers a handler for an already-registered packet ID.
     */
    public <T extends PbPacket> void registerHandler(int packetId, PbPacketHandler<T> handler) {
        handlers.put(packetId, handler);
    }

    /**
     * Reads a VarInt packet ID from the buffer, constructs the packet via the
     * registered factory, and dispatches it to the registered handler.
     * Malformed or unknown packets are logged as warnings without crashing.
     */
    @SuppressWarnings("unchecked")
    public void handle(FriendlyByteBuf buf, Connection connection) {
        int packetId;
        try {
            packetId = buf.readVarInt();
        } catch (Exception e) {
            LOGGER.warn("[PbProtocol] Failed to read packet ID from buffer", e);
            return;
        }

        Function<FriendlyByteBuf, PbPacket> factory = factories.get(packetId);
        if (factory == null) {
            LOGGER.warn("[PbProtocol] Unknown packet ID: {}", packetId);
            return;
        }

        PbPacket packet;
        try {
            packet = factory.apply(buf);
        } catch (Exception e) {
            LOGGER.warn("[PbProtocol] Failed to deserialize packet ID {}", packetId, e);
            return;
        }

        PbPacketHandler<PbPacket> handler = (PbPacketHandler<PbPacket>) handlers.get(packetId);
        if (handler == null) {
            LOGGER.warn("[PbProtocol] No handler registered for packet ID: {}", packetId);
            return;
        }

        try {
            handler.handle(packet, connection);
        } catch (Exception e) {
            LOGGER.warn("[PbProtocol] Error handling packet ID {}", packetId, e);
        }
    }

    /**
     * Registers factories for all 10 packet types so they can be deserialized.
     * Actual handlers are registered later by Tasks 12.1 and 12.2.
     * C2S_STAY handler is registered here as it's handled in Task 10.1.
     */
    private void registerFactories() {
        registerFactory(C2SJoinPacket.ID, C2SJoinPacket::new);
        registerFactory(S2CPasswordRequestPacket.ID, S2CPasswordRequestPacket::new);
        registerFactory(C2SPasswordPacket.ID, C2SPasswordPacket::new);
        registerFactory(C2SModuleTogglePacket.ID, C2SModuleTogglePacket::new);
        registerFactory(C2SSettingChangePacket.ID, C2SSettingChangePacket::new);
        registerFactory(S2CModuleStatePacket.ID, S2CModuleStatePacket::new);
        registerFactory(S2CSettingStatePacket.ID, S2CSettingStatePacket::new);
        registerFactory(S2CErrorPacket.ID, S2CErrorPacket::new);
        registerFactory(S2CServerNamePacket.ID, S2CServerNamePacket::new);
        registerFactory(C2SStayPacket.ID, C2SStayPacket::new);
    }

    /**
     * Registers the C2S_STAY handler that toggles the stayConnected flag on the ProxyServer.
     * Called during proxy initialization when the ProxyServer instance is available.
     */
    public void registerStayHandler(eu.client.pingbypass.server.ProxyServer proxyServer) {
        register(C2SStayPacket.ID, C2SStayPacket::new, (packet, connection) -> {
            proxyServer.setStayConnected(packet.isStay());
            LOGGER.info("[PbProtocol] Stay Connected set to {} via protocol handler", packet.isStay());
        });
    }

}
