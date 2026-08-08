package eu.client.modules.impl.player;

import lombok.Getter;
import lombok.Setter;
import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.*;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.*;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.graphics.Renderer3D;
import eu.client.utils.minecraft.HoleUtils;
import eu.client.utils.minecraft.InventoryUtils;
import eu.client.utils.minecraft.NetworkUtils;
import eu.client.utils.minecraft.WorldUtils;
import eu.client.utils.rotations.RotationUtils;
import eu.client.utils.system.Timer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.GameType;

import java.awt.*;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

// No proxyEnhanced -- matches earthhack's Speedmine exactly: it has no user-facing Auto/Proxy/
// Local choice at all. Its ListenerUpdate/ListenerMotion gate on PingBypass.isConnected() (proxy
// side, automatic) and ClientDiggingService gates on the same "PingBypass connected" condition
// (client side, automatic) -- dual execution kicks in purely from being connected, never from a
// setting. See isDeferringToProxy()/isProxyActive() below.
@RegisterModule(name = "SpeedMine", description = "Automatically mines blocks at a faster speed using packets.", category = Module.Category.PLAYER)
public class SpeedMineModule extends Module {
    public ModeSetting switchMode = new ModeSetting("Switch", "The mode that will be used for automatically switching to the fastest item.", "Silent", InventoryUtils.SWITCH_MODES);
    public NumberSetting range = new NumberSetting("Range", "The maximum distance at which blocks will be mined.", 6.0, 0.0, 8.0);
    public NumberSetting speed = new NumberSetting("Speed", "The speed at which the module will mine blocks.", 1.0, 0.6, 1.0);
    public ModeSetting rotate = new ModeSetting("Rotate", "Automatically rotates to the block when mining it.", "Packet", new String[]{"None", "Normal", "Packet"});

    public BooleanSetting auto = new BooleanSetting("Auto", "Automatically mines blocks deemed optimal for defeating your opponents.", false);
    public BooleanSetting cityOnly = new BooleanSetting("CityOnly", "Only mines the target's city positions.", new BooleanSetting.Visibility(auto, true), false);
    public BooleanSetting bed = new BooleanSetting("Bed", "Also targets the 4 blocks beside the target's head, in case they anti-crystal with a bed up there.", new BooleanSetting.Visibility(auto, true), false);
    public BooleanSetting holeCheck = new BooleanSetting("HoleCheck", "Only mine the player in hole.", new BooleanSetting.Visibility(auto, true), false);
    public BooleanSetting switchReset = new BooleanSetting("SwitchReset", "Resets the mining when switching slots.", new ModeSetting.Visibility(switchMode, "None", "AltSwap", "AltPickup"), true);
    public BooleanSetting doubleMine = new BooleanSetting("Double", "Allows the mining of 2 blocks at the same time.", false);
    public ModeSetting sequence = new ModeSetting("Sequence", "Sequence of mining for double mine", new BooleanSetting.Visibility(doubleMine, true), "Surround", new String[]{"Surround", "Phase"});
    public BooleanSetting instant = new BooleanSetting("Instant", "Instantly mines blocks once they have been replaced.", false);
    public NumberSetting instantDelay = new NumberSetting("InstantDelay", "The amount of time that has to pass before instantly mining blocks.", new BooleanSetting.Visibility(instant, true), 0, 0, 20);
    public NumberSetting instantTimeout = new NumberSetting("InstantTimeout", "The amount of time that cancel instantly mine while no block to mine.", new BooleanSetting.Visibility(instant, true), 60, 0, 100);
    // Instant already stays primed (see process()'s air-check) instead of tearing down once the
    // position goes locally air, but it still WAITS for local confirmation the position is solid
    // again before re-firing -- a real round-trip (target's own place packet -> server -> back to
    // us) every single break. Async skips that confirmation entirely: fires the break burst blind,
    // on the InstantDelay clock alone, whether the position looks air or solid locally. Most shots
    // land on nothing (server no-ops a STOP_DESTROY_BLOCK with nothing destroying), but the one
    // that lands exactly as the target's block appears costs zero extra latency instead of a full
    // tick-plus-ping waiting to see it.
    public BooleanSetting async = new BooleanSetting("Async", "Fires break packets blind on InstantDelay alone, without waiting to locally confirm the block exists.", new BooleanSetting.Visibility(instant, true), false);
    public BooleanSetting grim = new BooleanSetting("Grim", "Adds a bypass catered to the Grim anticheat.", false);
    // Was dropped entirely during the 1.21.4 -> 26.1.2 port -- Instant relies on this to make the
    // block go locally-air the INSTANT we fire the break packet, not whenever the server's own
    // block-update packet round-trips back. Without it, mining==true (Instant deliberately never
    // clears it) but the position's BlockState never locally changes, so process() just re-fires
    // the same STOP_DESTROY_BLOCK/swing burst on the SAME stale block every tick instead of ever
    // seeing canBeReplaced()==true and cancelling out to let the caller re-target -- i.e. Instant
    // never actually reacts to a target re-placing the block, it just spams the old one.
    public BooleanSetting clientRemove = new BooleanSetting("ClientRemove", "Removes the block client-side immediately for instant visual feedback.", true);
    public BooleanSetting strict = new BooleanSetting("Strict", "Waits for the server to tick you before switching back.", false);
    public BooleanSetting whileEating = new BooleanSetting("WhileEating", "Mines blocks while eating.", true);
    public WhitelistSetting whitelist = new WhitelistSetting("Whitelist", "Mines only the blocks that are on this list. If empty, every block will be mined.", WhitelistSetting.Type.BLOCKS);

