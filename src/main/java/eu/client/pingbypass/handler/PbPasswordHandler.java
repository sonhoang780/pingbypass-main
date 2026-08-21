package eu.client.pingbypass.handler;

import com.mojang.authlib.GameProfile;
import eu.client.EUClient;
import eu.client.pingbypass.protocol.PbCustomPayload;
import eu.client.pingbypass.server.ProxyServer;
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
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles password verification for the PingBypass proxy connection.
 * Sends S2C_PASSWORD_REQUEST on init, then waits for C2S_PASSWORD response.
 * On correct password, transitions to PbWaitingHandler.
 * On wrong password, disconnects with timing attack mitigation.
 */
public class PbPasswordHandler implements ServerGamePacketListener, TickablePacketListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(PbPasswordHandler.class);

    private static final long KEEP_ALIVE_INTERVAL_MS = 15_000L;

    private final ProxyServer proxyServer;
    private final Connection connection;
    private final GameProfile profile;
    private final net.minecraft.core.RegistryAccess registryManager;
    private boolean passwordRequestSent;
    private boolean lobbyWorldSent;
    private boolean clientInPlayState;
    private long lastKeepAliveTime;

    public PbPasswordHandler(ProxyServer proxyServer, Connection connection, GameProfile profile,
                             net.minecraft.core.RegistryAccess registryManager) {
        this.proxyServer = proxyServer;
        this.connection = connection;
        this.profile = profile;
        this.registryManager = registryManager;
        this.lastKeepAliveTime = 0;
        LOGGER.info("Password handler initialized for {}", profile.name());
    }

    @Override
    public void tick() {
        long now = System.currentTimeMillis();

        // Send the lobby world first so the client can transition to PLAY state
        // and process custom payloads. Without GameJoinS2CPacket, the client
        // can't send packets through Fabric's networking layer.
        if (!lobbyWorldSent) {
            lobbyWorldSent = true;
            eu.client.pingbypass.server.LobbyWorldSender.sendLobbyWorld(connection, registryManager);
        }

        // Send periodic keep-alives
        if (now - lastKeepAliveTime >= KEEP_ALIVE_INTERVAL_MS) {
            lastKeepAliveTime = now;
            connection.send(new net.minecraft.network.protocol.common.ClientboundKeepAlivePacket(now));
        }

        // Only send the password request after the client has confirmed it's in
        // PLAY state by responding to a keep-alive.
        // too early, the client may still be in CONFIGURATION and drop it.
        if (!passwordRequestSent && clientInPlayState) {
            passwordRequestSent = true;
            connection.send(new ClientboundCustomPayloadPacket(PbCustomPayload.passwordRequest()));
            LOGGER.info("Sent password request to {}", profile.name());
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
            if (packetId == PbCustomPayload.C2S_PASSWORD) {
                handlePassword(buf.readUtf());
            } else {
                LOGGER.warn("Unexpected packet ID {} from {} during password verification",
                        packetId, profile.name());
            }
        } finally {
            buf.release();
        }
    }

    private void handlePassword(String password) {
        String expected = EUClient.PINGBYPASS_CONFIG.getPassword();
        if (expected.equals(password)) {
            LOGGER.info("Password accepted for {}", profile.name());
            // Transition to PbWaitingHandler (proxy not connected to server yet)
            // Re-use the same play state protocol since we're already in play state
            connection.setupInboundProtocol(
                    GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY), () -> false),
                    new PbWaitingHandler(proxyServer, connection, profile, registryManager));
        } else {
            LOGGER.warn("Wrong password from {}", profile.name());
            // Timing attack mitigation: sleep random 1-10ms
            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(1, 11));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            connection.disconnect(Component.literal("Wrong password"));
        }
    }

    @Override
    public void onDisconnect(DisconnectionDetails info) {
        LOGGER.info("Client {} disconnected during password verification", profile.name());
    }

    @Override
    public boolean isAcceptingMessages() {
        return this.connection.isConnected();
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
    // PORT (26.2): ServerboundSpectateEntityPacket genuinely removed, but the FEATURE moved, not
    // gone -- real successor is ServerboundSpectatorActionPacket(OptionalInt spectateEntityId) via
    // handleSpectatorAction(...), confirmed against real 26.2 source. Corrects this session's
    // earlier wrong guess (deleting the override outright) once the interface method actually
    // surfaced as a real compile error ("not abstract and does not override...").
    @Override public void handleSpectatorAction(net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket p) {}
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
    @Override public void handleKeepAlive(ServerboundKeepAlivePacket p) {
        // Client responded to our keep-alive — it's in PLAY state and ready
        clientInPlayState = true;
    }
    @Override public void handlePong(net.minecraft.network.protocol.common.ServerboundPongPacket p) {}
    @Override public void handleResourcePackResponse(net.minecraft.network.protocol.common.ServerboundResourcePackPacket p) {}
    @Override public void handleCookieResponse(ServerboundCookieResponsePacket p) {}
    @Override public void handleCustomClickAction(net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket p) {}
    @Override public void handlePingRequest(net.minecraft.network.protocol.ping.ServerboundPingRequestPacket p) {}
}
