package eu.client.pingbypass.handler;

import com.mojang.authlib.GameProfile;
import eu.client.EUClient;
import eu.client.modules.Module;
import eu.client.pingbypass.server.ProxyServer;
import eu.client.pingbypass.server.ProxyServerTickListener;
import eu.client.pingbypass.server.S2CForwarder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.TickablePacketListener;
import net.minecraft.network.protocol.Packet;
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
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dumb pipe approach: forward ALL client packets to the real server as-is.
 * The proxy doesn't interpret or replay anything — it just passes packets through.
 * 
 * Modules inject their own packets independently via mc.getConnection().send().
 * The proxy's own player.tick() movement is suppressed by the ClientPlayerEntityMixin.
 */
public class PbPlayHandler implements ServerGamePacketListener, TickablePacketListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(PbPlayHandler.class);
    private static final long KEEP_ALIVE_INTERVAL_MS = 15_000L;

    private final ProxyServer proxyServer;
    private final Connection clientConnection;
    private final GameProfile profile;
    private final S2CForwarder s2cForwarder;
    private long lastKeepAliveSent;

    public PbPlayHandler(ProxyServer proxyServer, Connection clientConnection,
                         GameProfile profile, S2CForwarder s2cForwarder, int initialTeleportId) {
        this.proxyServer = proxyServer;
        this.clientConnection = clientConnection;
        this.profile = profile;
        this.s2cForwarder = s2cForwarder;
        LOGGER.info("Play handler initialized for {} — dumb pipe mode", profile.name());
    }

    @Override
    public void tick() {
        long now = System.currentTimeMillis();
        if (now - lastKeepAliveSent >= KEEP_ALIVE_INTERVAL_MS) {
            lastKeepAliveSent = now;
            clientConnection.send(new ClientboundKeepAlivePacket(now));
        }
    }

    @Override
    public void onDisconnect(DisconnectionDetails info) {
        LOGGER.info("Client {} disconnected: {}", profile.name(), info.reason());
        s2cForwarder.stop();

        // Disconnect the proxy from the server too
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("Client disconnected"));
            }
            if (mc.getConnection() != null) {
                mc.getConnection().getConnection().disconnect(
                        net.minecraft.network.chat.Component.literal("Client disconnected"));
            }
        });
    }

    @Override
    public boolean isAcceptingMessages() { return clientConnection.isConnected(); }

    // ═══════════════════════════════════════════════════════════════════
    // CORE: Forward client packets directly to the real server connection.
    // Uses allowSend() so the ProxyServerTickListener filter lets the
    // packet through if it happens to fire a PacketSendEvent.
    // ═══════════════════════════════════════════════════════════════════

    /**
     * When the client tries to interact (eat/place), sync the server's slot
     * to the client's actual slot. If SpeedMine is actively mining, the
     * server thinks we're holding a pickaxe — but the client wants to eat.
     * We send the client's real slot and tell SpeedMine to pause so it
     * doesn't switch back to pickaxe (which would cancel eating).
     */
    private void syncSlotForInteract() {
        var speedMine = EUClient.MODULE_MANAGER.getModule(
                eu.client.modules.impl.player.SpeedMineModule.class);
        if (speedMine != null && speedMine.isToggled() && speedMine.isRunningOnProxy()
                && (speedMine.getPrimary() != null || speedMine.getSecondary() != null)) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                int clientSlot = mc.player.getInventory().getSelectedSlot();
                // Switch server to client's actual slot for the interact
                forward(new ServerboundSetCarriedItemPacket(clientSlot));
                // Tell SpeedMine to pause — don't switch back to pickaxe
                speedMine.setInteractPaused(true);
            }
        }
    }

    private void forward(Packet<?> packet) {
        Connection serverConn = proxyServer.getServerConnection();
        if (serverConn != null && serverConn.isConnected()) {
            ProxyServerTickListener.allowSend(() -> serverConn.send(packet));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // MOVEMENT — forward as-is, also sync proxy position for modules
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void handleMovePlayer(ServerboundMovePlayerPacket p) {
        // Sync proxy state so modules can read player position
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            double prevY = mc.player.getY();
            if (p.hasPosition()) {
                mc.player.setPos(
                        p.getX(mc.player.getX()),
                        p.getY(mc.player.getY()),
                        p.getZ(mc.player.getZ()));

                // Track fallDistance from Y changes (proxy physics don't run)
                double newY = mc.player.getY();
                if (newY < prevY && !p.isOnGround()) {
                    mc.player.fallDistance += (float)(prevY - newY);
                } else if (p.isOnGround()) {
                    mc.player.fallDistance = 0;
                }
            }
            if (p.hasRotation()) {
                mc.player.setYRot(p.getYRot(mc.player.getYRot()));
                mc.player.setXRot(p.getXRot(mc.player.getXRot()));
            }
            mc.player.setOnGround(p.isOnGround());
        }
        forward(p);
    }

    // ═══════════════════════════════════════════════════════════════════
    // ALL OTHER PACKETS — just forward directly
    // ═══════════════════════════════════════════════════════════════════

    @Override public void handleUseItemOn(ServerboundUseItemOnPacket p) {
        syncSlotForInteract();
        forward(p);
    }
    @Override public void handleUseItem(ServerboundUseItemPacket p) {
        syncSlotForInteract();
        forward(p);
    }
    @Override public void handleInteract(ServerboundInteractPacket p) { forward(p); }
    @Override public void handlePlayerAction(ServerboundPlayerActionPacket p) {
        // When the client releases item use (finishes eating), unpause SpeedMine
        if (p.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM) {
            var speedMine = EUClient.MODULE_MANAGER.getModule(
                    eu.client.modules.impl.player.SpeedMineModule.class);
            if (speedMine != null && speedMine.isInteractPaused()) {
                speedMine.setInteractPaused(false);
            }
            forward(p);
            return;
        }
        // When the client starts mining a block, fire AttackBlockEvent on the
        // proxy so proxy-sided modules (like SpeedMine) can pick it up.
        if (p.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.execute(() -> {
                    var event = new eu.client.events.impl.AttackBlockEvent(p.getPos(), p.getDirection());
                    EUClient.EVENT_HANDLER.post(event);
                    if (!event.isCancelled()) {
                        forward(p);
                    }
                });
                return;
            }
        }
        forward(p);
    }

    @Override public void handleAnimate(ServerboundSwingPacket p) { forward(p); }
    @Override public void handleContainerClick(ServerboundContainerClickPacket p) {
        // Replay the click on the proxy's container so its inventory stays in sync
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.execute(() -> {
                try {
                    net.minecraft.world.inventory.AbstractContainerMenu handler =
                            p.containerId() == mc.player.containerMenu.containerId
                                    ? mc.player.containerMenu
                                    : mc.player.inventoryMenu;
                    handler.clicked(p.slotNum(), p.buttonNum(), p.containerInput(), mc.player);
                } catch (Exception ignored) {}
            });
        }
        forward(p);
    }
    @Override public void handleContainerClose(ServerboundContainerClosePacket p) { forward(p); }
    @Override public void handleSetCarriedItem(ServerboundSetCarriedItemPacket p) {
        // Sync proxy's local slot (so modules can read the client's real slot).
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.getInventory().setSelectedSlot(p.getSlot());
        }
        // If SpeedMine is actively mining on the proxy, DON'T forward the
        // client's slot change to the real server — SpeedMine controls the
        // server's slot state directly via serverSend(). Forwarding would
        // override the pickaxe with food and break mining.
        var speedMine = EUClient.MODULE_MANAGER.getModule(
                eu.client.modules.impl.player.SpeedMineModule.class);
        if (speedMine != null && speedMine.isToggled() && speedMine.isRunningOnProxy()
                && (speedMine.getPrimary() != null || speedMine.getSecondary() != null)) {
            // Don't forward — SpeedMine owns the server's slot state
            return;
        }
        forward(p);
    }
    @Override public void handleClientCommand(ServerboundClientCommandPacket p) { forward(p); }
    @Override public void handleChat(net.minecraft.network.protocol.game.ServerboundChatPacket p) { forward(p); }
    @Override public void handleChatCommand(net.minecraft.network.protocol.game.ServerboundChatCommandPacket p) { forward(p); }
    @Override public void handleSignedChatCommand(net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket p) { forward(p); }
    @Override public void handleChatAck(net.minecraft.network.protocol.game.ServerboundChatAckPacket p) { forward(p); }
    @Override public void handleChatSessionUpdate(net.minecraft.network.protocol.game.ServerboundChatSessionUpdatePacket p) { forward(p); }
    @Override public void handleCustomCommandSuggestions(ServerboundCommandSuggestionPacket p) { forward(p); }
    @Override public void handleSeenAdvancements(net.minecraft.network.protocol.game.ServerboundSeenAdvancementsPacket p) { forward(p); }
    @Override public void handleSpectateEntity(net.minecraft.network.protocol.game.ServerboundSpectateEntityPacket p) { forward(p); }
    @Override public void handleResourcePackResponse(net.minecraft.network.protocol.common.ServerboundResourcePackPacket p) { forward(p); }
    @Override public void handleCookieResponse(ServerboundCookieResponsePacket p) { forward(p); }
    @Override public void handleAcceptTeleportPacket(ServerboundAcceptTeleportationPacket p) { /* proxy already confirmed */ }
    @Override public void handleChunkBatchReceived(net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket p) { forward(p); }
    @Override public void handleClientTickEnd(ServerboundClientTickEndPacket p) { forward(p); }
    @Override public void handleConfigurationAcknowledged(net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket p) { forward(p); }
    @Override public void handleClientInformation(net.minecraft.network.protocol.common.ServerboundClientInformationPacket p) { forward(p); }
    @Override public void handleBlockEntityTagQuery(net.minecraft.network.protocol.game.ServerboundBlockEntityTagQueryPacket p) { forward(p); }
    @Override public void handleChangeDifficulty(net.minecraft.network.protocol.game.ServerboundChangeDifficultyPacket p) { forward(p); }
    @Override public void handleContainerSlotStateChanged(net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket p) { forward(p); }
    @Override public void handleDebugSubscriptionRequest(net.minecraft.network.protocol.game.ServerboundDebugSubscriptionRequestPacket p) { forward(p); }
    @Override public void handleEditBook(net.minecraft.network.protocol.game.ServerboundEditBookPacket p) { forward(p); }
    @Override public void handleEntityTagQuery(net.minecraft.network.protocol.game.ServerboundEntityTagQueryPacket p) { forward(p); }
    @Override public void handleJigsawGenerate(net.minecraft.network.protocol.game.ServerboundJigsawGeneratePacket p) { forward(p); }
    @Override public void handleLockDifficulty(net.minecraft.network.protocol.game.ServerboundLockDifficultyPacket p) { forward(p); }
    @Override public void handleMoveVehicle(net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket p) { forward(p); }
    @Override public void handlePaddleBoat(net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket p) { forward(p); }
    @Override public void handlePickItemFromBlock(net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket p) { forward(p); }
    @Override public void handlePickItemFromEntity(net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket p) { forward(p); }
    @Override public void handlePlaceRecipe(net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket p) { forward(p); }
    @Override public void handlePlayerAbilities(net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket p) { forward(p); }
    @Override public void handlePlayerCommand(net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket p) {
        // Sync sprint state on proxy
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            switch (p.getAction()) {
                case START_SPRINTING -> mc.player.setSprinting(true);
                case STOP_SPRINTING -> mc.player.setSprinting(false);
                default -> {}
            }
        }
        forward(p);
    }
    @Override public void handlePlayerInput(net.minecraft.network.protocol.game.ServerboundPlayerInputPacket p) {
        // Sync sneak state on proxy
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.setShiftKeyDown(p.input().shift());
        }
        forward(p);
    }
    @Override public void handleAcceptPlayerLoad(net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket p) { forward(p); }
    @Override public void handleRecipeBookSeenRecipePacket(net.minecraft.network.protocol.game.ServerboundRecipeBookSeenRecipePacket p) { forward(p); }
    @Override public void handleRenameItem(net.minecraft.network.protocol.game.ServerboundRenameItemPacket p) { forward(p); }
    @Override public void handleBundleItemSelectedPacket(net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket p) { forward(p); }
    @Override public void handleSelectTrade(net.minecraft.network.protocol.game.ServerboundSelectTradePacket p) { forward(p); }
    @Override public void handleSetBeaconPacket(net.minecraft.network.protocol.game.ServerboundSetBeaconPacket p) { forward(p); }
    @Override public void handleSetCommandBlock(net.minecraft.network.protocol.game.ServerboundSetCommandBlockPacket p) { forward(p); }
    @Override public void handleSetCommandMinecart(net.minecraft.network.protocol.game.ServerboundSetCommandMinecartPacket p) { forward(p); }
    @Override public void handleSetCreativeModeSlot(net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket p) { forward(p); }
    @Override public void handleSetGameRule(net.minecraft.network.protocol.game.ServerboundSetGameRulePacket p) { forward(p); }
    @Override public void handleSetJigsawBlock(net.minecraft.network.protocol.game.ServerboundSetJigsawBlockPacket p) { forward(p); }
    @Override public void handleSetStructureBlock(net.minecraft.network.protocol.game.ServerboundSetStructureBlockPacket p) { forward(p); }
    @Override public void handleSetTestBlock(net.minecraft.network.protocol.game.ServerboundSetTestBlockPacket p) { forward(p); }
    @Override public void handleSignUpdate(net.minecraft.network.protocol.game.ServerboundSignUpdatePacket p) { forward(p); }
    @Override public void handleTeleportToEntityPacket(net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket p) { forward(p); }
    @Override public void handleTestInstanceBlockAction(net.minecraft.network.protocol.game.ServerboundTestInstanceBlockActionPacket p) { forward(p); }
    @Override public void handleChangeGameMode(net.minecraft.network.protocol.game.ServerboundChangeGameModePacket p) { forward(p); }
    @Override public void handleAttack(net.minecraft.network.protocol.game.ServerboundAttackPacket p) { forward(p); }
    @Override public void handleContainerButtonClick(net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket p) { forward(p); }
    @Override public void handleRecipeBookChangeSettingsPacket(net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket p) { forward(p); }
    @Override public void handleCustomClickAction(net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket p) { forward(p); }

    // ═══════════════════════════════════════════════════════════════════
    // KEEPALIVE — proxy handles its own keepalive with the client,
    // but forwards the client's keepalive response to the server too
    // ═══════════════════════════════════════════════════════════════════

    @Override public void handleKeepAlive(ServerboundKeepAlivePacket p) { /* proxy handles keepalive with client */ }
    @Override public void handlePong(net.minecraft.network.protocol.common.ServerboundPongPacket p) { forward(p); }
    @Override public void handlePingRequest(net.minecraft.network.protocol.ping.ServerboundPingRequestPacket p) { }

    // ═══════════════════════════════════════════════════════════════════
    // CUSTOM PAYLOAD — handle PingBypass protocol, forward others
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void handleCustomPayload(ServerboundCustomPayloadPacket p) {
        if (p.payload() instanceof eu.client.pingbypass.protocol.PbCustomPayload pbPayload) {
            handlePbPayload(pbPayload);
            return;
        }
        forward(p);
    }

    private void handlePbPayload(eu.client.pingbypass.protocol.PbCustomPayload pbPayload) {
        net.minecraft.network.FriendlyByteBuf buf = pbPayload.toBuf();
        try {
            int packetId = buf.readVarInt();
            switch (packetId) {
                case eu.client.pingbypass.protocol.packets.C2SModuleTogglePacket.ID -> {
                    var pkt = new eu.client.pingbypass.protocol.packets.C2SModuleTogglePacket(buf);
                    Module module = EUClient.MODULE_MANAGER.getModule(pkt.getModuleName());
                    if (module != null) {
                        Minecraft.getInstance().execute(() -> {
                            module.setToggled(pkt.isEnabled(), false);
                            LOGGER.info("[PB] Module {} toggled to {}", pkt.getModuleName(), pkt.isEnabled());
                        });
                    }
                }
                case eu.client.pingbypass.protocol.packets.C2SSettingChangePacket.ID -> {
                    var pkt = new eu.client.pingbypass.protocol.packets.C2SSettingChangePacket(buf);
                    handleSettingChange(pkt);
                }
                default -> LOGGER.debug("[PB] Unknown packet ID: {}", packetId);
            }
        } catch (Exception e) {
            LOGGER.warn("[PB] Failed to handle payload", e);
        } finally {
            buf.release();
        }
    }

    private void handleSettingChange(eu.client.pingbypass.protocol.packets.C2SSettingChangePacket pkt) {
        Module module = EUClient.MODULE_MANAGER.getModule(pkt.getModuleName());
        if (module == null) return;

        eu.client.settings.Setting setting = module.getSetting(pkt.getSettingName());
        if (setting == null) return;

        Minecraft.getInstance().execute(() -> {
            try {
                String value = pkt.getValue();
                if (setting instanceof eu.client.settings.impl.BooleanSetting s) {
                    s.setValue(Boolean.parseBoolean(value));
                } else if (setting instanceof eu.client.settings.impl.NumberSetting s) {
                    switch (s.getType()) {
                        case INTEGER -> s.setValue(Integer.parseInt(value));
                        case LONG -> s.setValue(Long.parseLong(value));
                        case FLOAT -> s.setValue(Float.parseFloat(value));
                        case DOUBLE -> s.setValue(Double.parseDouble(value));
                    }
                } else if (setting instanceof eu.client.settings.impl.ModeSetting s) {
                    s.setValue(value);
                } else if (setting instanceof eu.client.settings.impl.StringSetting s) {
                    s.setValue(value);
                } else if (setting instanceof eu.client.settings.impl.ColorSetting s) {
                    String[] parts = value.split(",");
                    if (parts.length >= 4) {
                        s.setColor(new java.awt.Color(
                                Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                                Integer.parseInt(parts[2]), Integer.parseInt(parts[3])));
                        if (parts.length >= 5) s.setSync(Boolean.parseBoolean(parts[4]));
                        if (parts.length >= 6) s.setRainbow(Boolean.parseBoolean(parts[5]));
                    }
                }
                LOGGER.info("[PB] Setting {}.{} = {}", pkt.getModuleName(), pkt.getSettingName(), value);
            } catch (Exception e) {
                LOGGER.warn("[PB] Failed to apply setting {}.{}", pkt.getModuleName(), pkt.getSettingName(), e);
            }
        });
    }
}