    public CategorySetting renderCategory = new CategorySetting("Render", "The category containing all settings related to rendering.");
    public ModeSetting render = new ModeSetting("Render", "Mode", "The rendering that will be applied to the blocks highlighted.", new CategorySetting.Visibility(renderCategory), "Both", new String[]{"None", "Fill", "Outline", "Both"});
    public ModeSetting animation = new ModeSetting("Animation", "The animation that will be used when rendering the block mining progress.", new ModeSetting.Visibility(render, "Fill", "Outline", "Both"), "Expand", new String[]{"None", "Expand", "Rise"});
    public ModeSetting color = new ModeSetting("Color", "The color that will be used when rendering the block mining.", new ModeSetting.Visibility(render, "Fill", "Outline", "Both"), "Smooth", new String[]{"Static", "Smooth", "Custom"});
    public ColorSetting fillColor = new ColorSetting("FillColor", "The color used for the fill rendering.", new ModeSetting.Visibility(render, "Fill", "Both"), ColorUtils.getDefaultFillColor());
    public ColorSetting outlineColor = new ColorSetting("OutlineColor", "The color used for the outline rendering.", new ModeSetting.Visibility(render, "Outline", "Both"), ColorUtils.getDefaultOutlineColor());
    public ModeSetting instantRender = new ModeSetting("InstantRender", "Instant", "The color that will be used for rendering instantly mined blocks.", new CategorySetting.Visibility(renderCategory), "None", new String[]{"None", "Default", "Custom"});
    public ColorSetting instantColor = new ColorSetting("InstantColor", "The custom color used for instantly mined blocks.", new ModeSetting.Visibility(instantRender, "Custom"), new ColorSetting.Color(new Color(148, 0, 211), false, false));

    @Getter private Action primary = null;
    @Getter private Action secondary = null;

    private SwitchAction switchAction = null;
    // Re-entrancy guard: cancel()/start() below send a ServerboundSetCarriedItemPacket themselves,
    // which re-fires this same PacketSendEvent.Post listener and matches the same condition --
    // without this guard that recurses forever (StackOverflowError).
    private boolean handlingSwitchReset = false;

    private final Timer instantTimer = new Timer();
    private final Timer mineTimer = new Timer();
    // Ported from Sydney-Legacy -- lets other modules cheaply detect "the block SpeedMine is
    // actually mining right now changed" without polling getPrimary()/getPosition() and diffing
    // it themselves every tick.
    private BlockPos lastPrimaryPosition = null;

    public BlockPos getMiningPosition() {
        return primary != null && primary.isMining() ? primary.getPosition() : null;
    }

    public boolean isPrimaryPositionChanged() {
        BlockPos current = getMiningPosition();
        if (current == null && lastPrimaryPosition == null) return false;
        if (current == null || lastPrimaryPosition == null) {
            lastPrimaryPosition = current;
            return true;
        }
        if (!current.equals(lastPrimaryPosition)) {
            lastPrimaryPosition = current;
            return true;
        }
        return false;
    }

    /**
     * When true, SpeedMine pauses mining to let the client eat/interact.
     * Set by PbPlayHandler when the client sends an interact packet,
     * cleared when the client sends RELEASE_USE_ITEM.
     */
    @Getter private volatile boolean interactPaused = false;
    private volatile long interactPausedAt = 0;
    private boolean needsRestart = false;

    // Only interactions that actually hold down "use" (eating, drinking, blocking,
    // bow...) send RELEASE_USE_ITEM afterward -- a plain right-click on a chest,
    // door, entity, etc. never does. If interactPaused was set for one of those,
    // there's no packet coming to ever clear it, so mining would stay paused
    // forever. Auto-expire the pause instead of waiting on a release that may
    // never arrive.
    private static final long INTERACT_PAUSE_TIMEOUT_MS = 750L;

    public void setInteractPaused(boolean paused) {
        this.interactPaused = paused;
        if (paused) {
            this.interactPausedAt = System.currentTimeMillis();
        } else {
            // When unpausing, flag that mining needs to restart
            // (re-send pickaxe + START_DESTROY to the server)
            this.needsRestart = true;
        }
    }

    // Proxy-synced render state for client-side rendering
    public volatile BlockPos proxyPrimaryPos = null;
    public volatile float proxyPrimaryProgress = 0;
    public volatile BlockPos proxySecondaryPos = null;
    public volatile float proxySecondaryProgress = 0;

    // Interpolation bookkeeping so proxy-synced progress (updated once per proxy
    // tick over the network) doesn't render as a hard step every frame.
    private volatile float prevProxyPrimaryProgress = 0;
    private volatile long proxyPrimaryUpdateTime = 0;
    private volatile long proxyPrimaryUpdateInterval = 50;
    private volatile float prevProxySecondaryProgress = 0;
    private volatile long proxySecondaryUpdateTime = 0;
    private volatile long proxySecondaryUpdateInterval = 50;

    /**
     * Called from the client-side S2C_MINING_STATE packet handler. Shifts the
     * previous progress value forward so renderProxyState can interpolate
     * between network updates instead of snapping.
     */
    public void updateProxyMiningState(BlockPos primaryPos, float primaryProgress,
                                       BlockPos secondaryPos, float secondaryProgress) {
        long now = System.currentTimeMillis();

        boolean primaryPosChanged = primaryPos == null ? proxyPrimaryPos != null : !primaryPos.equals(proxyPrimaryPos);
        prevProxyPrimaryProgress = primaryPosChanged ? primaryProgress : proxyPrimaryProgress;
        // Bounded above as well as below: proxyPrimaryUpdateTime starts at 0 (epoch), so the
        // very first update here would otherwise compute "now - 0" -- a multi-decade interval --
        // and interpolatedProgress's `t` would stay ~0 forever, looking exactly like "stuck, not
        // interpolating". The same bug recurs any time updates stop for a while (module toggled
        // off/on, network hiccup). Capping at the max interpolation window fixes both.
        proxyPrimaryUpdateInterval = Mth.clamp(now - proxyPrimaryUpdateTime, 1L, 500L);
        proxyPrimaryUpdateTime = now;
        proxyPrimaryPos = primaryPos;
        proxyPrimaryProgress = primaryProgress;

        boolean secondaryPosChanged = secondaryPos == null ? proxySecondaryPos != null : !secondaryPos.equals(proxySecondaryPos);
        prevProxySecondaryProgress = secondaryPosChanged ? secondaryProgress : proxySecondaryProgress;
        proxySecondaryUpdateInterval = Mth.clamp(now - proxySecondaryUpdateTime, 1L, 500L);
        proxySecondaryUpdateTime = now;
        proxySecondaryPos = secondaryPos;
        proxySecondaryProgress = secondaryProgress;
    }

    private float interpolatedProgress(float prev, float current, long updateTime, long interval) {
        float t = Mth.clamp((float) (System.currentTimeMillis() - updateTime) / interval, 0f, 1f);
        return prev + (current - prev) * t;
    }

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (isDeferringToProxy()) return;
        if (mc.player == null || mc.level == null) return;

        if (doubleMine.getValue() && secondary != null && secondary.process()) secondary = null;
        if (primary != null && primary.process()) primary = null;

