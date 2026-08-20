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
import eu.client.utils.minecraft.EntityUtils;
import eu.client.utils.minecraft.HoleUtils;
import eu.client.utils.minecraft.InventoryUtils;
import eu.client.utils.minecraft.NetworkUtils;
import eu.client.utils.minecraft.WorldUtils;
import eu.client.utils.rotations.RotationUtils;
import eu.client.utils.system.Timer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.Items;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RegisterModule(name = "SpeedMine", description = "Automatically mines blocks at a faster speed using packets.", category = Module.Category.PLAYER)
public class SpeedMineModule extends Module {
    public ModeSetting switchMode = new ModeSetting("Switch", "The mode that will be used for automatically switching to the fastest item.", "Silent", InventoryUtils.SWITCH_MODES);
    public NumberSetting range = new NumberSetting("Range", "The maximum distance at which blocks will be mined.", 6.0, 0.0, 8.0);
    // Floor 0.7, NOT 0.6 -- vanilla hard limit, same floor nami uses (DoubleSetting("Speed", 1.0,
    // 0.7, 1.0)). ServerPlayerGameMode:220-234 (.mcref): on STOP_DESTROY_BLOCK the server breaks the
    // block ONLY if its own destroyProgress >= 0.7F; below that it parks ONE delayed destroy
    // (hasDelayedDestroy, a single slot) that finishes at full mining time instead.
    //
    // So Speed < 0.7 was never "faster", it was desync: we STOP early, predict the block air
    // locally (stopDestroyBlock -> removeBlock) and flip instantMine, while the server has not
    // broken anything yet -- the sweep then advances on a block that is still solid server-side.
    // Reported as "0.6 không đợi cả primary lẫn secondary vỡ". The pair asymmetry has the same
    // cause: the single delayed slot is taken by primary's sub-0.7 STOP, so the demoted partner's
    // own STOP (threshold 1.0, nami BlockBreakingTask(pos, facing, 1.0f) parity) does nothing until
    // primary's delayed destroy completes and frees it -- hence "secondary vỡ chậm hơn primary".
    // At >= 0.7 primary destroys instantly via destroyAndAck, never touching that slot, and both
    // land together, exactly as the user observed at 0.7.
    public NumberSetting speed = new NumberSetting("Speed", "The speed at which the module will mine blocks.", 1.0, 0.7, 1.0);
    public BooleanSetting farReach = new BooleanSetting("FarReach", "Bypass for Duration 0.7 on Grim. Try this if normal AutoMine doesn't work.", false);
    public ModeSetting rotate = new ModeSetting("Rotate", "Automatically rotates to the block when mining it.", "Packet", new String[]{"None", "Normal", "Packet", "Silent"});

    public BooleanSetting auto = new BooleanSetting("Auto", "Automatically mines blocks deemed optimal for defeating your opponents.", false);
    public ModeSetting logic = new ModeSetting("Logic", "Auto mining targeting logic.", new BooleanSetting.Visibility(auto, true), "Grim", new String[]{"Grim", "NCP"});
    public BooleanSetting avoidSharing = new BooleanSetting("AvoidSharing", "Avoids mining blocks that are part of your own surround or safety ring.", new BooleanSetting.Visibility(auto, true), true);
    public BooleanSetting switchReset = new BooleanSetting("SwitchReset", "Resets the mining when switching slots.", new ModeSetting.Visibility(switchMode, "None", "AltSwap", "AltPickup"), true);
    public BooleanSetting doubleMine = new BooleanSetting("Double", "Allows the mining of 2 blocks at the same time.", false);
    public BooleanSetting terrain = new BooleanSetting("Terrain", "Automatically mines terrain blocks to place obsidian when target has no crystal base or is fluid-covered.", new BooleanSetting.Visibility(auto, true), false);
    public BooleanSetting terrainPlace = new BooleanSetting("TerrainPlace", "Automatically places obsidian once the terrain block is broken.", new BooleanSetting.Visibility(terrain, true), true);
    public BooleanSetting shift = new BooleanSetting("Shift", "Selects the block behind according to crosshair as secondary when clicking while holding shift.", new BooleanSetting.Visibility(doubleMine, true), true);
    public BooleanSetting antiCrawl = new BooleanSetting("AntiCrawl", "While crawling, mines the block above/below your feet to stand back up instead of staying trapped.", new BooleanSetting.Visibility(doubleMine, true), true);
    public ModeSetting rebreak = new ModeSetting("Rebreak", "Automatically re-mines blocks once they have been replaced.", "None", new String[]{"None", "Fast", "Instant"});
    public NumberSetting instantDelay = new NumberSetting("InstantDelay", "The amount of time that has to pass before instantly mining blocks.", new ModeSetting.Visibility(rebreak, "Fast", "Instant"), 0, 0, 20);
    public NumberSetting instantTimeout = new NumberSetting("InstantTimeout", "The amount of time that cancel instantly mine while no block to mine.", new ModeSetting.Visibility(rebreak, "Fast", "Instant"), 60, 0, 100);
    public BooleanSetting async = new BooleanSetting("Async", "Keeps instantly re-firing even while the target position is currently air, instead of waiting for it to solidify.", new ModeSetting.Visibility(rebreak, "Fast", "Instant"), false);
    public BooleanSetting grim = new BooleanSetting("Grim", "Adds a bypass catered to the Grim anticheat.", false);
    public BooleanSetting strict = new BooleanSetting("Strict", "Waits for the server to tick you before switching back.", false);
    public BooleanSetting whileEating = new BooleanSetting("WhileEating", "Mines blocks while eating.", true);
    public ModeSetting whitelistMode = new ModeSetting("Mode", "All = mine every block. WhiteList = mine only listed blocks. BlackList = mine every block except listed.", "All", new String[]{"All", "WhiteList", "BlackList"});
    public WhitelistSetting whitelist = new WhitelistSetting("List", "Blocks the WhiteList/BlackList mode compares against.", WhitelistSetting.Type.BLOCKS);

    public CategorySetting renderCategory = new CategorySetting("Render", "The category containing all settings related to rendering.");
    public ModeSetting render = new ModeSetting("Render", "Mode", "The rendering that will be applied to the blocks highlighted.", new CategorySetting.Visibility(renderCategory), "Both", new String[]{"None", "Fill", "Outline", "Both"});
    public ModeSetting animation = new ModeSetting("Animation", "The animation that will be used when rendering the block mining progress.", new ModeSetting.Visibility(render, "Fill", "Outline", "Both"), "Expand", new String[]{"None", "Expand", "Rise"});
    public ModeSetting color = new ModeSetting("Color", "The color that will be used when rendering the block mining.", new ModeSetting.Visibility(render, "Fill", "Outline", "Both"), "Smooth", new String[]{"Static", "Smooth", "Custom"});
    public ColorSetting fillColor = new ColorSetting("FillColor", "The color used for the fill rendering.", new ModeSetting.Visibility(render, "Fill", "Both"), ColorUtils.getDefaultFillColor());
    public ColorSetting outlineColor = new ColorSetting("OutlineColor", "The color used for the outline rendering.", new ModeSetting.Visibility(render, "Outline", "Both"), ColorUtils.getDefaultOutlineColor());
    public ModeSetting instantRender = new ModeSetting("InstantRender", "Instant", "The color that will be used for rendering instantly mined blocks.", new CategorySetting.Visibility(renderCategory), "None", new String[]{"None", "Default", "Custom"});
    public ColorSetting instantColor = new ColorSetting("InstantColor", "The custom color used for instantly mined blocks.", new ModeSetting.Visibility(instantRender, "Custom"), new ColorSetting.Color(new Color(148, 0, 211), false, false));

    @Getter private Action primary = null;
    @Getter private Secondary secondary = null;
    @Getter private Action legacySecondary = null;

    public Action getPrimary() { return primary; }
    public Secondary getSecondary() { return secondary; }
    public Action getLegacySecondary() { return legacySecondary; }

    private static final int SECONDARY_TIMEOUT = 10;
    private int secondaryHoldSlot = -1;
    private int secondaryOriginalSlot = -1;
    private static final int SECONDARY_MAX_TICKS = 60;

    private boolean doubleEngaged = false;
    // 2026-08-20 FIX v7 (reported: "chỉ 1 trong 2 block primary/secondary vỡ là chuyển sang
    // isSurround luôn" -- speed<0.7 exposes it, speed>=0.7 just narrows the timing window).
    // Tracks whether the CURRENT primary already has an established partner (a Secondary it was
    // demoted-into-existence alongside). `hasSecondarySlot()` alone can't tell "never paired yet"
    // apart from "was paired, partner already finished" -- both read as false once the partner is
    // gone, so slotsFull()/phaseSlotsFull() wrongly let the ring/phase sweep steal an UNFINISHED
    // primary the instant its partner (not itself) breaks. Set true only at the exact moment a
    // demote-swap creates a partner for the new primary; cleared whenever primary changes identity
    // or goes null. See slotsFull()/phaseSlotsFull() for the actual gate.
    private boolean primaryPaired = false;
    private boolean handlingSwitchReset = false;

    private final Timer instantTimer = new Timer();
    private final Timer mineTimer = new Timer();

    private double delayBalance = 0;
    private long lastStopMs = 0;

