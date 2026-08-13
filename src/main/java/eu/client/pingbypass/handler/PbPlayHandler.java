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
    private boolean wasOnGround = true;
    private long lastKeepAliveSent;

    public PbPlayHandler(ProxyServer proxyServer, Connection clientConnection,
                         GameProfile profile, S2CForwarder s2cForwarder, int initialTeleportId) {
        this.proxyServer = proxyServer;
        this.clientConnection = clientConnection;
        this.profile = profile;
        this.s2cForwarder = s2cForwarder;
        LOGGER.info("Play handler initialized for {} — dumb pipe mode", profile.name());
    }

    public GameProfile getProfile() {
        return profile;
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
        // Was also gated on (getPrimary() != null || getSecondary() != null) -- but Instant's
        // rebreak loop legitimately nulls primary for the single tick between "block confirmed
        // broken" and "new Action re-acquired" (see Action.process()'s air-check -> cancel() ->
        // handle() re-creating it next tick). A real UseItemPacket landing in exactly that one-
        // tick window saw primary/secondary/legacySecondary all null and silently skipped
        // setInteractPaused(true) entirely -- SpeedMine never learned the player was eating, so
        // the very next rebreak cycle switched back to the pickaxe mid-eat as if nothing had
        // happened. Reported as "can't eat while a block is instant-mining" even with WhileEating
        // off. Pausing when SpeedMine has nothing active costs nothing (there's nothing to
        // switch back from), so just drop the null-check instead of trying to also cover
        // legacySecondary and still race the same window.
        if (speedMine != null && speedMine.isToggled() && speedMine.isRunningOnProxy()) {
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
        // Runs on the Netty IO thread. Matches earthhack's PbNetHandler.processPlayer, which
        // defers the equivalent mc.player mutation to mc.addScheduledTask -- without that, this
        // races the render thread's own tick()/move() (now cancelled above for the ghost, but
        // still reads/builds the outgoing packet from mc.player in sendPosition()) touching the
        // same mc.player fields concurrently, corrupting the position mid-write.
        Minecraft.getInstance().execute(() -> handleMovePlayer0(p));
    }

    private void handleMovePlayer0(ServerboundMovePlayerPacket p) {
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

                // The ghost player's position is teleported straight from the client's own
                // packets rather than driven by real physics, so LivingEntity.jumpFromGround()
                // (which is what normally fires PlayerJumpEvent) never runs here -- modules like
                // Surround's JumpDisable that rely on that event never see a proxy-side jump.
                // Detect the same thing directly from the movement data: leaving the ground
                // while ascending.
                if (newY > prevY && !p.isOnGround() && wasOnGround) {
                    EUClient.EVENT_HANDLER.post(new eu.client.events.impl.PlayerJumpEvent());
                }
                wasOnGround = p.isOnGround();
            }
            if (p.hasRotation()) {
                mc.player.setYRot(p.getYRot(mc.player.getYRot()));
                mc.player.setXRot(p.getXRot(mc.player.getXRot()));
            }
            mc.player.setOnGround(p.isOnGround());
            // ServerboundMovePlayerPacket packs horizontalCollision into EVERY movement packet's
            // flags byte (Pos/PosRot/Rot all take it), and vanilla's sendPosition() reads it
            // straight off mc.player.horizontalCollision (an Entity field only ever updated by
            // real physics collision in move()) -- which is cancelled entirely for this ghost now.
            // It was frozen at whatever it happened to be the instant physics got disabled and
            // never changed again, so EVERY regular movement packet (not just the rotation
            // packets modules build by hand, which already read PingBypassFlags.
            // clientHorizontalCollision) carried a stale, usually-wrong flag to the real server --
            // that's the rubberbanding on every move/jump/fall, not just while a module rotates.
            mc.player.horizontalCollision = p.horizontalCollision();

            // Matches earthhack's MotionUpdateHelper: setPosition() immediately followed by a
            // synchronous invokeUpdateWalkingPlayer() call in the SAME scheduled task -- one
            // outgoing packet per incoming client packet, right away. This used to instead rely on
            // the ghost's own tick()-driven sendPosition() (ClientPlayerEntityMixin) picking the
            // new position up on ITS OWN next tick, decoupled from when this handler actually ran.
            // If multiple client movement packets landed within a single ghost tick (bursty
            // delivery, or simply the ghost's tick cadence drifting out of phase with incoming
            // packets), only the LAST one before that tick's sendPosition() call ever got relayed
            // -- the real server saw fewer, larger position jumps than the client actually made,
            // which is exactly what real-server anti-cheat speed/distance checks correct: the
            // rubberbanding on every move/jump/fall. Send immediately instead of waiting.
            // Now that ProxyServerTickListener also blacklists ServerboundMovePlayerPacket (matches
            // earthhack's Pb2SManager -- the ghost's own movement sends are blocked by default),
            // this explicit send needs the same authorization MotionUpdateHelper gives its own
            // invokeUpdateWalkingPlayer() call (PACKET_MANAGER.allowAllOnThisThread(true)).
            ProxyServerTickListener.allowSend(() ->
                    ((eu.client.mixins.accessors.ClientPlayerEntityAccessor) mc.player).invokeSendMovementPackets());
        }
        // Record exactly what the client reported so proxy-side modules injecting their own
        // rotation packets can echo the same flags back instead of inventing their own from the
        // ghost player. onGround is mirrored onto mc.player above, but horizontalCollision has
        // no such mirror -- the ghost never runs collision, so mc.player.horizontalCollision is
        // permanently stale. A rotation packet carrying a horizontalCollision that contradicts
        // the client's own forwarded movement packets makes the real server correct the player:
        // the rubberbanding seen only while moving with AutoCrystal/SpeedMine rotating.
        eu.client.pingbypass.PingBypassFlags.clientOnGround = p.isOnGround();
        eu.client.pingbypass.PingBypassFlags.clientHorizontalCollision = p.horizontalCollision();

        // No forward(p) here: the client's raw movement packet is NOT relayed to the real
        // server. mc.player (the proxy's own ghost) was just repositioned above, and
        // invokeSendMovementPackets() was just called synchronously to build+send a FRESH
        // movement packet on the proxy's own connection to the real server right away, exactly
        // like earthhack's MotionUpdateHelper.makeMotionUpdate()+invokeUpdateWalkingPlayer(). That
        // path is also where RotationManager's queued rotation gets substituted in before the
        // send (ClientPlayerEntityMixin's WrapOperation), so proxy-side aim modules
        // (ServerAutoCrystal etc) always land in the same single outgoing packet rather than
        // racing a second, separately-injected one.
    }

    // ═══════════════════════════════════════════════════════════════════
    // ALL OTHER PACKETS — just forward directly
    // ═══════════════════════════════════════════════════════════════════

    @Override public void handleUseItemOn(ServerboundUseItemOnPacket p) {
        syncSlotForInteract();
        mirrorStartUsingItem(p.getHand());
        forward(p);
    }
    @Override public void handleUseItem(ServerboundUseItemPacket p) {
        syncSlotForInteract();
        mirrorStartUsingItem(p.getHand());
        forward(p);
    }

    /**
     * mc.player.isUsingItem() is what SpeedMine's eat-pause (interactPaused's stillUsing check)
     * and AutoCrystal's WhileEating gate both read to decide "is the real player still eating
     * right now". On the proxy nothing ever called startUsingItem()/stopUsingItem() on the
     * ghost, so isUsingItem() was permanently false regardless of what the real client was
     * actually doing -- two different, opposite-looking bugs from the same root cause:
     *   - SpeedMine's stillUsing check always read false, so its 750ms interactPaused timeout
     *     always fired as if eating had already finished, switching back to the pickaxe mid-eat
     *     and cancelling a real ~1.6s golden apple eat before it completed.
     *   - AutoCrystal's WhileEating gate (shouldPause: eatingFlag && isUsingItem()) always read
     *     false too, so it never actually paused for eating at all -- if it still visually
     *     looked like nothing happened, that's most likely a Switch mode that doesn't touch the
     *     client's own displayed hotbar (Silent/AltSwap), not this.
     * Only start it for items that actually have a use animation/duration (food, potions, bows,
     * shields...) -- calling this unconditionally for every right-click, including placing a
     * block, would incorrectly mark ordinary block placement as "eating" too.
     */
    private void mirrorStartUsingItem(net.minecraft.world.InteractionHand hand) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        net.minecraft.world.item.ItemStack stack = mc.player.getItemInHand(hand);
        if (stack.getUseDuration(mc.player) > 0) {
            mc.player.startUsingItem(hand);
        }
    }
    @Override public void handleInteract(ServerboundInteractPacket p) { forward(p); }
    @Override public void handlePlayerAction(ServerboundPlayerActionPacket p) {
        // When the client releases item use (finishes eating), unpause SpeedMine
        if (p.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.stopUsingItem();

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
        // Only defer to the main thread (costs up to 1 tick of latency) when
        // SpeedMine is actually active and could cancel this — otherwise
        // AttackBlockEvent has no listener and the mc.execute round-trip is
        // pure added delay on every single block break.
        if (p.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
            var speedMine = EUClient.MODULE_MANAGER.getModule(
                    eu.client.modules.impl.player.SpeedMineModule.class);
            if (speedMine != null && speedMine.isToggled()) {
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
        }
        forward(p);
    }

    @Override public void handleAnimate(ServerboundSwingPacket p) { forward(p); }
    @Override public void handleContainerClick(ServerboundContainerClickPacket p) {
        // Like movement: don't dumb-pipe the client's raw packet. It carries the CLIENT's own
        // container's stateId/changedSlots-prediction, which is tracked independently from the
        // PROXY's own container instance (the one the real server is actually validating
        // against) -- forwarding it as-is desyncs the transaction sequence, matching earthhack's
        // real PbNetHandler.processClickWindow: replay the click on the proxy's own container,
        // then build+send a FRESH packet carrying the proxy's own container's stateId.
        // changedSlots/carriedItem are the client's local slot-prediction shortcut for the
        // server to skip a full resync -- we don't have that prediction on the proxy's side, so
        // send HashedStack.EMPTY/no predicted slots; worst case the server does a full
        // resync (ContainerSetContent) instead of trusting the prediction, same as vanilla
        // falls back to when the prediction hash doesn't match.
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.execute(() -> {
                try {
                    net.minecraft.world.inventory.AbstractContainerMenu handler =
                            p.containerId() == mc.player.containerMenu.containerId
                                    ? mc.player.containerMenu
                                    : mc.player.inventoryMenu;
                    handler.clicked(p.slotNum(), p.buttonNum(), p.containerInput(), mc.player);
                    var freshPacket = new ServerboundContainerClickPacket(
                            p.containerId(), handler.getStateId(), p.slotNum(), p.buttonNum(),
                            p.containerInput(), it.unimi.dsi.fastutil.ints.Int2ObjectMaps.emptyMap(),
                            net.minecraft.network.HashedStack.EMPTY);
                    forward(freshPacket);
                } catch (Exception ignored) {}
            });
        }
    }
    @Override public void handleContainerClose(ServerboundContainerClosePacket p) { forward(p); }
    @Override public void handleSetCarriedItem(ServerboundSetCarriedItemPacket p) {
        Minecraft mc = Minecraft.getInstance();

        // If SpeedMine is actively mining on the proxy, it (and whatever it triggers mid-mine,
        // e.g. AutoCrystal's onDestroyBlock switch-to-crystal-then-place-then-switchback
        // sequence) owns the proxy's slot state right now via serverSend(). This handler runs
        // on the Netty IO thread while that sequence runs on the render thread -- previously
        // ONLY the forward-to-real-server was suppressed here, but mc.player.getInventory()
        // (the LOCAL mirror) still got overwritten unconditionally below. A stray/queued
        // SetCarriedItem from the client landing in that exact window still corrupted the
        // local mirror mid-sequence (AutoCrystal reads mc.player.getMainHandItem()/selected
        // slot live to decide what it's holding), even though the real server never saw it --
        // showing up as "block breaks fine but crystal doesn't place/attack". Skip touching
        // the mirror too while SpeedMine owns it, not just the forward.
        var speedMine = EUClient.MODULE_MANAGER.getModule(
                eu.client.modules.impl.player.SpeedMineModule.class);
        if (speedMine != null && speedMine.isToggled() && speedMine.isRunningOnProxy()
                && (speedMine.getPrimary() != null || speedMine.getSecondary() != null)) {
            return;
        }

        // Sync proxy's local slot (so modules can read the client's real slot).
        if (mc.player != null) {
            mc.player.getInventory().setSelectedSlot(p.getSlot());
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
    // Was a no-op ("proxy already confirmed") -- inverted from earthhack's real PbNetHandler,
    // which forwards the REAL client's confirm and blocks the ghost's own (Pb2SManager). The
    // ghost applies+confirms a teleport the instant it's forwarded, before the real client (which
    // is still catching up over real network latency) has even seen it -- the real server then
    // sees the ghost's later, pre-teleport-confirm movement packets as "moved wrongly" and
    // re-teleports, which the ghost re-confirms instantly again: a self-sustaining rubberband
    // loop for as long as the real client lags behind. Forwarding the real client's own confirm
    // instead means the real server keeps ignoring stale positions (matches vanilla
    // ServerGamePacketListenerImpl.updateAwaitingTeleport) until the real client has actually caught up.
    @Override public void handleAcceptTeleportPacket(ServerboundAcceptTeleportationPacket p) { forward(p); }
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
                    eu.client.pingbypass.modules.PbModule pbModule = EUClient.PB_MODULE_MANAGER.getModule(pkt.getModuleName());
                    if (pbModule != null) {
                        Minecraft.getInstance().execute(() -> {
                            eu.client.pingbypass.modules.SyncModule.applyToggle(pbModule, pkt.isEnabled());
                            LOGGER.info("[PB] PbModule {} toggled to {}", pkt.getModuleName(), pkt.isEnabled());
                        });
                    } else {
                        Module module = EUClient.MODULE_MANAGER.getModule(pkt.getModuleName());
                        if (module != null) {
                            Minecraft.getInstance().execute(() -> {
                                module.setToggled(pkt.isEnabled(), false);
                                LOGGER.info("[PB] Module {} toggled to {}", pkt.getModuleName(), pkt.isEnabled());
                            });
                        }
                    }
                }
                case eu.client.pingbypass.protocol.packets.C2SSettingChangePacket.ID -> {
                    var pkt = new eu.client.pingbypass.protocol.packets.C2SSettingChangePacket(buf);
                    eu.client.pingbypass.modules.PbModule pbModule = EUClient.PB_MODULE_MANAGER.getModule(pkt.getModuleName());
                    if (pbModule != null) {
                        Minecraft.getInstance().execute(() ->
                                eu.client.pingbypass.modules.SyncModule.applySetting(pbModule, pkt.getSettingName(), pkt.getValue()));
                    } else {
                        handleSettingChange(pkt);
                    }
                }
                case eu.client.pingbypass.protocol.packets.C2SOpenInventoryPacket.ID -> {
                    var pkt = new eu.client.pingbypass.protocol.packets.C2SOpenInventoryPacket(buf);
                    Minecraft.getInstance().execute(() -> {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.player == null) return;
                        if (pkt.isOpen()) {
                            mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                        } else if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen inv
                                && inv.getMenu() == mc.player.inventoryMenu) {
                            mc.setScreen(null);
                        }
                    });
                }
                case eu.client.pingbypass.protocol.packets.C2SFriendSyncPacket.ID -> {
                    var pkt = new eu.client.pingbypass.protocol.packets.C2SFriendSyncPacket(buf);
                    Minecraft.getInstance().execute(() -> {
                        EUClient.FRIEND_MANAGER.clear();
                        for (String friend : pkt.getFriends()) EUClient.FRIEND_MANAGER.add(friend);
                        LOGGER.info("[PB] Synced {} friend(s) from client", pkt.getFriends().size());
                    });
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