        // Sync mining state to the client for rendering
        if (isProxyActive()) {
            syncMiningStateToClient();
        }

        if (!auto.getValue()) return;
        if ((primary != null && primary.getPriority() > 0 && !WorldUtils.isReplaceable(primary.getPosition())) || (secondary != null && secondary.getPriority() > 0 && !WorldUtils.isReplaceable(secondary.getPosition())))
            return;

        Target target = getTarget();

        if (doubleMine.getValue()) {
            if (!mineTimer.hasTimeElapsed(350L)) return;

            if (mc.player.isVisuallyCrawling()) {
                BlockPos position;
                BlockPos playerPosition = mc.player.blockPosition();

                if (WorldUtils.canBreak(playerPosition.below()) && !WorldUtils.isReplaceable(playerPosition.below()) && (!WorldUtils.isReplaceable(playerPosition.below(2)) || HoleUtils.getSingleHole(playerPosition.below(2), 1, false) != null)) {
                    position = playerPosition.below();
                } else {
                    position = playerPosition.above();
                }

                if (isValid(position) && !isOutOfRange(position)) {
                    if (!isInvalid(position)) handle(position, 0);
                    return;
                }
            }

            if ((primary != null && primary.isInstantMine() && !instantTimer.hasTimeElapsed(instantTimeout.getValue().longValue() * 50L) && primary.getAttempts() != 0) || secondary != null) return;

            if (target != null) {
                Runnable inside = () -> {
                    List<BlockPos> insidePositions = HoleUtils.getInsidePositions(target.player()).stream().filter(insidePosition -> !mc.level.getBlockState(insidePosition).canBeReplaced()).toList();;
                    for (BlockPos position : insidePositions) {
                        if (primary != null && secondary != null) break;
                        if (isInvalid(position) || isOutOfRange(position)) continue;
                        handle(position, 0);
                    }
                };
                Runnable outside = () -> {
                    List<BlockPos> surroundPositions = HoleUtils.getFeetPositions(target.player(), true, false, true).stream().filter(pos -> !mc.level.getBlockState(pos).canBeReplaced()).toList();
                    if (HoleUtils.isPlayerInHole(target.player()) || !holeCheck.getValue()) {
                        for (BlockPos position : surroundPositions) {
                            if (primary != null && secondary != null) break;
                            if (isMining(position)) continue;
                            if (isInvalid(position) || isOutOfRange(position)) continue;
                            handle(position, 0);
                        }
                    }
                };
                // Bed: the enemy phasing their HEAD into a 2-tall gap isn't covered by `inside`
                // (that's feet-level airgaps only, HoleUtils.getInsidePositions' offsets are all
                // Y < feet) or `outside` (feet-level ring). Break the actual head-level block
                // they're standing inside, plus one of the 4 NSWE blocks beside it -- their own
                // anti-crystal bed, if that's what's there.
                Runnable bed = () -> {
                    if (!SpeedMineModule.this.bed.getValue()) return;

                    BlockPos head = target.player().blockPosition().offset(0, 2, 0);
                    List<BlockPos> headPositions = new ArrayList<>();
                    if (!mc.level.getBlockState(head).canBeReplaced()) headPositions.add(head);
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        BlockPos side = head.relative(dir);
                        if (!mc.level.getBlockState(side).canBeReplaced()) headPositions.add(side);
                    }

                    for (BlockPos position : headPositions) {
                        if (primary != null && secondary != null) break;
                        if (isMining(position)) continue;
                        if (isInvalid(position) || isOutOfRange(position)) continue;
                        handle(position, 0);
                    }
                };

                if (sequence.getValue().equals("Surround")) {
                    outside.run();
                    inside.run();
                } else if (sequence.getValue().equals("Phase")) {
                    inside.run();
                    outside.run();
                }
                bed.run();
            }
        } else {
            BlockPos position = null;

            if (target == null) {
                return;
            } else {
                if (!WorldUtils.isReplaceable(target.player.blockPosition()) && !WorldUtils.getBlock(target.player().blockPosition()).equals(Blocks.COBWEB)) {
                    position = target.player().blockPosition();
                } else if (HoleUtils.isPlayerInHole(target.player()) || !holeCheck.getValue()) {
                    position = target.position();
                }
            }
            if (position == null) return;
            if (primary != null && position.equals(primary.getPosition()))
                return;

            handle(position, 0);
        }
    }

    @SubscribeEvent(priority = Integer.MAX_VALUE)
    public void onTick(TickEvent event) {
        if (isDeferringToProxy()) return;
        if (switchAction == null) return;
        if (System.currentTimeMillis() - switchAction.time() < 100L)
            return;

        if (mc.player != null && mc.level != null && (switchAction.slot() != -1 && switchAction.previousSlot() != -1)) {
            InventoryUtils.switchBack(switchMode.getValue(), switchAction.slot(), switchAction.previousSlot());
        }

        switchAction = null;
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        if (mc.player == null || mc.level == null) return;

        if (isDeferringToProxy()) {
            // Client side: render using proxy-synced state
            renderProxyState(event.getMatrices());
            return;
        }

        if (doubleMine.getValue() && secondary != null) secondary.render(event.getMatrices());
        if (primary != null) primary.render(event.getMatrices());
    }

    @SubscribeEvent
    public void onPacketSend(PacketSendEvent.Post event) {
        if (isDeferringToProxy()) return;
        if (mc.player == null || mc.level == null) return;

        if (handlingSwitchReset) return;

        if (event.getPacket() instanceof ServerboundSetCarriedItemPacket && switchReset.getValue() && (switchMode.getValue().equalsIgnoreCase("AltSwap") || switchMode.getValue().equalsIgnoreCase("AltPickup"))) {
            handlingSwitchReset = true;
            try {
                if (secondary != null) {
                    secondary.cancel();
                    secondary.start();
                }

                if (primary != null) {
                    primary.cancel();
                    primary.start();
                }
            } finally {
                handlingSwitchReset = false;
            }
        }
    }

    @SubscribeEvent
    public void onAttackBlock(AttackBlockEvent event) {
        if (isDeferringToProxy()) return;
        if (mc.player == null || mc.level == null) return;

        if (handle(event.getPosition(), 1)) {
            event.setCancelled(true);
        }
    }

    @Override
    public void onDisable() {
        if (isDeferringToProxy()) return;
        if (mc.player == null || mc.level == null) {
            primary = null;
            secondary = null;
            return;
        }

        // There was no onDisable at all before. Toggling the module off mid-mine just dropped
        // the in-flight Actions on the floor -- and since start() intentionally leaves the REAL
        // SERVER holding the pickaxe until process() switches back, killing the module before
        // that point stranded the server on the pickaxe permanently. Everything that placed
        // blocks afterwards (AutoCrystal especially) then silently placed nothing while the
        // proxy kept predicting the item was used. Cancel properly so the slot gets restored.
        if (secondary != null) {
            secondary.cancel();
            secondary = null;
        }
        if (primary != null) {
            primary.cancel();
            primary = null;
        }

        setInteractPaused(false);
    }

    @Override
    public String getMetaData() {
        String primaryProgress = primary == null ? "0.0" : new DecimalFormat("0.0").format(primary.getProgress() / primary.getSpeed());
        String secondaryProgress = secondary == null || !doubleMine.getValue() ? "" : ", " + new DecimalFormat("0.0").format(secondary.getProgress() / secondary.getSpeed());
        return primaryProgress + secondaryProgress;
    }

    private boolean handle(BlockPos position, int priority) {
        if (mc.gameMode.getPlayerMode() == GameType.CREATIVE || mc.gameMode.getPlayerMode() == GameType.SPECTATOR) return false;
        if (mc.level.getBlockState(position).getBlock().defaultDestroyTime() == -1) return false;
        if (!whitelist.getWhitelist().isEmpty() && !whitelist.isWhitelistContains(mc.level.getBlockState(position).getBlock())) return false;
        if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(position)) > Mth.square(range.getValue().doubleValue()))
            return false;

        if ((primary != null && primary.getPosition().equals(position)) || (secondary != null && secondary.getPosition().equals(position))) return true;

        if (doubleMine.getValue()) {
            if (secondary != null) {
                primary = new Action(position, priority);
            } else {
                if (primary != null) {
                    if (!primary.isInstantMine()) secondary = primary;
                    primary = new Action(position, priority);
                } else {
                    primary = new Action(position, priority);
                }
            }
        } else {
            if (primary != null) primary.cancel();
            primary = new Action(position, priority);
        }

        return true;
    }

    private boolean isInvalid(BlockPos position) {
        if (!isValid(position)) return true;
        return isMining(position);
    }

    private boolean isValid(BlockPos position) {
        if (position == null) return false;
        if (mc.level.getBlockState(position).getBlock().defaultDestroyTime() == -1) return false;
        return !mc.level.getBlockState(position).getBlock().equals(Blocks.COBWEB);
    }

    private boolean isMining(BlockPos position) {
        if (position == null) return true;
        if (primary != null && primary.getPosition().equals(position)) return true;
        return secondary != null && secondary.getPosition().equals(position);
    }

    private boolean isOutOfRange(BlockPos position) {
        if (position == null) return true;
        return mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(position)) > Mth.square(range.getValue().doubleValue());
    }

    private Target getTarget() {
        Target optimalTarget = null;
        for (Player player : mc.level.players()) {
            if (player == mc.player) continue;
            if (!player.isAlive() || player.getHealth() <= 0.0f) continue;
            if (mc.player.distanceToSqr(player) > Mth.square(range.getValue().doubleValue() + 2.0)) continue;
            if (EUClient.FRIEND_MANAGER.contains(player.getName().getString())) continue;

            List<Position> feetPositions = getPositions(player);
            BlockPos position = getTargetPosition(feetPositions);

            if (!doubleMine.getValue()) {
                if (feetPositions.isEmpty()) continue;
                if (position == null) continue;
            }

            if (optimalTarget == null) {
                optimalTarget = new Target(player, feetPositions, position);
                continue;
            }

            if (mc.player.distanceToSqr(player) < mc.player.distanceToSqr(optimalTarget.player())) {
                optimalTarget = new Target(player, feetPositions, position);
            }
        }

        return optimalTarget;
    }

    private BlockPos getTargetPosition(List<Position> positions) {
        BlockPos optimalPosition = null;
        double optimalScore = 0.0;

        for (Position position : positions) {
            if ((doubleMine.getValue() || cityOnly.getValue()) && !position.feetPosition()) continue;
            if (!isValidPosition(position.position())) continue;
            if (HoleUtils.isPlayerInHole(mc.player) && HoleUtils.getFeetPositions(mc.player, true, false, true).contains(position.position())) continue;

            double score = 0.0;

            if (position.feetPosition()) {
                score += 0.05;

                if (mc.level.getBlockState(position.position()).getBlock() == Blocks.ENDER_CHEST) score += 0.95;
                else if (WorldUtils.isCrystalPlaceable(position.position().offset(0, 1, 0))) score += 0.35;
                if (hasCityPosition(position.position())) score += 0.6;
            } else {
                if (mc.level.getBlockState(position.position()).getBlock() == Blocks.ENDER_CHEST) {
                    score -= 2.0;
                } else {
                    if (WorldUtils.isCrystalPlaceable(position.position().offset(0, 1, 0))) score += 0.75;
                    else score -= 2.0;
                }
            }

            if (score >= optimalScore) {
                optimalPosition = position.position();
                optimalScore = score;
            }
        }

        return optimalPosition;
    }

    private List<Position> getPositions(Player player) {
        List<Position> positions = new ArrayList<>();

        for (BlockPos position : HoleUtils.getFeetPositions(player, true, false, true)) {
            positions.add(new Position(position, true));
            if (!doubleMine.getValue()) positions.add(new Position(position.offset(0, 1, 0), false));
        }

        if (!doubleMine.getValue()) positions.add(new Position(player.blockPosition().offset(0, 2, 0), false));

        if (bed.getValue() && !doubleMine.getValue()) {
            BlockPos head = player.blockPosition().offset(0, 2, 0);
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                positions.add(new Position(head.relative(dir), false));
            }
        }

        return positions;
    }

    private boolean isValidPosition(BlockPos position) {
        if (mc.level.getBlockState(position).canBeReplaced()) return false;
        if (mc.level.getBlockState(position).getBlock().defaultDestroyTime() == -1) return false;
        return !isOutOfRange(position);
    }

    private boolean hasCityPosition(BlockPos position) {
        Vec3i[] offsets = new Vec3i[]{new Vec3i(1, 0, 0), new Vec3i(-1, 0, 0), new Vec3i(0, 0, 1), new Vec3i(0, 0, -1)};

        for (Vec3i vec3i : offsets) {
            BlockPos offsetPosition = position.offset(vec3i);
            if (WorldUtils.isPlaceable(offsetPosition)) return true;
        }

        return false;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Proxy-sided packet helpers.
    // When the proxy is forwarding for a client, SpeedMine's packets
    // are sent DIRECTLY to the server connection, completely bypassing
    // mc.getConnection().send(). This means:
    //   - The proxy's local mc.player state is never touched
    //   - mc.player.getInventory().getSelectedSlot() stays in sync with the client
    //   - The client never sees slot switches, arm swings, or rotations
    //   - The server sees the atomic switch→mine→switchback burst
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Returns true when this module is executing on the proxy server. Matches earthhack's
     * ListenerUpdate/ListenerMotion guard (PingBypass.isConnected(), i.e. server && connected) --
     * automatic, no ProxyMode setting to check.
     */
    private boolean isProxyActive() {
        return eu.client.pingbypass.PingBypassFlags.proxyForwardingActive
                && EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer()
                && EUClient.PROXY_SERVER != null;
    }

    /**
     * Returns true on the CLIENT when it's connected to a PingBypass proxy -- the client should
     * defer its own raw digging execution to the proxy (matches earthhack's ClientDiggingService,
     * which cancels the client's own CPacketPlayerDigging sends under the same condition).
     */
    private boolean isDeferringToProxy() {
        return eu.client.pingbypass.PingBypassFlags.isPingBypassActive();
    }

    /**
     * Sends the current mining state (positions + progress) to the connected
     * client so it can render mining progress boxes.
     */
    private void syncMiningStateToClient() {
        var packet = new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                eu.client.pingbypass.protocol.PbCustomPayload.fromPacket(
                        new eu.client.pingbypass.protocol.packets.S2CMiningStatePacket(
                                primary != null ? primary.getPosition() : null,
                                primary != null ? primary.getProgress() / primary.getSpeed() : 0,
                                secondary != null ? secondary.getPosition() : null,
                                secondary != null ? secondary.getProgress() / secondary.getSpeed() : 0)));
        for (net.minecraft.network.Connection conn : EUClient.PROXY_SERVER.getConnections()) {
            if (conn.isConnected()) conn.send(packet);
        }
    }

    /**
     * Renders mining progress on the client using proxy-synced state.
     */
    private void renderProxyState(PoseStack matrices) {
        if (proxyPrimaryPos != null) {
            float progress = interpolatedProgress(prevProxyPrimaryProgress, proxyPrimaryProgress, proxyPrimaryUpdateTime, proxyPrimaryUpdateInterval);
            renderProxyBlock(matrices, proxyPrimaryPos, progress);
        }
        if (proxySecondaryPos != null && doubleMine.getValue()) {
            float progress = interpolatedProgress(prevProxySecondaryProgress, proxySecondaryProgress, proxySecondaryUpdateTime, proxySecondaryUpdateInterval);
            renderProxyBlock(matrices, proxySecondaryPos, progress);
        }
    }

    private void renderProxyBlock(PoseStack matrices, BlockPos pos, float progress) {
        if (mc.level.getBlockState(pos).canBeReplaced()) return;

        AABB box = new AABB(pos);
        if (animation.getValue().equalsIgnoreCase("Expand")) box = new AABB(pos).deflate(0.5).inflate(Mth.clamp(progress / 2.0, 0.0, 0.5));
        if (animation.getValue().equalsIgnoreCase("Rise")) box = new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0, pos.getY() + progress, pos.getZ() + 1.0);

        Color fill = fillColor.getColor();
        Color outline = outlineColor.getColor();

        if (color.getValue().equalsIgnoreCase("Static")) {
            fill = progress >= 0.9 ? new Color(0, 255, 0, fillColor.getAlpha()) : new Color(255, 0, 0, fillColor.getAlpha());
            outline = progress >= 0.9 ? new Color(0, 255, 0, outlineColor.getAlpha()) : new Color(255, 0, 0, outlineColor.getAlpha());
        } else if (color.getValue().equalsIgnoreCase("Smooth")) {
            fill = new Color(255 - (int) (Mth.clamp(progress, 0.0f, 1.0f) * 255), (int) (Mth.clamp(progress, 0.0f, 1.0f) * 255), 0, fillColor.getAlpha());
            outline = new Color(255 - (int) (Mth.clamp(progress, 0.0f, 1.0f) * 255), (int) (Mth.clamp(progress, 0.0f, 1.0f) * 255), 0, outlineColor.getAlpha());
        }

        if (render.getValue().equalsIgnoreCase("Fill") || render.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBox(matrices, box, fill);
        if (render.getValue().equalsIgnoreCase("Outline") || render.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBoxOutline(matrices, box, outline);
    }

    /**
     * Sends a packet directly to the real server connection when on the proxy,
     * bypassing the proxy's local ClientPlayNetworkHandler. Falls back to
     * normal send when running locally.
     */
    private void serverSend(net.minecraft.network.protocol.Packet<?> packet) {
        if (isProxyActive()) {
            var serverConn = EUClient.PROXY_SERVER.getServerConnection();
            if (serverConn != null && serverConn.isConnected()) {
                eu.client.pingbypass.server.ProxyServerTickListener.allowSend(() -> serverConn.send(packet));
                return;
            }
        }
        mc.getConnection().send(packet);
    }

    /**
     * Sends a sequenced packet directly to the server connection when on the proxy.
     */
    private void serverSendSequenced(java.util.function.IntFunction<net.minecraft.network.protocol.Packet<?>> packetFactory) {
        if (isProxyActive()) {
            try (var pending = ((eu.client.mixins.accessors.ClientWorldAccessor) mc.level)
                    .invokeGetPendingUpdateManager().startPredicting()) {
                serverSend(packetFactory.apply(pending.currentSequence()));
            }
        } else {
            NetworkUtils.sendSequencedPacket(seq -> {
                @SuppressWarnings("unchecked")
                var p = (net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ServerGamePacketListener>) packetFactory.apply(seq);
                return p;
            });
        }
    }

    @Getter
    public class Action {
        private final BlockPos position;
        private BlockState state;
        private final int priority;

        @Setter private float progress;
        private float prevProgress;
        private int attempts;
        private boolean mining;
        private long stallTime;

        private boolean instantMine;
        private int startSlot = -1; // slot we switched FROM at start

        public Action(BlockPos position, int priority) {
            this.position = position;
            this.state = mc.level.getBlockState(position);
            this.priority = priority;

            start();
        }

        public boolean process() {
            if (isOutOfRange(position)) {
                cancel();
                return true;
            }

            // If the client is eating/interacting, pause mining — don't send
            // any packets that would change the server's slot state. Auto-expire
            // the pause if no RELEASE_USE_ITEM ever arrives (see interactPausedAt) --
            // but only once the client isn't actually mid-use anymore. A real eat
            // (golden apple etc) takes ~1.6s, well past the timeout; resuming on a
            // fixed clock switches the held item back to the pickaxe WHILE the eat
            // animation is still playing and cancels it outright.
            if (interactPaused) {
                boolean stillUsing = mc.player != null && mc.player.isUsingItem();
                if (!stillUsing && System.currentTimeMillis() - interactPausedAt >= INTERACT_PAUSE_TIMEOUT_MS) {
                    setInteractPaused(false);
                } else {
                    return false;
                }
            }

            // After unpausing (client finished eating), restart mining from
            // scratch so the server recalculates with pickaxe speed.
            if (needsRestart && isProxyActive()) {
                needsRestart = false;
                start();
                return false;
            }

            boolean secondary = getSecondary() != null && position.equals(getSecondary().getPosition());
            if (secondary) instantMine = false;

            // Block is broken (air) — clean up and switch back to client's slot. EXCEPT while
            // instantMine is priming: that's exactly the tick right after ClientRemove marks our
            // own break as air LOCALLY, before the target has rebuilt anything there yet -- tearing
            // the Action down here (progress/attempts/instantMine all zeroed by cancel(), primary
            // nulled by the caller) threw away the "already fully progressed" state that's the
            // entire point of Instant, forcing a full re-mine from 0 the next time this position
            // got targeted instead of an instant re-break the moment it solidifies again. Stay
            // primed at full progress and idle here instead; only actually give up once nothing
            // gets rebuilt for InstantTimeout (0 = never give up, matches the user just wanting
            // max-aggression instant mining with no watchdog at all).
            if (mc.level.getBlockState(position).canBeReplaced()) {
                if (instantMine) {
                    long timeoutTicks = instantTimeout.getValue().longValue();
                    boolean timedOut = timeoutTicks > 0 && instantTimer.hasTimeElapsed(timeoutTicks * 50L);
                    if (!timedOut) {
                        // Async: fire blind on the InstantDelay clock instead of idling here
                        // waiting for the position to locally solidify again.
                        if (async.getValue() && instantTimer.hasTimeElapsed(instantDelay.getValue().longValue() * 50L)) {
                            Direction blindDirection = WorldUtils.getClosestDirection(position, true);
                            int blindSlot = switchMode.getValue().equalsIgnoreCase("None") ? -1 : InventoryUtils.findFastestItem(this.state, InventoryUtils.HOTBAR_START, switchMode.getValue().equalsIgnoreCase("AltSwap") || switchMode.getValue().equalsIgnoreCase("AltPickup") ? InventoryUtils.INVENTORY_END : InventoryUtils.HOTBAR_END);
                            if (blindSlot == -1) blindSlot = mc.player.getInventory().getSelectedSlot();
                            fireBreakBurst(blindDirection, blindSlot, false);
                            instantTimer.reset();
                            attempts++;
                        }
                        return false;
                    }
                }

                if (isProxyActive()) {
                    // Switch server back to the client's actual slot
                    serverSend(new ServerboundSetCarriedItemPacket(mc.player.getInventory().getSelectedSlot()));
                }
                if (switchAction != null) {
                    switchAction = null;
                }
                cancel();
                return true;
            }

            Direction direction = WorldUtils.getClosestDirection(position, true);
            BlockState state = mc.level.getBlockState(position);

            if (!state.canBeReplaced() && state.getBlock() != this.state.getBlock()) {
                this.state = state;
            }

            if (mining) {
                int slot = switchMode.getValue().equalsIgnoreCase("None") ? -1 : InventoryUtils.findFastestItem(this.state, InventoryUtils.HOTBAR_START, switchMode.getValue().equalsIgnoreCase("AltSwap") || switchMode.getValue().equalsIgnoreCase("AltPickup") ? InventoryUtils.INVENTORY_END : InventoryUtils.HOTBAR_END);
                if (slot == -1) slot = mc.player.getInventory().getSelectedSlot();

                float delta = WorldUtils.getMineSpeed(this.state, slot) / EUClient.WORLD_MANAGER.getTimerMultiplier();

                prevProgress = progress;
                progress = Mth.clamp(progress + delta, 0.0f, getSpeed());

                if (rotate.getValue().equalsIgnoreCase("Normal") && progress + (delta * 2) >= getSpeed()) {
                    if (isProxyActive()) {
                        float[] rots = RotationUtils.getRotations(WorldUtils.getHitVector(position, direction));
                        // Rot-only (no X/Y/Z) -- see RotationManager.packetRotate's comment.
                        // Sending position built from mc.player's proxy-mirrored coordinates
                        // races the client's own movement packets already being forwarded,
                        // causing rubberbanding on the real server.
                        serverSend(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                                rots[0], rots[1],
                                eu.client.pingbypass.PingBypassFlags.clientOnGround,
                                eu.client.pingbypass.PingBypassFlags.clientHorizontalCollision));
                    } else {
                        EUClient.ROTATION_MANAGER.rotate(RotationUtils.getRotations(WorldUtils.getHitVector(position, direction)), EUClient.ROTATION_MANAGER.getModulePriority(EUClient.MODULE_MANAGER.getModule(SpeedMineModule.class)));
                    }
                }

                // AltSwap/AltPickup/Normal complete mining by swapping the fast tool into
                // mc.player.getInventory().getSelectedSlot() -- read live, right here, whatever
                // that currently is. WhileEating lets progress keep accumulating while the player
                // is mid-use-item, but if the player selected e.g. an apple to eat, THAT slot is
                // what's currently selected -- completing mining while still using an item swapped
                // the pickaxe straight into the apple's slot (the apple and pickaxe visibly
                // traded places, mid-chew). Silent/None never touch the client's own inventory
                // positions/selection at all, so they're unaffected. Let progress keep climbing
                // (uncapped above) but hold off actually completing (and switching) until the item
                // use finishes for the modes that do.
                boolean switchTouchesInventory = switchMode.getValue().equalsIgnoreCase("Normal")
                        || switchMode.getValue().equalsIgnoreCase("AltSwap") || switchMode.getValue().equalsIgnoreCase("AltPickup");
                if (progress >= getSpeed() && !state.canBeReplaced() && (whileEating.getValue() || !mc.player.isUsingItem())
                        && !(switchTouchesInventory && mc.player.isUsingItem())) {
                    if (!instantMine || instantTimer.hasTimeElapsed(instantDelay.getValue().longValue() * 50L)) {
                        fireBreakBurst(direction, slot, secondary);
                        if (!instantMine || secondary) mineTimer.reset();
                    }

                    attempts++;
                    if (!secondary) {
                        if (!instant.getValue()) {
                            // Don't restart immediately — wait for the server to confirm
                            // the break (block becomes air). The process() loop will detect
                            // the block is replaceable and return true on the next tick.
                            this.mining = false;
                            this.stallTime = System.currentTimeMillis();
                        } else {
                            this.instantMine = true;
                            instantTimer.reset();
                        }
                    }

                    return doubleMine.getValue() && secondary;
                }
            } else {
                // Only restart if the block is still there and we haven't just sent STOP -- OR
                // the block still hasn't broken 1s after we did (attempts > 0 forever blocked the
                // retry here otherwise: once mining stalls out, attempts is never reset back to 0
                // except by start() itself, so a block the server refuses to break -- packet loss,
                // desync, anticheat rejection -- got stuck at full progress forever with no way
                // to recover on its own).
                if (!mc.level.getBlockState(position).canBeReplaced() && (attempts == 0 || System.currentTimeMillis() - stallTime >= 1000L)) {
                    start();
                }
            }

            return false;
        }

        // Extracted out of process()'s completion branch so Async's blind re-fire (see the
        // air-check above) can send the exact same rotate/switch/STOP_DESTROY/grim/swing/
        // clientRemove burst without duplicating it -- the two call sites used to drift out of
        // sync being separate copies.
        private void fireBreakBurst(Direction direction, int slot, boolean secondary) {
            EUClient.EVENT_HANDLER.post(new DestroyBlockEvent(position));

            if (rotate.getValue().equalsIgnoreCase("Packet")) {
                if (isProxyActive()) {
                    float[] rots = RotationUtils.getRotations(WorldUtils.getHitVector(position, direction));
                    // Rot-only, same reason as the "Normal" branch in process() -- PosRot's X/Y/Z
                    // here raced the client's own forwarded movement packets.
                    serverSend(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                            rots[0], rots[1],
                            eu.client.pingbypass.PingBypassFlags.clientOnGround,
                            eu.client.pingbypass.PingBypassFlags.clientHorizontalCollision));
                } else {
                    EUClient.ROTATION_MANAGER.packetRotate(RotationUtils.getRotations(WorldUtils.getHitVector(position, direction)));
                }
            }

            int previousSlot = mc.player.getInventory().getSelectedSlot();

            if (isProxyActive()) {
                // Use startSlot (captured in start(), before anything switched) as the real
                // "previous" slot to restore -- previousSlot above was just read live, which by
                // now is the pickaxe slot start() already switched to, not what the player
                // actually had selected.
                int realPreviousSlot = startSlot != -1 ? startSlot : previousSlot;
                int mineSlot = switchMode.getValue().equalsIgnoreCase("None") ? -1 : slot;

                // EUClient.EVENT_HANDLER.post(new DestroyBlockEvent(...)) above runs its listeners
                // SYNCHRONOUSLY -- AutoCrystalModule.onDestroyBlock is one of them, and with
                // Switch=Normal it selects the crystal slot and (by design, see InventoryUtils'
                // comment on switchBack's Normal no-op) leaves it selected afterward. That happens
                // BEFORE this code runs. Always re-assert the mining slot here regardless of what
                // it "should" already be -- it costs one extra packet on the (rare) already-correct
                // case, but is the only way to be right after another module reselected mid-event.
                if (mineSlot != -1) {
                    serverSend(new ServerboundSetCarriedItemPacket(mineSlot));
                    mc.player.getInventory().setSelectedSlot(mineSlot);
                }

                serverSendSequenced(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, position, direction, seq));
                if (grim.getValue()) serverSend(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, position.above(500), direction));
                serverSend(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));

                if (mineSlot != -1) {
                    serverSend(new ServerboundSetCarriedItemPacket(realPreviousSlot));
                    mc.player.getInventory().setSelectedSlot(realPreviousSlot);
                }
            } else {
                InventoryUtils.switchSlot(switchMode.getValue(), slot, previousSlot);

                NetworkUtils.sendSequencedPacket(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, position, direction, seq));
                if (grim.getValue()) mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, position.above(500), direction));
                mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));

                if (strict.getValue() || (doubleMine.getValue() && secondary)) switchAction = new SwitchAction(slot, previousSlot, System.currentTimeMillis());
                else if (switchAction == null) InventoryUtils.switchBack(switchMode.getValue(), slot, previousSlot);
            }

            // Remove block client-side so modules (and process()'s own top-of-loop
            // canBeReplaced() check) see it as air immediately instead of waiting on the server's
            // block-update round-trip.
            if (clientRemove.getValue()) {
                mc.level.removeBlock(position, false);
            }
        }

        public void render(PoseStack matrices) {
            if (mc.level.getBlockState(position).canBeReplaced() && (!instantMine || instantRender.getValue().equalsIgnoreCase("None")))
                return;

            AABB box = new AABB(position);
            double progress = Mth.lerp(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false), prevProgress / getSpeed(), this.progress / getSpeed());

            if (animation.getValue().equalsIgnoreCase("Expand")) box = new AABB(position).deflate(0.5).inflate(Mth.clamp(progress / 2.0, 0.0, 0.5));
            if (animation.getValue().equalsIgnoreCase("Rise")) box = new AABB(position.getX(), position.getY(), position.getZ(), position.getX() + 1.0, position.getY() + progress, position.getZ() + 1.0);

            Color fill = fillColor.getColor();
            Color outline = outlineColor.getColor();

            if (color.getValue().equalsIgnoreCase("Static")) {
                fill = progress >= 0.9 ? new Color(0, 255, 0, fillColor.getAlpha()) : new Color(255, 0, 0, fillColor.getAlpha());
                outline = progress >= 0.9 ? new Color(0, 255, 0, outlineColor.getAlpha()) : new Color(255, 0, 0, outlineColor.getAlpha());
            } else if (color.getValue().equalsIgnoreCase("Smooth")) {
                fill = new Color(255 - (int) (Mth.clamp(progress, 0.0f, 1.0f) * 255), (int) (Mth.clamp(progress, 0.0f, 1.0f) * 255), 0, fillColor.getAlpha());
                outline = new Color(255 - (int) (Mth.clamp(progress, 0.0f, 1.0f) * 255), (int) (Mth.clamp(progress, 0.0f, 1.0f) * 255), 0, outlineColor.getAlpha());
            }

            if (progress >= getSpeed() && instantMine && instantRender.getValue().equalsIgnoreCase("Custom")) {
                fill = ColorUtils.getColor(instantColor.getColor(), fillColor.getAlpha());
                outline = ColorUtils.getColor(instantColor.getColor(), outlineColor.getAlpha());
            }

            if (render.getValue().equalsIgnoreCase("Fill") || render.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBox(matrices, box, fill);
            if (render.getValue().equalsIgnoreCase("Outline") || render.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBoxOutline(matrices, box, outline);
        }

        public void start() {
            Direction direction = WorldUtils.getClosestDirection(position, true);

            if (isProxyActive()) {
                // Matches the local (non-proxy) branch below: no slot switch here at all.
                // Holding the pickaxe on the real server for the ENTIRE mining duration (from
                // start() through to STOP_DESTROY in process()) used to be deliberate here, to
                // make the server compute progress off the pickaxe every tick -- but it meant
                // any OTHER proxy-side module that reselects the slot mid-mine (AutoCrystal's
                // Normal switch, triggered synchronously off DestroyBlockEvent) permanently wins
                // the fight over the real server's held item, since nothing here expected the
                // slot to move out from under it. That produced "server stuck on crystal/
                // pickaxe forever, SpeedMine mining speed wrong, or crystals silently rejected
                // while the proxy still predicted they were consumed" bugs. The vanilla trick
                // this whole module is built on doesn't actually need the fast tool held while
                // mining -- only briefly, right when STOP_DESTROY_BLOCK is sent, so the server's
                // destroy-progress calculation at that exact tick uses it. Switching only for
                // that instant (like local mode already does) removes the multi-tick window
                // other modules could steal the slot during.
                startSlot = mc.player.getInventory().getSelectedSlot();

                if (doubleMine.getValue()) {
                    serverSendSequenced(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, position, direction, seq));
                    serverSendSequenced(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, position, direction, seq));
                    serverSendSequenced(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, position, direction, seq));
                } else {
                    serverSendSequenced(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, position, direction, seq));
                }

                serverSend(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
            } else {
                // Local: no slot switch in start(), only at STOP_DESTROY moment
                if (doubleMine.getValue()) {
                    NetworkUtils.sendSequencedPacket(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, position, direction, seq));
                    NetworkUtils.sendSequencedPacket(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, position, direction, seq));
                    NetworkUtils.sendSequencedPacket(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, position, direction, seq));
                } else {
                    NetworkUtils.sendSequencedPacket(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, position, direction, seq));
                }
                mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
            }

            this.progress = 0.0f;
            this.prevProgress = 0.0f;
            this.attempts = 0;
            this.mining = true;
            this.instantMine = false;
        }

        public void cancel() {
            if (!doubleMine.getValue()) {
                if (isProxyActive()) {
                    serverSendSequenced(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, position, WorldUtils.getClosestDirection(position, true), seq));
                    serverSend(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                } else {
                    NetworkUtils.sendSequencedPacket(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, position, WorldUtils.getClosestDirection(position, true), seq));
                    mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                }
            }

            // start() deliberately leaves the REAL SERVER holding the pickaxe ("Do NOT switch
            // back here") and relies on process() to restore it after STOP_DESTROY. Any path
            // that ends the action early therefore has to do that restore itself, or the server
            // keeps thinking a pickaxe is in hand indefinitely -- at which point AutoCrystal's
            // ServerboundUseItemOnPacket places nothing (server sees a pickaxe), while the
            // proxy's local model still predicts the crystal was consumed: crystals vanish into
            // nothing until clicking the hotbar slot forces a resync and the server hands the
            // whole stack back.
            //
            // Restore to startSlot, NOT getSelectedSlot(): start() already mirrored the pickaxe
            // slot into mc.player, so reading it live here would "restore" the pickaxe onto
            // itself and leave the desync in place. Runs regardless of doubleMine -- the switch
            // in start() isn't conditional on it either.
            if (isProxyActive() && startSlot != -1) {
                serverSend(new ServerboundSetCarriedItemPacket(startSlot));
                mc.player.getInventory().setSelectedSlot(startSlot);
                startSlot = -1;
            }

            this.progress = 0.0f;
            this.prevProgress = 0.0f;
            this.attempts = 0;
            this.mining = false;

            this.instantMine = false;
        }

        private float getSpeed() {
            return getSecondary() != null && position.equals(getSecondary().getPosition()) ? 1.0f : speed.getValue().floatValue();
        }

        // For AutoCrystalModule's MineIgnore: approximates ticks left before this target breaks,
        // using the SAME per-tick delta formula process() itself uses (WorldUtils.getMineSpeed off
        // the currently-selected slot / the world's timer multiplier). Not exact -- delta can shift
        // tick to tick if switchMode picks a different item, or if the timer multiplier changes --
        // but close enough for a coarse "N ticks left" trigger threshold, and cheap (no scan).
        public int getTicksRemaining() {
            if (!mining) return Integer.MAX_VALUE;

            float delta = WorldUtils.getMineSpeed(state, mc.player.getInventory().getSelectedSlot()) / EUClient.WORLD_MANAGER.getTimerMultiplier();
            if (delta <= 0.0f) return Integer.MAX_VALUE;

            return Math.max(0, Math.round((getSpeed() - progress) / delta));
        }
    }

    private record SwitchAction(int slot, int previousSlot, long time) { }

    private record Target(Player player, java.util.List<Position> feetPositions, BlockPos position) { }
    private record Position(BlockPos position, boolean feetPosition) { }
}
