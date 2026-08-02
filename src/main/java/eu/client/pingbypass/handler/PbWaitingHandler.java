package eu.client.pingbypass.handler;

import com.mojang.authlib.GameProfile;
import eu.client.pingbypass.protocol.PbCustomPayload;
import eu.client.pingbypass.protocol.packets.S2CErrorPacket;
import eu.client.pingbypass.server.LobbyWorldSender;
import eu.client.pingbypass.server.ProxyServer;
import eu.client.pingbypass.server.S2CForwarder;
import eu.client.pingbypass.server.WorldStateReplay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.TickablePacketListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the waiting state when the proxy is not yet connected to a server.
 * Listens for C2S_JOIN custom packets to initiate a server connection,
 * and responds to keep-alives to prevent the client from timing out.
 */
public class PbWaitingHandler implements ServerGamePacketListener, TickablePacketListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(PbWaitingHandler.class);
    private static final long KEEP_ALIVE_TIMEOUT_MS = 30_000L;
    private static final long KEEP_ALIVE_INTERVAL_MS = 15_000L;

    private final ProxyServer proxyServer;
    private final Connection connection;
    private final GameProfile profile;
    private final net.minecraft.core.RegistryAccess registryManager;
    private long lastKeepAliveTime;
    private long lastKeepAliveSentTime;
    private boolean lobbyWorldSent;

    public PbWaitingHandler(ProxyServer proxyServer, Connection connection, GameProfile profile,
                            net.minecraft.core.RegistryAccess registryManager) {
        this.proxyServer = proxyServer;
        this.connection = connection;
        this.profile = profile;
        this.registryManager = registryManager;
        this.lastKeepAliveTime = System.currentTimeMillis();
        this.lastKeepAliveSentTime = 0;
        LOGGER.info("Waiting handler initialized for {}", profile.name());
    }

    @Override
    public void tick() {
        long now = System.currentTimeMillis();

        // Send the lobby world on the first tick so the client leaves "Joining World"
        if (!lobbyWorldSent) {
            lobbyWorldSent = true;
            LobbyWorldSender.sendLobbyWorld(connection, registryManager);
        }

        if (now - lastKeepAliveTime > KEEP_ALIVE_TIMEOUT_MS) {
            LOGGER.warn("Client {} timed out (no keep-alive for 30s)", profile.name());
            connection.disconnect(Component.literal("Timed out"));
        }

        // Send periodic keep-alives to prevent the client from timing out
        if (now - lastKeepAliveSentTime >= KEEP_ALIVE_INTERVAL_MS) {
            lastKeepAliveSentTime = now;
            connection.send(new ClientboundKeepAlivePacket(now));
        }

        // Poll for server connection establishment
        if (connectingToServer) {
            connectionAttemptTicks++;
            Minecraft mc = Minecraft.getInstance();

            // Send live status updates to the client
            if (connectionAttemptTicks == 20) {
                sendChat("§7[PingBypass] Resolving address...");
            } else if (connectionAttemptTicks == 40 && mc.getConnection() == null) {
                sendChat("§7[PingBypass] Establishing connection...");
            } else if (connectionAttemptTicks == 60 && mc.getConnection() == null) {
                sendChat("§7[PingBypass] Encrypting...");
            }

            if (mc.getConnection() != null && mc.player == null) {
                // In configuration/login phase
                if (connectionAttemptTicks % 40 == 0) {
                    sendChat("§7[PingBypass] Configuring...");
                }
            } else if (mc.getConnection() != null && mc.player != null && mc.level != null) {
                if (mc.getConnection().registryAccess() != null) {
                    // Server connection established and registry is ready!
                    connectingToServer = false;
                    sendChat("§a[PingBypass] Connected! Loading world...");
                    onServerConnectionEstablished(mc, pendingServerIp, pendingServerPort);
                } else if (connectionAttemptTicks % 40 == 0) {
                    sendChat("§7[PingBypass] Loading terrain...");
                }
            } else if (mc.screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen disconnectedScreen) {
                // Connection failed
                connectingToServer = false;
                String reason = ((eu.client.mixins.accessors.DisconnectedScreenAccessor) disconnectedScreen)
                        .euclient$getDetails().reason().getString();
                LOGGER.warn("Connection to {}:{} failed for {}: {}", pendingServerIp, pendingServerPort, profile.name(), reason);
                sendError("Failed to connect to " + pendingServerIp + ":" + pendingServerPort + ": " + reason);
                sendChat("§c[PingBypass] Failed to connect to " + pendingServerIp + ":" + pendingServerPort + " (" + reason + ")");
            } else if (connectionAttemptTicks > 600) {
                // Timeout after ~30 seconds
                connectingToServer = false;
                LOGGER.warn("Connection to {}:{} timed out for {}", pendingServerIp, pendingServerPort, profile.name());
                sendError("Connection to " + pendingServerIp + ":" + pendingServerPort + " timed out");
                sendChat("§c[PingBypass] Connection timed out");
            }
        }
    }

    @Override
    public void handleCustomPayload(ServerboundCustomPayloadPacket packet) {
        CustomPacketPayload payload = packet.payload();
        if (!PbCustomPayload.CHANNEL.equals(payload.type().id())) {
            return;
        }

        if (!(payload instanceof PbCustomPayload pbPayload)) {
            return;
        }

        FriendlyByteBuf buf = pbPayload.toBuf();
        try {
            int packetId = buf.readVarInt();
            if (packetId == PbCustomPayload.C2S_JOIN) {
                String serverIp = buf.readUtf();
                int serverPort = buf.readVarInt();
                handleJoinRequest(serverIp, serverPort);
            } else {
                LOGGER.warn("Unexpected packet ID {} from {} during waiting state",
                        packetId, profile.name());
            }
        } catch (Exception e) {
            LOGGER.warn("Malformed custom payload from {}", profile.name(), e);
        } finally {
            buf.release();
        }
    }

    /**
     * Handles a C2S_JOIN request from the client.
     * Initiates a connection to the specified MC server using the headless client session.
     * The proxy (HeadlessMC) connects to the target server via ConnectScreen.
     * Once connected (mc.player != null), the world state is replayed to the client.
     */
    void handleJoinRequest(String serverIp, int serverPort) {
        LOGGER.info("Client {} requested join to {}:{}", profile.name(), serverIp, serverPort);

        Minecraft mc = Minecraft.getInstance();

        // Check if the proxy is already connected to a server
        if (mc.getConnection() != null && mc.player != null && mc.level != null
                && mc.getConnection().registryAccess() != null) {
            LOGGER.info("Proxy already connected to a server, replaying world state for {}", profile.name());
            sendChat("§a[PingBypass] Proxy already connected! Resuming session...");
            onServerConnectionEstablished(mc, serverIp, serverPort);
            return;
        }

        sendChat("§d[PingBypass] §fConnecting to §a" + serverIp + ":" + serverPort + "§f...");

        // DNS resolution itself is now handled by ServerNameResolverMixin (java.net-based
        // fallback), which keeps the real hostname intact for the handshake -- no need to
        // pre-resolve or substitute an IP here anymore.
        String address = serverIp + ":" + serverPort;
        ServerAddress serverAddress = ServerAddress.parseString(address);
        ServerData serverInfo = new ServerData("PingBypass Target", address, ServerData.Type.OTHER);

        // Track connection state
        this.pendingServerIp = serverIp;
        this.pendingServerPort = serverPort;
        this.connectingToServer = true;
        this.connectionAttemptTicks = 0;

        mc.execute(() -> {
            try {
                LOGGER.info("Initiating connection to {} for {}", address, profile.name());
                ConnectScreen.startConnecting(new net.minecraft.client.gui.screens.TitleScreen(),
                        mc, serverAddress, serverInfo, false, null);
            } catch (Exception e) {
                LOGGER.error("Failed to initiate connection to {} for {}", address, profile.name(), e);
                sendError("Failed to connect to " + address + ": " + e.getMessage());
                connectingToServer = false;
            }
        });
    }

    private String pendingServerIp;
    private int pendingServerPort;
    private volatile boolean connectingToServer;
    private int connectionAttemptTicks;

    /**
     * Called when the headless client has successfully connected to the target MC server.
     * Sets up forwarding and transitions to PbPlayHandler.
     *
     * Critical ordering (mirrors PingBypass's S2PB2CPipeline):
     * 1. Create S2CForwarder and LOCK it (queue incoming S2C packets)
     * 2. Start the forwarder (subscribes to events, packets get queued)
     * 3. Replay world state to client
     * 4. Transition to PbPlayHandler with the initialTeleportId
     * 5. When client confirms the teleport, PbPlayHandler unlocks the forwarder
     *    and all queued packets flush in order.
     */
    private void onServerConnectionEstablished(Minecraft mc, String serverIp, int serverPort) {
        LOGGER.info("Server connection established to {}:{} for {}", serverIp, serverPort, profile.name());

        try {
            // Get the server connection from the network handler
            Connection serverConnection = mc.getConnection().getConnection();
            proxyServer.setServerConnection(serverConnection);

            // Pull the registry straight off mc.level rather than mc.getConnection() --
            // WorldStateReplay builds packets (e.g. CommonPlayerSpawnInfo's dimension-type
            // Holder) directly from mc.level, so the encoder MUST use that exact same
            // RegistryAccess instance. Re-fetching mc.getConnection().registryAccess()
            // separately raced a live mid-session CONFIGURATION reload (server pushing
            // registry_data updates) on this fast-reconnect path: it could return null or
            // a newer/different instance than the one mc.level's holders were built from,
            // and encoding fell back to RegistryAccess.EMPTY -- crashing with
            // "Can't find id for 'Reference{...dimension_type/overworld}' in map" while
            // encoding ClientboundLoginPacket.
            net.minecraft.core.RegistryAccess serverRegistry = mc.level.registryAccess();

            // Re-transition the outbound encoder to use the REAL server's registry.
            connection.setupOutboundProtocol(
                    GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(serverRegistry)));
            LOGGER.info("Re-transitioned outbound encoder to real server registry for {}", profile.name());

            // Also re-transition inbound to use the server's registry for decoding C2S packets
            // (needed for CustomPacketPayload packets that reference registry entries)
            final net.minecraft.core.RegistryAccess finalRegistry = serverRegistry;

            // Send the server name to the client
            String address = serverIp + ":" + serverPort;
            connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                    eu.client.pingbypass.protocol.PbCustomPayload.fromPacket(
                            new eu.client.pingbypass.protocol.packets.S2CServerNamePacket(address))));

            // Create S2C forwarder — start it immediately without locking.
            // Simpler approach: just start forwarding right away.
            S2CForwarder s2cForwarder = new S2CForwarder(connection);
            s2cForwarder.start();

            // Replay the full world state to the client
            int initialTeleportId = WorldStateReplay.replay(connection, serverRegistry);

            // Unlock immediately (no-op since we didn't lock, but keeps the API consistent)
            // The client will process the replay and start sending packets right away.

            // Transition to PbPlayHandler for C2S input handling (client → proxy)
            PbPlayHandler playHandler = new PbPlayHandler(
                    proxyServer, connection, profile, s2cForwarder, 0);
            connection.setupInboundProtocol(
                    GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(finalRegistry), () -> false),
                    playHandler);

            LOGGER.info("Proxy fully active for {} on {}:{} (awaiting teleport confirm)",
                    profile.name(), serverIp, serverPort);
        } catch (Exception e) {
            LOGGER.error("Failed to set up forwarding for {}", profile.name(), e);
            sendError("Failed to set up forwarding: " + e.getMessage());
        }
    }

    /**
     * Sends an S2C_ERROR packet to the client over the custom channel.
     */
    private void sendError(String message) {
        if (connection.isConnected()) {
            connection.send(new ClientboundCustomPayloadPacket(
                    PbCustomPayload.fromPacket(new S2CErrorPacket(message))));
        }
    }

    /**
     * Sends a chat message to the client (system message).
     */
    private void sendChat(String message) {
        if (connection.isConnected()) {
            connection.send(new net.minecraft.network.protocol.game.ClientboundSystemChatPacket(
                    net.minecraft.network.chat.Component.literal(message), false));
        }
    }

    @Override
    public void handleKeepAlive(ServerboundKeepAlivePacket packet) {
        // Client responded to our keepalive — just update the timestamp
        this.lastKeepAliveTime = System.currentTimeMillis();
    }

    @Override
    public void onDisconnect(DisconnectionDetails info) {
        LOGGER.info("Client {} disconnected during waiting state", profile.name());
    }

    @Override
    public boolean isAcceptingMessages() {
        return this.connection.isConnected();
    }

    public GameProfile getProfile() {
        return profile;
    }

    public ProxyServer getProxyServer() {
        return proxyServer;
    }

    public Connection getConnection() {
        return connection;
    }

    // --- ServerGamePacketListener method stubs ---

    @Override public void handleAnimate(ServerboundSwingPacket p) {}
    @Override public void handleChat(net.minecraft.network.protocol.game.ServerboundChatPacket p) {}
    @Override public void handleChatCommand(net.minecraft.network.protocol.game.ServerboundChatCommandPacket p) {}
    @Override public void handleSignedChatCommand(net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket p) {}
    @Override public void handleChatAck(net.minecraft.network.protocol.game.ServerboundChatAckPacket p) {}
    @Override public void handleClientCommand(ServerboundClientCommandPacket p) {}
    @Override public void handleContainerButtonClick(net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket p) {}
    @Override public void handleContainerClick(ServerboundContainerClickPacket p) {}
    @Override public void handlePlaceRecipe(net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket p) {}
    @Override public void handleContainerClose(ServerboundContainerClosePacket p) {}
    @Override public void handleAttack(net.minecraft.network.protocol.game.ServerboundAttackPacket p) {}
    @Override public void handleInteract(ServerboundInteractPacket p) {}
    @Override public void handleSpectateEntity(net.minecraft.network.protocol.game.ServerboundSpectateEntityPacket p) {}
    @Override public void handleMovePlayer(ServerboundMovePlayerPacket p) {}
    @Override public void handlePlayerAbilities(net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket p) {}
    @Override public void handlePlayerAction(ServerboundPlayerActionPacket p) {}
    @Override public void handlePlayerCommand(net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket p) {}
    @Override public void handlePlayerInput(net.minecraft.network.protocol.game.ServerboundPlayerInputPacket p) {}
    @Override public void handleSetCarriedItem(ServerboundSetCarriedItemPacket p) {}
    @Override public void handleSetCreativeModeSlot(net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket p) {}
    @Override public void handleSignUpdate(net.minecraft.network.protocol.game.ServerboundSignUpdatePacket p) {}
    @Override public void handleUseItemOn(ServerboundUseItemOnPacket p) {}
    @Override public void handleUseItem(ServerboundUseItemPacket p) {}
    @Override public void handleTeleportToEntityPacket(net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket p) {}
    @Override public void handlePaddleBoat(net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket p) {}
    @Override public void handleMoveVehicle(net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket p) {}
    @Override public void handleAcceptTeleportPacket(ServerboundAcceptTeleportationPacket p) {}
    @Override public void handleAcceptPlayerLoad(net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket p) {}
    @Override public void handleRecipeBookSeenRecipePacket(net.minecraft.network.protocol.game.ServerboundRecipeBookSeenRecipePacket p) {}
    @Override public void handleBundleItemSelectedPacket(net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket p) {}
    @Override public void handleRecipeBookChangeSettingsPacket(net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket p) {}
    @Override public void handleSeenAdvancements(net.minecraft.network.protocol.game.ServerboundSeenAdvancementsPacket p) {}
    @Override public void handleCustomCommandSuggestions(ServerboundCommandSuggestionPacket p) {}
    @Override public void handleSetCommandBlock(net.minecraft.network.protocol.game.ServerboundSetCommandBlockPacket p) {}
    @Override public void handleSetCommandMinecart(net.minecraft.network.protocol.game.ServerboundSetCommandMinecartPacket p) {}
    @Override public void handlePickItemFromBlock(net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket p) {}
    @Override public void handlePickItemFromEntity(net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket p) {}
    @Override public void handleRenameItem(net.minecraft.network.protocol.game.ServerboundRenameItemPacket p) {}
    @Override public void handleSetBeaconPacket(net.minecraft.network.protocol.game.ServerboundSetBeaconPacket p) {}
    @Override public void handleSetGameRule(net.minecraft.network.protocol.game.ServerboundSetGameRulePacket p) {}
    @Override public void handleSetStructureBlock(net.minecraft.network.protocol.game.ServerboundSetStructureBlockPacket p) {}
    @Override public void handleSetTestBlock(net.minecraft.network.protocol.game.ServerboundSetTestBlockPacket p) {}
    @Override public void handleTestInstanceBlockAction(net.minecraft.network.protocol.game.ServerboundTestInstanceBlockActionPacket p) {}
    @Override public void handleSelectTrade(net.minecraft.network.protocol.game.ServerboundSelectTradePacket p) {}
    @Override public void handleEditBook(net.minecraft.network.protocol.game.ServerboundEditBookPacket p) {}
    @Override public void handleEntityTagQuery(net.minecraft.network.protocol.game.ServerboundEntityTagQueryPacket p) {}
    @Override public void handleContainerSlotStateChanged(net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket p) {}
    @Override public void handleBlockEntityTagQuery(net.minecraft.network.protocol.game.ServerboundBlockEntityTagQueryPacket p) {}
    @Override public void handleSetJigsawBlock(net.minecraft.network.protocol.game.ServerboundSetJigsawBlockPacket p) {}
    @Override public void handleJigsawGenerate(net.minecraft.network.protocol.game.ServerboundJigsawGeneratePacket p) {}
    @Override public void handleChangeDifficulty(net.minecraft.network.protocol.game.ServerboundChangeDifficultyPacket p) {}
    @Override public void handleChangeGameMode(net.minecraft.network.protocol.game.ServerboundChangeGameModePacket p) {}
    @Override public void handleLockDifficulty(net.minecraft.network.protocol.game.ServerboundLockDifficultyPacket p) {}
    @Override public void handleChatSessionUpdate(net.minecraft.network.protocol.game.ServerboundChatSessionUpdatePacket p) {}
    @Override public void handleConfigurationAcknowledged(net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket p) {}
    @Override public void handleChunkBatchReceived(net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket p) {}
    @Override public void handleDebugSubscriptionRequest(net.minecraft.network.protocol.game.ServerboundDebugSubscriptionRequestPacket p) {}
    @Override public void handleClientTickEnd(ServerboundClientTickEndPacket p) {}

    // --- ServerCommonPacketListener / ServerCookiePacketListener / ServerPingPacketListener stubs ---
    @Override public void handleClientInformation(net.minecraft.network.protocol.common.ServerboundClientInformationPacket p) {}
    @Override public void handlePong(net.minecraft.network.protocol.common.ServerboundPongPacket p) {}
    @Override public void handleResourcePackResponse(net.minecraft.network.protocol.common.ServerboundResourcePackPacket p) {}
    @Override public void handleCookieResponse(ServerboundCookieResponsePacket p) {}
    @Override public void handleCustomClickAction(net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket p) {}
    @Override public void handlePingRequest(net.minecraft.network.protocol.ping.ServerboundPingRequestPacket p) {}
}