    private static final int STOP_COOLDOWN_TICKS = 6;
    private int stopCooldown = 0;

    private final List<BlockPos> pendingTerrainPlacements = new ArrayList<>();
    private TerrainPair activeTerrainPair = null;
    private UUID activeTerrainTarget = null;

    public record TerrainPair(Direction direction, BlockPos surroundPos, BlockPos basePos, double score) {}

    private boolean isRealTerrainBase(BlockPos pos) {
        if (pos == null || mc.level == null) return false;
        net.minecraft.world.level.block.Block block = mc.level.getBlockState(pos).getBlock();
        return block == Blocks.OBSIDIAN || block == Blocks.BEDROCK;
    }

    /**
     * The REAL base (obsidian/bedrock) of the terrain pair we are currently working. Never a legal
     * mining target -- terrain itself only ever picks a base while !hasRealBase, so any slot that
     * ends up here is a leftover Action whose block turned back solid under it.
     */
    private boolean isProtectedTerrainBase(BlockPos pos) {
        return terrain.getValue() && activeTerrainPair != null && pos != null
                && pos.equals(activeTerrainPair.basePos()) && isRealTerrainBase(pos);
    }

    private boolean allSidesTerraformed(Player target) {
        BlockPos feet = target.blockPosition();
        boolean sawCandidate = false;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos surroundPos = feet.relative(dir);
            BlockPos basePos = surroundPos.below();
            if (isOutOfRange(surroundPos) || isOutOfRange(basePos)) continue;

            BlockState surroundState = mc.level.getBlockState(surroundPos);
            if (surroundState.getBlock().defaultDestroyTime() < 0) continue;

            sawCandidate = true;
            BlockState baseState = mc.level.getBlockState(basePos);
            boolean hasRealBase = baseState.getBlock() == Blocks.OBSIDIAN || baseState.getBlock() == Blocks.BEDROCK;
            if (!hasRealBase) return false;
        }
        return sawCandidate;
    }

    public TerrainPair getBestTerrainPair(Player target) {
        if (target == null || mc.level == null || mc.player == null) {
            activeTerrainPair = null;
            activeTerrainTarget = null;
            return null;
        }

        if (allSidesTerraformed(target)) {
            activeTerrainPair = null;
            activeTerrainTarget = null;
            return null;
        }

        UUID targetUUID = target.getUUID();
        BlockPos targetFeet = target.blockPosition();

        if (activeTerrainPair != null && targetUUID.equals(activeTerrainTarget)) {
            BlockPos currentSurround = activeTerrainPair.surroundPos();
            BlockPos currentBase = activeTerrainPair.basePos();

            boolean inRange = !isOutOfRange(currentSurround) && !isOutOfRange(currentBase);
            boolean nearTarget = targetFeet.distManhattan(currentSurround) <= 2;

            BlockState surroundState = mc.level.getBlockState(currentSurround);
            BlockState baseState = mc.level.getBlockState(currentBase);

            boolean surroundBreakable = surroundState.getBlock().defaultDestroyTime() >= 0;
            boolean baseBreakable = baseState.getBlock().defaultDestroyTime() >= 0 || baseState.getBlock() == Blocks.BEDROCK || baseState.getBlock() == Blocks.OBSIDIAN || pendingTerrainPlacements.contains(currentBase);

            boolean surroundDone = surroundState.canBeReplaced();
            boolean baseDone = baseState.getBlock() == Blocks.OBSIDIAN || baseState.getBlock() == Blocks.BEDROCK;

            if (inRange && nearTarget && surroundBreakable && baseBreakable && (!surroundDone || !baseDone)) {
                return activeTerrainPair;
            }
        }

        Set<BlockPos> selfSurround = new HashSet<>();
        if (avoidSharing.getValue()) {
            selfSurround.addAll(HoleUtils.getFeetPositions(mc.player, true, false, true));
            for (BlockPos p : HoleUtils.getInsidePositions(mc.player)) {
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    selfSurround.add(p.relative(dir));
                }
            }
        }

        TerrainPair bestPair = null;
        double bestScore = Double.MAX_VALUE;

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos surroundPos = targetFeet.relative(dir);
            BlockPos basePos = surroundPos.below();

            if (isOutOfRange(surroundPos) || isOutOfRange(basePos)) continue;

            BlockState surroundState = mc.level.getBlockState(surroundPos);
            BlockState baseState = mc.level.getBlockState(basePos);

            if (surroundState.getBlock().defaultDestroyTime() < 0) continue;
            if (baseState.getBlock().defaultDestroyTime() < 0 && baseState.getBlock() != Blocks.BEDROCK && baseState.getBlock() != Blocks.OBSIDIAN && !pendingTerrainPlacements.contains(basePos)) continue;

            double score = 0;

            if (avoidSharing.getValue() && (selfSurround.contains(surroundPos) || selfSurround.contains(basePos))) {
                score += 100000.0;
            }

            boolean hasRealBase = baseState.getBlock() == Blocks.OBSIDIAN || baseState.getBlock() == Blocks.BEDROCK;
            
            if (hasRealBase) {
                score -= 20000.0;
            } else if (!baseState.canBeReplaced()) {
                score += baseState.getBlock().defaultDestroyTime() * 10.0;
            }

            if (!surroundState.canBeReplaced()) {
                score -= 10000.0;
            }

            boolean isMiningPair = isMining(surroundPos) || isMining(basePos);
            if (isMiningPair) {
                score -= 50000.0;
            }

            score += mc.player.distanceToSqr(Vec3.atCenterOf(surroundPos));

            if (score < bestScore) {
                bestScore = score;
                bestPair = new TerrainPair(dir, surroundPos, basePos, score);
            }
        }

        activeTerrainPair = bestPair;
        activeTerrainTarget = bestPair != null ? targetUUID : null;
        return bestPair;
    }

    public boolean hasCrystalBaseForTarget(Player target) {
        if (target == null || mc.level == null) return false;
        BlockPos targetFeet = target.blockPosition();

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos surroundPos = targetFeet.relative(dir);
            BlockPos base = surroundPos.below();
            BlockState state = mc.level.getBlockState(base);
            if (state.getBlock() == Blocks.OBSIDIAN || state.getBlock() == Blocks.BEDROCK) {
                if (!isOutOfRange(surroundPos) && !isOutOfRange(base)) return true;
            }
        }
        return false;
    }

    public List<BlockPos> getTerrainPositions(Player target) {
        TerrainPair pair = getBestTerrainPair(target);
        if (pair == null) return java.util.Collections.emptyList();
        
        BlockState baseState = mc.level.getBlockState(pair.basePos());
        if (baseState.getBlock() == Blocks.OBSIDIAN || baseState.getBlock() == Blocks.BEDROCK) {
            return java.util.Collections.emptyList();
        }
        return List.of(pair.basePos());
    }

    private void placePendingTerrain() {
        if (!terrainPlace.getValue() || pendingTerrainPlacements.isEmpty()) return;
        if (mc.player == null || mc.level == null) return;
        if (!whileEating.getValue() && EntityUtils.isEating()) return;

        int obsidianSlot = switchMode.getValue().equalsIgnoreCase("None") ? -1 : InventoryUtils.find(Items.OBSIDIAN, 0, switchMode.getValue().equalsIgnoreCase("AltSwap") || switchMode.getValue().equalsIgnoreCase("AltPickup") ? InventoryUtils.INVENTORY_END : InventoryUtils.HOTBAR_END);
        if (obsidianSlot == -1) obsidianSlot = InventoryUtils.findHardestBlock(0, 8);
        if (obsidianSlot == -1) return;

        List<BlockPos> toRemove = new ArrayList<>();
        for (BlockPos pos : new ArrayList<>(pendingTerrainPlacements)) {
            if (isOutOfRange(pos)) {
                toRemove.add(pos);
                continue;
            }

            BlockState state = mc.level.getBlockState(pos);
            if (state.getBlock() == Blocks.OBSIDIAN) {
                toRemove.add(pos);
                if (primary != null && primary.getPosition().equals(pos)) {
                    primary.cancel();
                    primary = null;
                }
                if (legacySecondary != null && legacySecondary.getPosition().equals(pos)) {
                    legacySecondary.cancel();
                    legacySecondary = null;
                }
                if (secondary != null && secondary.getPosition().equals(pos)) {
                    secondary.release();
                    secondary = null;
                }
                continue;
            }

            if (state.canBeReplaced() && WorldUtils.isPlaceable(pos)) {
                Direction direction = WorldUtils.getDirection(pos, strict.getValue());
                if (direction == null) direction = WorldUtils.getClosestDirection(pos, true);
                if (direction != null) {
                    int previousSlot = mc.player.getInventory().getSelectedSlot();
                    InventoryUtils.switchSlot(switchMode.getValue(), obsidianSlot, previousSlot);
                    boolean placed = WorldUtils.placeBlock(pos, direction, InteractionHand.MAIN_HAND,
                            rotate.getValue().equalsIgnoreCase("Packet") || rotate.getValue().equalsIgnoreCase("Normal") || rotate.getValue().equalsIgnoreCase("Silent"),
                            true,
                            render.getValue().equalsIgnoreCase("Both") || render.getValue().equalsIgnoreCase("Fill"));
                    InventoryUtils.switchBack(switchMode.getValue(), obsidianSlot, previousSlot);
                    if (placed) {
                        toRemove.add(pos);
                        if (primary != null && primary.getPosition().equals(pos)) {
                            primary.cancel();
                            primary = null;
                        }
                        if (legacySecondary != null && legacySecondary.getPosition().equals(pos)) {
                            legacySecondary.cancel();
                            legacySecondary = null;
                        }
                        if (secondary != null && secondary.getPosition().equals(pos)) {
                            secondary.release();
                            secondary = null;
                        }
                    }
                }
            }
        }
        toRemove.forEach(pendingTerrainPlacements::remove);
    }

    private boolean hasSecondarySlot() {
        return farReach.getValue() ? secondary != null : legacySecondary != null;
    }

    private boolean canStartNow() {
        if (!whileEating.getValue() && (interactPaused || (mc.player != null && (mc.player.isUsingItem() || EntityUtils.isEating())))) return false;
        if (!farReach.getValue()) return true;
        return stopCooldown == 0 && canBegin();
    }

    private boolean canBegin() {
        long delay = System.currentTimeMillis() - lastStopMs;
        if (delay >= 275) return true;
        double cost = (300 - delay) * (farReach.getValue() ? 2 : 1);
        return delayBalance + cost <= 900;
    }

    private void trackStarts(int starts) {
        long delay = System.currentTimeMillis() - lastStopMs;
        for (int i = 0; i < starts; i++) {
            if (delay >= 275) delayBalance *= 0.9;
            else delayBalance += 300 - delay;
        }
        delayBalance = Mth.clamp(delayBalance, -1000, 1000);
    }

    private void markStop() {
        markStop(false);
    }

    private void markStop(boolean cooldown) {
        lastStopMs = System.currentTimeMillis();
        if (cooldown) stopCooldown = STOP_COOLDOWN_TICKS;
    }

    public BlockPos getMiningPosition() {
        return primary != null && primary.isMining() ? primary.getPosition() : null;
    }

    @Getter private volatile boolean interactPaused = false;
    public boolean isInteractPaused() { return interactPaused; }
    private volatile long interactPausedAt = 0;
    private boolean needsRestart = false;

    public final Object interactSyncLock = new Object();
    private static final long INTERACT_PAUSE_TIMEOUT_MS = 750L;

    public void setInteractPaused(boolean paused) {
        this.interactPaused = paused;
        if (paused) {
            this.interactPausedAt = System.currentTimeMillis();
        } else {
            this.needsRestart = true;
        }
    }

    public volatile BlockPos proxyPrimaryPos = null;
    public volatile float proxyPrimaryProgress = 0;
    public volatile BlockPos proxySecondaryPos = null;
    public volatile float proxySecondaryProgress = 0;

    private volatile float prevProxyPrimaryProgress = 0;
    private volatile long proxyPrimaryUpdateTime = 0;
    private volatile long proxyPrimaryUpdateInterval = 50;
    private volatile float prevProxySecondaryProgress = 0;
    private volatile long proxySecondaryUpdateTime = 0;
    private volatile long proxySecondaryUpdateInterval = 50;

    public void updateProxyMiningState(BlockPos primaryPos, float primaryProgress,
                                       BlockPos secondaryPos, float secondaryProgress) {
        long now = System.currentTimeMillis();

        boolean primaryPosChanged = primaryPos == null ? proxyPrimaryPos != null : !primaryPos.equals(proxyPrimaryPos);
        prevProxyPrimaryProgress = primaryPosChanged ? primaryProgress : proxyPrimaryProgress;
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

    private boolean handleSecondary(BlockPos position, int priority) {
        if (!canHandle(position)) return false;
        if ((primary != null && primary.getPosition().equals(position)) || (secondary != null && secondary.getPosition().equals(position)) || (legacySecondary != null && legacySecondary.getPosition().equals(position))) return true;
        if (!doubleMine.getValue()) return false;

        if (farReach.getValue()) {
            if (secondary == null) secondary = new Secondary(position, priority, mc.level.getBlockState(position), 0.0f);
        } else {
            if (legacySecondary == null) legacySecondary = new Action(position, priority);
        }
        return true;
    }

    private boolean handle(BlockPos position, int priority) {
        if (!canHandle(position)) return false;

        if ((primary != null && primary.getPosition().equals(position)) || (secondary != null && secondary.getPosition().equals(position)) || (legacySecondary != null && legacySecondary.getPosition().equals(position))) return true;

        boolean dual = doubleMine.getValue() && (doubleEngaged || priority > 0);

        if (!farReach.getValue()) {
            if (dual) {
                if (legacySecondary != null) {
                    primary = new Action(position, priority);
                } else {
                    if (primary != null) {
                        if (!primary.isInstantMine()) legacySecondary = primary;
                        primary = new Action(position, priority);
                    } else {
                        primary = new Action(position, priority);
                    }
                }
                // v7: snapshot pairing state right as this primary is (re)born. hasSecondarySlot()
                // stays accurate for THIS instant; primaryPaired then holds that truth even after
                // legacySecondary later releases on its own (outside handle()) -- see field comment.
                primaryPaired = hasSecondarySlot();
            } else {
                if (primary != null) primary.cancel();
                primary = new Action(position, priority);
                primaryPaired = false;
            }
            return true;
        }

        if (dual) {
            Secondary demoted = secondary == null && primary != null && !primary.isInstantMine() ? primary.demote() : null;
            if (demoted != null) secondary = demoted;
            else if (primary != null) primary.cancel();
            primary = new Action(position, priority);
            primaryPaired = hasSecondarySlot();
        } else {
            if (primary != null) primary.cancel();
            primary = new Action(position, priority);
            primaryPaired = false;
        }

        return true;
    }

    /** Primary actively digging a real (solid) block. */
    private boolean primaryDigging() {
        return primary != null && !mc.level.getBlockState(primary.getPosition()).canBeReplaced();
    }

    /**
     * Primary parked on an air pos that is STILL a live target slot -- rebreak camp, nami's
     * BlockBreakingTask.instantRemine. Not idle: it is the point of Instant/Fast rebreak.
     */
    private boolean primaryCamping() {
        if (primary == null || !primary.isInstantMine()) return false;
        if (!mc.level.getBlockState(primary.getPosition()).canBeReplaced()) return false;
        Target t = getTarget();
        return t != null && isTargetSurroundPosition(primary.getPosition(), t.player());
    }

    // v7: `hasSecondarySlot()` alone reads false both "never paired" and "was paired, partner
    // already broke" -- OR in primaryPaired so the second case still counts as full/blocked. Only
    // "never paired" (fresh primary, no partner yet) is allowed to let the sweep keep looking.
    private boolean slotsFull() {
        return (primaryDigging() || primaryCamping()) && (!doubleEngaged || hasSecondarySlot() || primaryPaired);
    }

    /**
     * 2026-08-20 FIX v6, root cause of BOTH new reports (ring walks 3->4->5 instead of settling;
     * Speed < 0.7 jumping off the phase pair early).
     *
     * slotsFull() alone can never stop the walk, because its second term frees the sweep whenever
     * the PASSIVE slot is empty while the target is phased -- and every sweep pick goes through
     * handle(), which ALWAYS reassigns primary (nami onBlockStartBreak parity, v4). So each time a
     * Secondary released (3 hold ticks, nami doublemineHoldTicks) the "next" ring block was promoted
     * to PRIMARY and the camp was cancelled: 1 -> 2 -> 3 -> 4 -> 5. Speed < 0.7 only made it start
     * sooner -- the break burst fires at progress >= getSpeed(), so a 0.6 threshold puts the block
     * locally air (and the slot up for grabs) a third of the way early. Same bug, earlier clock.
     *
     * nami does not walk because its list is a stable PRIORITY order re-derived every tick, and
     * phase tasks outrank surround tasks (AutoMineFeature `priority`: ...phase, phase, surroundFeet,
     * surroundFace). Ours is "any candidate not currently mined", which slides down the ring.
     * So: a camping primary may only be preempted by a HIGHER-priority (phase/inside) pick. Ring
     * picks stand down entirely -- that is the "settle and instant-camp on one edge" the user wants,
     * while `inside` still takes over the moment the enemy re-places a block they are phased into.
     */
    private boolean phaseSlotsFull() {
        return primaryDigging() && (!doubleEngaged || hasSecondarySlot() || primaryPaired);
    }

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (isDeferringToProxy()) return;
        if (mc.player == null || mc.level == null) return;

        if (stopCooldown > 0) stopCooldown--;

        if (!doubleMine.getValue() || !auto.getValue()) doubleEngaged = false;

        if (farReach.getValue()) {
            if (secondary != null && !doubleMine.getValue()) {
                secondary.release();
                secondary = null;
            } else if (secondary != null && secondary.process()) {
                secondary = null;
            }
        } else if (legacySecondary != null && !doubleMine.getValue()) {
            legacySecondary.cancel();
            legacySecondary = null;
        } else if (legacySecondary != null && legacySecondary.process()) {
            legacySecondary = null;
        }
        if (primary != null && primary.process()) { primary = null; primaryPaired = false; }

        if (isProxyActive()) {
            syncMiningStateToClient();
        }

        placePendingTerrain();

        // Terrain base self-protect, unconditional and branch-agnostic (the flag-based net further
        // down only runs inside the doubleMine branch, and only catches slots still carrying
        // terrainBase). Runs right after placePendingTerrain() so a slot is dropped the same tick
        // its block turns back into a real base -- before anything can restart on it.
        //
        // Why only !farReach ever showed this: its second slot (legacySecondary) is an ACTIVE
        // Action with its own restart/rebreak state machine, so a leftover task there re-mines the
        // block the moment it reads solid again. farReach's slot is a passive Secondary that cannot
        // mine at all, which is exactly why turning FarReach on "fixed" it.
        if (primary != null && isProtectedTerrainBase(primary.getPosition())) {
            primary.cancel();
            primary = null;
            primaryPaired = false;
        }
        if (legacySecondary != null && isProtectedTerrainBase(legacySecondary.getPosition())) {
            legacySecondary.cancel();
            legacySecondary = null;
        }

        if (!auto.getValue()) return;

        Target target = getTarget();

        BlockPos secondaryPos = farReach.getValue() ? (secondary != null ? secondary.getPosition() : null) : (legacySecondary != null ? legacySecondary.getPosition() : null);
        int secondaryPriority = farReach.getValue() ? (secondary != null ? secondary.getPriority() : 0) : (legacySecondary != null ? legacySecondary.getPriority() : 0);
        if ((primary != null && primary.getPriority() > 0 && !WorldUtils.isReplaceable(primary.getPosition())) || (secondaryPos != null && secondaryPriority > 0 && !WorldUtils.isReplaceable(secondaryPos)))
            return;

        if (doubleMine.getValue()) {
            if (!mineTimer.hasTimeElapsed(50L)) return;

            if (antiCrawl.getValue() && mc.player.isVisuallyCrawling()) {
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

            if (target == null) doubleEngaged = false;

            if (target != null) {
                List<BlockPos> validBlocks = new ArrayList<>();
                List<BlockPos> insidePositions = HoleUtils.getInsidePositions(target.player());
                
                validBlocks.addAll(insidePositions);
                HashSet<BlockPos> feetPositions = HoleUtils.getFeetPositions(target.player(), true, false, true);
                validBlocks.addAll(feetPositions);
                
                for (BlockPos pos : feetPositions) {
                    validBlocks.add(pos.below());
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        validBlocks.add(pos.relative(dir).below());
                    }
                }
                
                for (BlockPos pos : insidePositions) {
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        validBlocks.add(pos.relative(dir));
                        validBlocks.add(pos.relative(dir).below());
                    }
                    validBlocks.add(pos.above());
                    validBlocks.add(pos.below());
                }

                TerrainPair bestPair = terrain.getValue() ? getBestTerrainPair(target.player()) : null;
                if (bestPair != null) {
                    validBlocks.add(bestPair.surroundPos());
                    validBlocks.add(bestPair.basePos());
                }

                // 2026-08-20 FIX (reported: "terrain xây bệ đỡ xong tự phá obsidian luôn" -- matches
                // the race this file's own Action#terrainBase doc already predicted). The ONLY thing
                // that used to stop a terrainBase Action from re-mining its own freshly-placed base
                // was placePendingTerrain()'s position-match cancel -- which only runs while
                // TerrainPlace is on AND the pos is still in pendingTerrainPlacements, and only fires
                // AFTER Action#process() already ran this tick. Any gap (TerrainPlace off, placement
                // landing a tick late, position already removed from pendingTerrainPlacements) left
                // a terrainBase Action free to see the block solid again (now real obsidian/bedrock)
                // and just tryStart() on it like any other target. This is a second, independent
                // safety net: ANY slot flagged terrainBase whose position has become a real base gets
                // cancelled outright, unconditionally -- a base is never a legitimate mining target.
                if (primary != null && primary.isTerrainBase() && isRealTerrainBase(primary.getPosition())) {
                    primary.cancel();
                    primary = null;
                    primaryPaired = false;
                }
                if (legacySecondary != null && legacySecondary.isTerrainBase() && isRealTerrainBase(legacySecondary.getPosition())) {
                    legacySecondary.cancel();
                    legacySecondary = null;
                }

                doubleEngaged = eu.client.utils.minecraft.EntityUtils.isPhased(target.player());
                if (!doubleEngaged) {
                    if (secondary != null) { secondary.release(); secondary = null; }
                    if (legacySecondary != null) { legacySecondary.cancel(); legacySecondary = null; }
                }

                boolean isPrimaryInsideAir = primary != null && HoleUtils.getInsidePositions(target.player()).contains(primary.getPosition()) && mc.level.getBlockState(primary.getPosition()).canBeReplaced();
                if (primary != null && primary.getPriority() == 0) {
                    BlockPos pos = primary.getPosition();
                    if (!validBlocks.contains(pos) || isPrimaryInsideAir) {
                        primary.cancel();
                        primary = null;
                        primaryPaired = false;
                    }
                }

                if (farReach.getValue()) {
                    boolean isSecInsideAir = secondary != null && HoleUtils.getInsidePositions(target.player()).contains(secondary.getPosition()) && mc.level.getBlockState(secondary.getPosition()).canBeReplaced();
                    if (secondary != null && secondary.getPriority() == 0) {
                        BlockPos pos = secondary.getPosition();
                        if (!validBlocks.contains(pos) || isSecInsideAir) {
                            secondary.release();
                            secondary = null;
                        }
                    }
                } else {
                    boolean isLegSecInsideAir = legacySecondary != null && HoleUtils.getInsidePositions(target.player()).contains(legacySecondary.getPosition()) && mc.level.getBlockState(legacySecondary.getPosition()).canBeReplaced();
                    if (legacySecondary != null && legacySecondary.getPriority() == 0) {
                        BlockPos pos = legacySecondary.getPosition();
                        if (!validBlocks.contains(pos) || isLegSecInsideAir) {
                            legacySecondary.cancel();
                            legacySecondary = null;
                        }
                    }
                }

                Runnable terrainTask = () -> {
                    if (!SpeedMineModule.this.terrain.getValue()) return;
                    if (bestPair == null) return;

                    BlockPos basePos = bestPair.basePos();
                    BlockPos surroundPos = bestPair.surroundPos();
                    BlockState baseState = mc.level.getBlockState(basePos);
                    BlockState surroundState = mc.level.getBlockState(surroundPos);
                    boolean hasRealBase = baseState.getBlock() == Blocks.OBSIDIAN || baseState.getBlock() == Blocks.BEDROCK;

                    if (!hasRealBase && !baseState.canBeReplaced()) {
                        if (terrainPlace.getValue() && !pendingTerrainPlacements.contains(basePos)) {
                            pendingTerrainPlacements.add(basePos);
                        }
                        if (!isMining(basePos)) {
                            handle(basePos, 0);
                            if (primary != null && primary.getPosition().equals(basePos)) primary.setTerrainBase(true);
                        }
                    } else if (hasRealBase && !surroundState.canBeReplaced()) {
                        if (!isMining(surroundPos)) {
                            handle(surroundPos, 0);
                        }
                    }
                };

                Runnable outside = () -> {
                    // Camping primary = settled on one edge waiting for the rebreak. Ring picks are
                    // same-priority, so they must NOT steal the slot -- this is the walk stopper.
                    if (primaryCamping()) return;

                    if (terrain.getValue() && bestPair != null) {
                        BlockState pairBaseState = mc.level.getBlockState(bestPair.basePos());
                        boolean pairHasRealBase = pairBaseState.getBlock() == Blocks.OBSIDIAN || pairBaseState.getBlock() == Blocks.BEDROCK;
                        if (!pairHasRealBase) return;
                        if (isMining(bestPair.basePos()) || isMining(bestPair.surroundPos())) {
                            if (slotsFull()) return;
                        }
                    }

                    if (logic.getValue().equals("NCP")) {
                        List<BlockPos> surroundPositions = HoleUtils.getFeetPositions(target.player(), true, false, true).stream().filter(pos -> !mc.level.getBlockState(pos).canBeReplaced()).toList();
                        for (BlockPos position : surroundPositions) {
                            if (slotsFull()) break;
                            if (isMining(position)) continue;
                            if (isInvalid(position) || isOutOfRange(position)) continue;
                            handle(position, 0);
                        }
                        return;
                    }

                    Set<BlockPos> selfSurround = new HashSet<>();
                    if (avoidSharing.getValue() && mc.player != null) {
                        selfSurround.addAll(HoleUtils.getFeetPositions(mc.player, true, false, true));
                        for (BlockPos p : HoleUtils.getInsidePositions(mc.player)) {
                            for (Direction dir : Direction.Plane.HORIZONTAL) {
                                selfSurround.add(p.relative(dir));
                            }
                        }
                    }

                    List<BlockPos> surroundPositions = HoleUtils.getFeetPositions(target.player(), true, false, true).stream()
                            .filter(pos -> !mc.level.getBlockState(pos).canBeReplaced())
                            .sorted(java.util.Comparator.comparingDouble((BlockPos pos) -> {
                                double penalty = (avoidSharing.getValue() && selfSurround.contains(pos)) ? 100000.0 : 0.0;
                                
                                if (SpeedMineModule.this.terrain.getValue()) {
                                    if (bestPair != null && pos.equals(bestPair.surroundPos())) {
                                        penalty -= 100000.0;
                                    } else {
                                        BlockPos basePos = pos.below();
                                        BlockState baseState = mc.level.getBlockState(basePos);
                                        boolean hasBase = baseState.getBlock() == Blocks.OBSIDIAN || baseState.getBlock() == Blocks.BEDROCK;
                                        boolean isMiningBase = isMining(basePos);
                                        
                                        if (isMiningBase) {
                                            penalty -= 50000.0;
                                        } else if (hasBase) {
                                            penalty -= 20000.0;
                                        } else {
                                            penalty += 50000.0;
                                        }
                                    }
                                }

                                return penalty + mc.player.distanceToSqr(Vec3.atCenterOf(pos));
                            }))
                            .toList();

                    for (BlockPos position : surroundPositions) {
                        if (slotsFull()) break;

                        if (!isMining(position)) {
                            if (isInvalid(position) || isOutOfRange(position)) continue;
                            handle(position, 0);
                        }

                        if (slotsFull()) break;
                    }
                };

                Runnable inside = () -> {
                    List<BlockPos> filteredInside = HoleUtils.getInsidePositions(target.player()).stream().filter(insidePosition -> !mc.level.getBlockState(insidePosition).canBeReplaced()).toList();
                    for (BlockPos position : filteredInside) {
                        if (isInvalid(position) || isOutOfRange(position)) continue;
                        if (!isMining(position)) {
                            // phaseSlotsFull, not slotsFull: phase blocks outrank a camping primary
                            // (nami priority order), so a re-placed block the enemy is phased into
                            // still preempts the camp. Only a primary really digging blocks this.
                            if (phaseSlotsFull()) break;
                            handle(position, 0);
                            if (phaseSlotsFull()) break;
                        }
                    }
                };

                if (terrain.getValue()) terrainTask.run();

                inside.run();
                outside.run();
            }
        } else {
            BlockPos position = null;
            boolean terrainBasePick = false;

            if (target == null) {
                return;
            } else {
                boolean isInsideAir = primary != null && HoleUtils.getInsidePositions(target.player()).contains(primary.getPosition()) && mc.level.getBlockState(primary.getPosition()).canBeReplaced();
                if (primary != null && primary.getPriority() == 0 && !primary.isTerrainBase()
                        && (!isTargetSurroundPosition(primary.getPosition(), target.player()) || isInsideAir)) {
                    primary.cancel();
                    primary = null;
                    primaryPaired = false;
                }

                if (terrain.getValue()) {
                    TerrainPair bestPair = getBestTerrainPair(target.player());
                    if (bestPair != null) {
                        BlockState baseState = mc.level.getBlockState(bestPair.basePos());
                        BlockState surroundState = mc.level.getBlockState(bestPair.surroundPos());
                        boolean hasRealBase = baseState.getBlock() == Blocks.OBSIDIAN || baseState.getBlock() == Blocks.BEDROCK;

                        if (!hasRealBase && !baseState.canBeReplaced()) {
                            position = bestPair.basePos();
                            terrainBasePick = true;
                            if (terrainPlace.getValue() && !pendingTerrainPlacements.contains(position)) {
                                pendingTerrainPlacements.add(position);
                            }
                        } else if (!surroundState.canBeReplaced()) {
                            position = bestPair.surroundPos();
                        }
                    }
                }

                if (position == null) {
                    if (!WorldUtils.isReplaceable(target.player.blockPosition()) && !WorldUtils.getBlock(target.player().blockPosition()).equals(Blocks.COBWEB)) {
                        position = target.player().blockPosition();
                    } else {
                        position = target.position();
                    }
                }
            }

            if (position == null) return;
            if (primary != null && position.equals(primary.getPosition()))
                return;

            // v6's settle rule, ported to single-mine (reported: "Double off thì đào lần lượt hết
            // các block dưới cạnh surround thay vì một block"). Same shape as the doubleMine
            // ring-walk: primary breaks its block, the per-tick `position` recompute drops that
            // now-air pos and returns the NEXT one, and handle()'s non-dual branch cancel-replaces
            // the camping primary -- one block per rebreak cycle, right around the ring.
            // A camping primary is settled; nothing retargets it.
            //
            // Terrain sequencing is untouched: a base sits BELOW the ring, so it is never a
            // camping position (isTargetSurroundPosition is false there), so base -> surround
            // inside one pair still runs. Only the march to the NEXT side is stopped.
            if (primaryCamping()) return;

            handle(position, 0);
            if (terrainBasePick && primary != null && primary.getPosition().equals(position)) primary.setTerrainBase(true);
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        if (mc.player == null || mc.level == null) return;

        if (isDeferringToProxy()) {
            renderProxyState(event.getMatrices());
            return;
        }

        if (doubleMine.getValue()) {
            if (farReach.getValue()) {
                if (secondary != null) secondary.render(event.getMatrices());
            } else if (legacySecondary != null) {
                legacySecondary.render(event.getMatrices());
            }
        }
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
                if (primary != null) {
                    primary.cancel();
                    primary.tryStart();
                }
                if (!farReach.getValue() && legacySecondary != null) {
                    legacySecondary.cancel();
                    legacySecondary.tryStart();
                }
            } finally {
                handlingSwitchReset = false;
            }
        }
        
        if (event.getPacket() instanceof ServerboundPlayerActionPacket action) {
            if (action.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
                if (action.getPos().getY() > GRIM_DECOY_Y_OFFSET) return;
            }
        }
    }

    @SubscribeEvent
    public void onAttackBlock(AttackBlockEvent event) {
        if (isDeferringToProxy()) return;
        if (mc.player == null || mc.level == null) return;

        BlockPos position = event.getPosition();

        if (doubleMine.getValue() && shift.getValue() && (mc.options.keyShift.isDown() || mc.player.isShiftKeyDown())) {
            Vec3 look = mc.player.getLookAngle();
            double absX = Math.abs(look.x);
            double absY = Math.abs(look.y);
            double absZ = Math.abs(look.z);

            Direction direction;
            if (absY > absX && absY > absZ) {
                direction = look.y > 0 ? Direction.UP : Direction.DOWN;
            } else if (absX > absZ) {
                direction = look.x > 0 ? Direction.EAST : Direction.WEST;
            } else {
                direction = look.z > 0 ? Direction.SOUTH : Direction.NORTH;
            }

            BlockPos behind = position.relative(direction);

            if (isValid(behind) && !isOutOfRange(behind) && !mc.level.getBlockState(behind).canBeReplaced()) {
                handle(behind, 1);
            }
        }

        if (handle(position, 1)) {
            event.setCancelled(true);
        }
    }

    @Override
    public void onEnable() {
        doubleEngaged = false;
        primaryPaired = false;
        pendingTerrainPlacements.clear();
        activeTerrainPair = null;
        activeTerrainTarget = null;
    }

    @Override
    public void onDisable() {
        doubleEngaged = false;
        primaryPaired = false;
        pendingTerrainPlacements.clear();
        activeTerrainPair = null;
        activeTerrainTarget = null;
        if (isDeferringToProxy()) return;
        if (mc.player == null || mc.level == null) {
            primary = null;
            secondary = null;
            legacySecondary = null;
            return;
        }

        if (secondary != null) secondary.release();
        secondary = null;
        if (legacySecondary != null) legacySecondary.cancel();
        legacySecondary = null;
        if (primary != null) {
            primary.cancel();
            primary = null;
        }

        setInteractPaused(false);
    }

    @Override
    public String getMetaData() {
        String primaryProgress = primary == null ? "0.0" : new DecimalFormat("0.0").format(primary.getProgress() / primary.getSpeed());
        String secondaryProgress;
        if (farReach.getValue()) {
            secondaryProgress = secondary == null || !doubleMine.getValue() ? "" : ", " + new DecimalFormat("0.0").format(secondary.getProgress() / secondary.getSpeed());
        } else {
            secondaryProgress = legacySecondary == null || !doubleMine.getValue() ? "" : ", " + new DecimalFormat("0.0").format(legacySecondary.getProgress() / legacySecondary.getSpeed());
        }
        return primaryProgress + secondaryProgress;
    }

    private boolean canHandle(BlockPos position) {
        if (mc.gameMode.getPlayerMode() == GameType.CREATIVE || mc.gameMode.getPlayerMode() == GameType.SPECTATOR) return false;
        if (mc.level.getBlockState(position).getBlock().defaultDestroyTime() == -1) return false;
        // Terrain's own freshly-built base is off-limits, at the one choke point EVERY selection
        // path goes through (both sweeps, both tick branches, both slots).
        if (isProtectedTerrainBase(position)) return false;
        boolean listed = whitelist.isWhitelistContains(mc.level.getBlockState(position).getBlock());
        boolean allowedByList = switch (whitelistMode.getValue()) {
            case "WhiteList" -> listed;
            case "BlackList" -> !listed;
            default -> true;
        };
        if (!allowedByList) return false;
        return !(mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(position)) > Mth.square(range.getValue().doubleValue()));
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
        if (farReach.getValue()) return secondary != null && secondary.getPosition().equals(position);
        return legacySecondary != null && legacySecondary.getPosition().equals(position);
    }

    private boolean isTargetSurroundPosition(BlockPos position, Player target) {
        if (position == null || target == null || mc.level == null) return false;

        AABB box = target.getBoundingBox();
        int yLegs = Mth.floor(target.getY());

        for (int y = yLegs; y <= yLegs + 2; y++) {
            for (int x = Mth.floor(box.minX); x < Mth.ceil(box.maxX); x++) {
                for (int z = Mth.floor(box.minZ); z < Mth.ceil(box.maxZ); z++) {
                    BlockPos base = new BlockPos(x, y, z);
                    if (position.equals(base)) return true;
                    if (y > yLegs + 1) continue;
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        if (position.equals(base.relative(dir))) return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isOutOfRange(BlockPos position) {
        if (position == null) return true;
        return mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(position)) > Mth.square(range.getValue().doubleValue());
    }

    private Target getTarget() {
        Target optimalTarget = null;
        eu.client.modules.impl.visuals.PopChamsModule popChams = EUClient.MODULE_MANAGER != null ? EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.visuals.PopChamsModule.class) : null;
        eu.client.modules.impl.visuals.LogoutSpotModule logoutSpot = EUClient.MODULE_MANAGER != null ? EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.visuals.LogoutSpotModule.class) : null;

        List<Player> allCandidates = new ArrayList<>(mc.level.players());
        if (logoutSpot != null && logoutSpot.isToggled()) {
            for (Player ghost : logoutSpot.getGhosts()) {
                if (ghost != null && !allCandidates.contains(ghost)) {
                    allCandidates.add(ghost);
                }
            }
        }

        for (Player player : allCandidates) {
            if (player == mc.player) continue;
            if (popChams != null && popChams.isGhost(player)) continue;
            if (logoutSpot == null || !logoutSpot.isGhost(player)) {
                if (!player.isAlive() || player.getHealth() <= 0.0f) continue;
            }
            if (mc.player.distanceToSqr(player) > Mth.square(range.getValue().doubleValue() + 2.0)) continue;
            if (logoutSpot != null && logoutSpot.isGhost(player)) {
                eu.client.modules.impl.visuals.LogoutSpotModule.Spot spot = logoutSpot.getSpot((net.minecraft.client.player.RemotePlayer) player);
                if (spot != null && EUClient.FRIEND_MANAGER.contains(spot.data.name)) continue;
            } else {
                if (EUClient.FRIEND_MANAGER.contains(player.getName().getString())) continue;
            }

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
            if (doubleMine.getValue() && !position.feetPosition()) continue;
            if (!isValidPosition(position.position())) continue;
            if (HoleUtils.isPlayerInHole(mc.player) && HoleUtils.getFeetPositions(mc.player, true, false, true).contains(position.position())) continue;

            double score = 0.0;

            if (position.feetPosition()) {
                score += 5.0;

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

        return positions;
    }

    private boolean isValidPosition(BlockPos position) {
        if (mc.level.getBlockState(position).canBeReplaced()) return false;
        if (mc.level.getBlockState(position).getBlock().defaultDestroyTime() == -1) return false;
        return !isOutOfRange(position);
    }

    private boolean hasCityPosition(BlockPos position) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos offsetPosition = position.relative(dir);
            if (WorldUtils.isPlaceable(offsetPosition)) return true;
        }
        return false;
    }

    private boolean isProxyActive() {
        return eu.client.pingbypass.PingBypassFlags.proxyForwardingActive
                && EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer()
                && EUClient.PROXY_SERVER != null;
    }

    private boolean isDeferringToProxy() {
        return eu.client.pingbypass.PingBypassFlags.isPingBypassActive();
    }

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

    private <T extends net.minecraft.network.protocol.Packet<?>> void serverSendSequenced(java.util.function.IntFunction<T> packetFactory) {
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

    private static final int GRIM_DECOY_Y_OFFSET = 2000;

    private void sendRawPlayerAction(ServerboundPlayerActionPacket.Action action, BlockPos target, Direction face) {
        sendRawPlayerAction(action, target, face, false);
    }

    private void sendRawPlayerAction(ServerboundPlayerActionPacket.Action action, BlockPos target, Direction face, boolean cooldown) {
        if (action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) markStop(cooldown);
        serverSend(new ServerboundPlayerActionPacket(action, target, face));
    }

    @Getter
    public class Secondary {
        private final BlockPos position;
        private final int priority;
        private BlockState state;
        private float progress;
        private float prevProgress;
        private int ticks;
        private boolean holding;
        private int holdTicks;

        private Secondary(BlockPos position, int priority, BlockState state, float progress) {
            this.position = position;
            this.priority = priority;
            this.state = state;
            this.progress = progress;
            this.prevProgress = progress;
        }

        public BlockPos getPosition() { return position; }
        public int getPriority() { return priority; }
        public boolean isMining() { return true; }
        public float getSpeed() { return 1.0f; }
        public float getProgress() { return Mth.clamp(progress, 0.0f, 1.0f); }

        public boolean process() {
            if (isOutOfRange(position)) {
                release();
                return true;
            }

            boolean clientEating = mc.player != null && (mc.player.isUsingItem() || EntityUtils.isEating());
            if (interactPaused && !clientEating && System.currentTimeMillis() - interactPausedAt >= INTERACT_PAUSE_TIMEOUT_MS) {
                setInteractPaused(false);
            }

            BlockState current = mc.level.getBlockState(position);
            if (current.canBeReplaced()) {
                EUClient.EVENT_HANDLER.post(new DestroyBlockEvent(position));
                mineTimer.reset();
                release();
                return true;
            }
            this.state = current;

            int bestSlot = InventoryUtils.findFastestItem(this.state, InventoryUtils.HOTBAR_START, InventoryUtils.HOTBAR_END);
            if (bestSlot == -1) bestSlot = mc.player.getInventory().getSelectedSlot();

            float delta = WorldUtils.getMineSpeed(this.state, bestSlot) / EUClient.WORLD_MANAGER.getTimerMultiplier();
            if (delta <= 0.0f) {
                release();
                return true;
            }

            ticks++;
            prevProgress = progress;
            progress += delta;

            boolean canHold = !clientEating || switchMode.getValue().equalsIgnoreCase("None");
            if (!holding && progress + delta >= 1.0f && canHold) hold(bestSlot);

            if (holding && !farReach.getValue() && ++holdTicks >= 3) {
                release();
                return true;
            }

            if (progress >= 1.0f + delta * SECONDARY_TIMEOUT) {
                release();
                return true;
            }
            if (ticks > SECONDARY_MAX_TICKS) {
                release();
                return true;
            }
            return false;
        }

        private void hold(int slot) {
            if (switchMode.getValue().equalsIgnoreCase("None")) return;
            int selected = mc.player.getInventory().getSelectedSlot();
            holding = true;
            if (selected == slot) return;
            secondaryOriginalSlot = selected;
            secondaryHoldSlot = slot;
            if (isProxyActive()) serverSend(new ServerboundSetCarriedItemPacket(slot));
            else mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
            if (switchMode.getValue().equalsIgnoreCase("Normal")) mc.player.getInventory().setSelectedSlot(slot);
        }

        private void release() {
            if (!holding) return;
            holding = false;
            if (secondaryHoldSlot == -1) return;
            int restore = secondaryOriginalSlot;
            secondaryHoldSlot = -1;
            secondaryOriginalSlot = -1;
            if (mc.player == null) return;
            if (switchMode.getValue().equalsIgnoreCase("Normal") && mc.player.getInventory().getSelectedSlot() == restore) return;
            if (isProxyActive()) serverSend(new ServerboundSetCarriedItemPacket(restore));
            else mc.getConnection().send(new ServerboundSetCarriedItemPacket(restore));
            if (switchMode.getValue().equalsIgnoreCase("Normal")) mc.player.getInventory().setSelectedSlot(restore);
        }

        public void render(PoseStack matrices) {
            if (mc.level.getBlockState(position).canBeReplaced()) return;

            double p = Mth.clamp(Mth.lerp(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false), prevProgress, progress), 0.0f, 1.0f);

            AABB box = new AABB(position);
            if (animation.getValue().equalsIgnoreCase("Expand")) box = new AABB(position).deflate(0.5).inflate(Mth.clamp(p / 2.0, 0.0, 0.5));
            if (animation.getValue().equalsIgnoreCase("Rise")) box = new AABB(position.getX(), position.getY(), position.getZ(), position.getX() + 1.0, position.getY() + p, position.getZ() + 1.0);

            Color fill = fillColor.getColor();
            Color outline = outlineColor.getColor();

            if (color.getValue().equalsIgnoreCase("Static")) {
                fill = p >= 0.9 ? new Color(0, 255, 0, fillColor.getAlpha()) : new Color(255, 0, 0, fillColor.getAlpha());
                outline = p >= 0.9 ? new Color(0, 255, 0, outlineColor.getAlpha()) : new Color(255, 0, 0, outlineColor.getAlpha());
            } else if (color.getValue().equalsIgnoreCase("Smooth")) {
                fill = new Color(255 - (int) (p * 255), (int) (p * 255), 0, fillColor.getAlpha());
                outline = new Color(255 - (int) (p * 255), (int) (p * 255), 0, outlineColor.getAlpha());
            }

            if (render.getValue().equalsIgnoreCase("Fill") || render.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBox(matrices, box, fill);
            if (render.getValue().equalsIgnoreCase("Outline") || render.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBoxOutline(matrices, box, outline);
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
        private int startSlot = -1;
        private boolean started;

        @Setter private boolean terrainBase;

        public Action(BlockPos position, int priority) {
            this.position = position;
            this.state = mc.level.getBlockState(position);
            this.priority = priority;
            tryStart();
        }

        public BlockPos getPosition() { return position; }
        public float getProgress() { return progress; }
        public boolean isMining() { return mining; }
        public boolean isInstantMine() { return instantMine; }
        public int getPriority() { return priority; }
        public boolean isTerrainBase() { return terrainBase; }
        public void setTerrainBase(boolean terrainBase) { this.terrainBase = terrainBase; }

        private Secondary demote() {
            if (!started) return null;

            BlockState current = mc.level.getBlockState(position);
            if (current.canBeReplaced()) return null;

            Direction direction = WorldUtils.getClosestDirection(position, true);
            int slot = switchMode.getValue().equalsIgnoreCase("None") ? -1 : InventoryUtils.findFastestItem(current, InventoryUtils.HOTBAR_START, switchMode.getValue().equalsIgnoreCase("AltSwap") || switchMode.getValue().equalsIgnoreCase("AltPickup") ? InventoryUtils.INVENTORY_END : InventoryUtils.HOTBAR_END);
            if (slot == -1) slot = mc.player.getInventory().getSelectedSlot();

            fireBreakBurst(direction, slot, true);
            return new Secondary(position, priority, current, progress);
        }

        private void tryStart() {
            synchronized (interactSyncLock) {
                if (!canStartNow()) return;
                start();
            }
        }

        public boolean process() {
            if (isOutOfRange(position)) {
                cancel();
                return true;
            }

            boolean clientEating = mc.player != null && (mc.player.isUsingItem() || EntityUtils.isEating());
            if (interactPaused && !clientEating && System.currentTimeMillis() - interactPausedAt >= INTERACT_PAUSE_TIMEOUT_MS) {
                setInteractPaused(false);
            }

            if (needsRestart && isProxyActive()) {
                needsRestart = false;
                started = false;
            }

            if (!started) {
                if (mc.level.getBlockState(position).canBeReplaced()) return true;
                tryStart();
                return false;
            }

            if (!farReach.getValue()) return legacyProcess();

            if (mc.level.getBlockState(position).canBeReplaced()) {
                if (rebreak.getValue().equalsIgnoreCase("Fast")) {
                    this.progress = 0.0f;
                    this.prevProgress = 0.0f;
                }
                if (instantMine) {
                    if (async.getValue()) {
                        if ((whileEating.getValue() || !EntityUtils.isEating()) && instantTimer.hasTimeElapsed(instantDelay.getValue().longValue() * 50L)) {
                            Direction asyncDirection = WorldUtils.getClosestDirection(position, true);
                            int asyncSlot = switchMode.getValue().equalsIgnoreCase("None") ? -1 : InventoryUtils.findFastestItem(this.state, InventoryUtils.HOTBAR_START, switchMode.getValue().equalsIgnoreCase("AltSwap") || switchMode.getValue().equalsIgnoreCase("AltPickup") ? InventoryUtils.INVENTORY_END : InventoryUtils.HOTBAR_END);
                            if (asyncSlot == -1) asyncSlot = mc.player.getInventory().getSelectedSlot();
                            fireBreakBurst(asyncDirection, asyncSlot, false, false);
                            instantTimer.reset();
                            attempts++;
                        }
                        return false;
                    }

                    long timeoutTicks = instantTimeout.getValue().longValue();
                    boolean timedOut = timeoutTicks > 0 && instantTimer.hasTimeElapsed(timeoutTicks * 50L);
                    if (!timedOut) return false;
                }

                if (isProxyActive()) {
                    serverSend(new ServerboundSetCarriedItemPacket(mc.player.getInventory().getSelectedSlot()));
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
                    float[] rots = RotationUtils.getRotations(WorldUtils.getHitVector(position, direction));
                    if (isProxyActive()) {
                        serverSend(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot(
                                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                                rots[0], rots[1], mc.player.onGround(), mc.player.horizontalCollision));
                    } else {
                        EUClient.ROTATION_MANAGER.legacyRotate(rots, EUClient.ROTATION_MANAGER.getLegacyModulePriority(SpeedMineModule.this));
                    }
                }

                boolean switchTouchesInventory = !switchMode.getValue().equalsIgnoreCase("None");
                boolean isEatingNow = mc.player != null && (mc.player.isUsingItem() || EntityUtils.isEating());
                boolean pauseBreak = interactPaused || (!whileEating.getValue() && isEatingNow) || (switchTouchesInventory && isEatingNow);
                if (progress >= getSpeed() && !state.canBeReplaced() && !pauseBreak) {
                    if (!instantMine || instantTimer.hasTimeElapsed(instantDelay.getValue().longValue() * 50L)) {
                        fireBreakBurst(direction, slot, false);
                        if (!instantMine) mineTimer.reset();
                    }

                    attempts++;
                    if (rebreak.getValue().equalsIgnoreCase("None") || terrainBase) {
                        this.mining = false;
                        this.stallTime = System.currentTimeMillis();
                    } else {
                        this.instantMine = true;
                        if (rebreak.getValue().equalsIgnoreCase("Fast")) {
                            this.progress = 0.0f;
                            this.prevProgress = 0.0f;
                        }
                        instantTimer.reset();
                    }

                    return false;
                }
            } else {
                if (!mc.level.getBlockState(position).canBeReplaced() && (attempts == 0 || System.currentTimeMillis() - stallTime >= 150L)) {
                    tryStart();
                }
            }

            return false;
        }

        private boolean legacyProcess() {
            if (mc.level.getBlockState(position).canBeReplaced()) {
                if (instantMine) {
                    if (async.getValue()) {
                        if ((whileEating.getValue() || !EntityUtils.isEating()) && instantTimer.hasTimeElapsed(instantDelay.getValue().longValue() * 50L)) {
                            Direction asyncDirection = WorldUtils.getClosestDirection(position, true);
                            int asyncSlot = switchMode.getValue().equalsIgnoreCase("None") ? -1 : InventoryUtils.findFastestItem(this.state, InventoryUtils.HOTBAR_START, switchMode.getValue().equalsIgnoreCase("AltSwap") || switchMode.getValue().equalsIgnoreCase("AltPickup") ? InventoryUtils.INVENTORY_END : InventoryUtils.HOTBAR_END);
                            if (asyncSlot == -1) asyncSlot = mc.player.getInventory().getSelectedSlot();
                            legacyFireBreakBurst(asyncDirection, asyncSlot);
                            instantTimer.reset();
                            attempts++;
                        }
                        return false;
                    }

                    long timeoutTicks = instantTimeout.getValue().longValue();
                    boolean timedOut = timeoutTicks > 0 && instantTimer.hasTimeElapsed(timeoutTicks * 50L);
                    if (!timedOut) return false;
                }

                if (isProxyActive()) {
                    serverSend(new ServerboundSetCarriedItemPacket(mc.player.getInventory().getSelectedSlot()));
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
                    float[] rots = RotationUtils.getRotations(WorldUtils.getHitVector(position, direction));
                    if (isProxyActive()) {
                        serverSend(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot(
                                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                                rots[0], rots[1], mc.player.onGround(), mc.player.horizontalCollision));
                    } else {
                        EUClient.ROTATION_MANAGER.legacyRotate(rots, EUClient.ROTATION_MANAGER.getLegacyModulePriority(SpeedMineModule.this));
                    }
                }

                boolean switchTouchesInventory = !switchMode.getValue().equalsIgnoreCase("None");
                if (progress >= getSpeed() && !state.canBeReplaced() && (whileEating.getValue() || !EntityUtils.isEating())
                        && !(switchTouchesInventory && EntityUtils.isEating())) {
                    legacyFireBreakBurst(direction, slot);

                    attempts++;
                    if (rebreak.getValue().equalsIgnoreCase("None") || terrainBase) {
                        this.mining = false;
                        this.stallTime = System.currentTimeMillis();
                    } else {
                        this.instantMine = true;
                        if (rebreak.getValue().equalsIgnoreCase("Fast")) {
                            this.progress = 0.0f;
                            this.prevProgress = 0.0f;
                        }
                        instantTimer.reset();
                    }

                    return false;
                }
            } else {
                if (!mc.level.getBlockState(position).canBeReplaced() && (attempts == 0 || (rebreak.getValue().equalsIgnoreCase("None") && !mining && System.currentTimeMillis() - stallTime >= 150L))) {
                    start();
                }
            }

            return false;
        }

        private void legacyFireBreakBurst(Direction direction, int slot) {
            EUClient.EVENT_HANDLER.post(new DestroyBlockEvent(position));

            if (rotate.getValue().equalsIgnoreCase("Packet") || rotate.getValue().equalsIgnoreCase("Silent")) {
                float[] rots = RotationUtils.getRotations(WorldUtils.getHitVector(position, direction));
                if (isProxyActive()) {
                    if (rotate.getValue().equalsIgnoreCase("Silent")) {
                        serverSend(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                                rots[0], rots[1], mc.player.onGround(), mc.player.horizontalCollision));
                    } else {
                        serverSend(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot(
                                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                                rots[0], rots[1], mc.player.onGround(), mc.player.horizontalCollision));
                    }
                } else {
                    EUClient.ROTATION_MANAGER.wireRotate(rotate.getValue(), rots);
                }
            }

            int previousSlot = mc.player.getInventory().getSelectedSlot();

            if (isProxyActive()) {
                int mineSlot = switchMode.getValue().equalsIgnoreCase("None") ? -1 : slot;
                boolean needSwitch = mineSlot != -1 && mineSlot != previousSlot;

                if (needSwitch) serverSend(new ServerboundSetCarriedItemPacket(mineSlot));
                stopDestroyBlock(position, direction, true);
                if (grim.getValue()) serverSend(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, position.above(500), direction));
                serverSend(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                if (needSwitch) serverSend(new ServerboundSetCarriedItemPacket(previousSlot));
            } else {
                InventoryUtils.switchSlot(switchMode.getValue(), slot, previousSlot);

                stopDestroyBlock(position, direction, true);
                if (grim.getValue()) mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, position.above(500), direction));
                mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));

                InventoryUtils.switchBack(switchMode.getValue(), slot, previousSlot);
            }
        }

        private void fireBreakBurst(Direction direction, int slot, boolean demote) {
            fireBreakBurst(direction, slot, demote, !demote);
        }

        private void fireBreakBurst(Direction direction, int slot, boolean demote, boolean armCooldown) {
            if (!demote) EUClient.EVENT_HANDLER.post(new DestroyBlockEvent(position));

            if (rotate.getValue().equalsIgnoreCase("Packet") || rotate.getValue().equalsIgnoreCase("Silent")) {
                float[] rots = RotationUtils.getRotations(WorldUtils.getHitVector(position, direction));
                if (isProxyActive()) {
                    if (rotate.getValue().equalsIgnoreCase("Silent")) {
                        serverSend(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                                rots[0], rots[1], mc.player.onGround(), mc.player.horizontalCollision));
                    } else {
                        serverSend(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot(
                                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                                rots[0], rots[1], mc.player.onGround(), mc.player.horizontalCollision));
                    }
                } else {
                    EUClient.ROTATION_MANAGER.wireRotate(rotate.getValue(), rots);
                }
            }

            int previousSlot = mc.player.getInventory().getSelectedSlot();

            if (isProxyActive()) {
                int realPreviousSlot = startSlot != -1 ? startSlot : previousSlot;
                int mineSlot = switchMode.getValue().equalsIgnoreCase("None") ? -1 : slot;

                if (mineSlot != -1) {
                    serverSend(new ServerboundSetCarriedItemPacket(mineSlot));
                    if (switchMode.getValue().equalsIgnoreCase("Normal")) mc.player.getInventory().setSelectedSlot(mineSlot);
                }

                stopDestroyBlock(position, direction, !demote, armCooldown);
                if (grim.getValue()) serverSend(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, position.above(500), direction));
                serverSend(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));

                if (mineSlot != -1) {
                    serverSend(new ServerboundSetCarriedItemPacket(realPreviousSlot));
                    if (switchMode.getValue().equalsIgnoreCase("Normal")) mc.player.getInventory().setSelectedSlot(realPreviousSlot);
                }
            } else {
                InventoryUtils.switchSlot(switchMode.getValue(), slot, previousSlot);

                stopDestroyBlock(position, direction, !demote, armCooldown);
                if (grim.getValue()) mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, position.above(500), direction));
                mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));

                InventoryUtils.switchBack(switchMode.getValue(), slot, previousSlot);
            }
        }

        private void stopDestroyBlock(BlockPos position, Direction direction, boolean remove) {
            stopDestroyBlock(position, direction, remove, false);
        }

        private void stopDestroyBlock(BlockPos position, Direction direction, boolean remove, boolean cooldown) {
            try (net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler prediction =
                         ((eu.client.mixins.accessors.ClientWorldAccessor) mc.level).invokeGetPendingUpdateManager().startPredicting()) {
                net.minecraft.network.protocol.Packet<?> packet = new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, position, direction, prediction.currentSequence());

                markStop(cooldown);
                if (isProxyActive()) serverSend(packet);
                else mc.getConnection().send(packet);

                if (remove) mc.level.removeBlock(position, false);
            }
        }

        public void render(PoseStack matrices) {
            if (mc.level.getBlockState(position).canBeReplaced() && !instantMine)
                return;

            AABB box = new AABB(position);
            boolean airWaiting = mc.level.getBlockState(position).canBeReplaced() && instantMine;
            double progress = airWaiting ? this.progress / getSpeed()
                    : Mth.clamp(Mth.lerp(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false), prevProgress / getSpeed(), this.progress / getSpeed()), 0.0f, 1.0f);

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
            if (!farReach.getValue()) {
                legacyStart();
                return;
            }

            trackStarts(farReach.getValue() ? 2 : 1);
            Direction direction = WorldUtils.getClosestDirection(position, true);

            if (isProxyActive()) {
                startSlot = mc.player.getInventory().getSelectedSlot();

                if (farReach.getValue()) {
                    sendRawPlayerAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, position, direction);
                    BlockPos decoyPos = grimDecoyPos();
                    sendRawPlayerAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, decoyPos, WorldUtils.getClosestDirection(decoyPos, true));
                } else {
                    serverSendSequenced(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, position, direction, seq));
                }

                serverSend(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
            } else {
                if (farReach.getValue()) {
                    sendRawPlayerAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, position, direction);
                    BlockPos decoyPos = grimDecoyPos();
                    sendRawPlayerAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, decoyPos, WorldUtils.getClosestDirection(decoyPos, true));
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
            this.started = true;
        }

        private void legacyStart() {
            Direction direction = WorldUtils.getClosestDirection(position, true);

            if (isProxyActive()) {
                int slot = switchMode.getValue().equalsIgnoreCase("None") ? -1 : InventoryUtils.findFastestItem(state, InventoryUtils.HOTBAR_START, switchMode.getValue().equalsIgnoreCase("AltSwap") || switchMode.getValue().equalsIgnoreCase("AltPickup") ? InventoryUtils.INVENTORY_END : InventoryUtils.HOTBAR_END);
                boolean needSwitch = slot != -1 && slot != mc.player.getInventory().getSelectedSlot();

                if (needSwitch) serverSend(new ServerboundSetCarriedItemPacket(slot));

                if (doubleMine.getValue()) {
                    serverSendSequenced(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, position, direction, seq));
                    serverSendSequenced(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, position, direction, seq));
                    serverSendSequenced(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, position, direction, seq));
                } else {
                    serverSendSequenced(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, position, direction, seq));
                }

                serverSend(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
            } else {
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
            this.started = true;
        }

        public void cancel() {
            if (!farReach.getValue()) {
                legacyCancel();
                return;
            }

            if (!doubleMine.getValue() && started) {
                if (farReach.getValue()) {
                    sendRawPlayerAction(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, position, WorldUtils.getClosestDirection(position, true));
                    serverSend(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                } else if (isProxyActive()) {
                    serverSendSequenced(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, position, WorldUtils.getClosestDirection(position, true), seq));
                    serverSend(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                } else {
                    NetworkUtils.sendSequencedPacket(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, position, WorldUtils.getClosestDirection(position, true), seq));
                    mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                }
            }

            if (isProxyActive() && startSlot != -1) {
                serverSend(new ServerboundSetCarriedItemPacket(startSlot));
                mc.player.getInventory().setSelectedSlot(startSlot);
                startSlot = -1;
            }

            this.progress = 0.0f;
            this.prevProgress = 0.0f;
            this.attempts = 0;
            this.mining = false;
            this.started = false;
            this.instantMine = false;
        }

        private void legacyCancel() {
            if (!doubleMine.getValue()) {
                Direction direction = WorldUtils.getClosestDirection(position, true);
                if (isProxyActive()) {
                    serverSendSequenced(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, position, direction, seq));
                    serverSend(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                } else {
                    NetworkUtils.sendSequencedPacket(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, position, direction, seq));
                    mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                }
            }

            if (isProxyActive()) serverSend(new ServerboundSetCarriedItemPacket(mc.player.getInventory().getSelectedSlot()));

            this.progress = 0.0f;
            this.prevProgress = 0.0f;
            this.attempts = 0;
            this.mining = false;
            this.started = false;
            this.instantMine = false;
        }

        private float getSpeed() {
            if (farReach.getValue()) return 0.7f;
            return legacySecondary != null && position.equals(legacySecondary.getPosition()) ? 1.0f : speed.getValue().floatValue();
        }

        private BlockPos grimDecoyPos() {
            return position.below(GRIM_DECOY_Y_OFFSET);
        }

        public int getTicksRemaining() {
            if (!mining) return Integer.MAX_VALUE;

            float delta = WorldUtils.getMineSpeed(state, mc.player.getInventory().getSelectedSlot()) / EUClient.WORLD_MANAGER.getTimerMultiplier();
            if (delta <= 0.0f) return Integer.MAX_VALUE;

            return Math.max(0, Math.round((getSpeed() - progress) / delta));
        }
    }

    private record Target(Player player, java.util.List<Position> feetPositions, BlockPos position) { }
    private record Position(BlockPos position, boolean feetPosition) { }
}