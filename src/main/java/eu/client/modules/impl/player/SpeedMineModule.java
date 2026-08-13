package eu.client.modules.impl.player;

import lombok.Getter;
import lombok.Setter;
import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.*;
import eu.client.managers.RotationPriorities;
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
    // boze's own description (Discord screenshot, 2026-08-12): "Bypass for Duration 0.7 on Grim" /
    // "Try this if normal AutoMine doesn't work". Two DIFFERENT techniques bundled under one toggle,
    // confirmed (2026-08-12) against homovore-public's actual SpeedMineModule.java (github.com/
    // leonetics/homovore-public) rather than guessed from GrimAC's formulas alone:
    //  1. "Duration 0.7" = homovore's own `Threshold` setting, default 0.7 -- getSpeed() below
    //     overrides Speed to 0.7 while this is on, completing at 70% of the real per-tick progress
    //     a legit break needs instead of 100%.
    //  2. "Bypass" = homovore's `GrimDecoy` setting (also default true there) -- see
    //     Action.start()'s grimDecoyPos() send. GrimAC's FastBreak check (checks/impl/breaking/
    //     FastBreak.java) tracks targetBlockPosition/maximumBlockDamage/startBreak as ONE set of
    //     fields, not per-position -- sending a SECOND START_DESTROY_BLOCK for a block 2000Y below
    //     the real target (out of the world, the real server silently no-ops it) right after the
    //     real one overwrites FastBreak's own tracking with the decoy's (bogus) block-damage value,
    //     poisoning its predictedTime calc for whatever FINISHED_DIGGING follows -- not a speed
    //     trick, a state-tracking exploit. Threshold alone (no decoy) still legitimately completes
    //     faster than 1.0, i.e. still has SOME predictedTime-vs-realTime gap; the decoy is what
    //     actually makes that gap harmless to FastBreak's math.
    // Overrides the Speed setting outright while on (doesn't blend with it) and does NOT touch
    // Range/doubleMine/instant -- confirmed against boze's actual in-game behavior, not assumed.
    public BooleanSetting farReach = new BooleanSetting("FarReach", "Bypass for Duration 0.7 on Grim. Try this if normal AutoMine doesn't work.", false);
    // MovementSync is this project's rewrite of the port's old "Normal" (rotation-only proxy
    // packet + ClientRotationEvent arbitration, see RotationManager's class doc) -- kept under a
    // new name. Normal/Packet below are bản gốc 1.21.4's original implementations, restored
    // verbatim (self-contained here, not routed through RotationManager's shared queue -- that
    // queue API was removed entirely in the ClientRotationEvent rewrite). Both reintroduce a
    // known trade-off MovementSync/the current Packet fix exist specifically to avoid: on the
    // real server, GrimAC runs a full movement-prediction cycle for ANY packet carrying a
    // position, so proxy-side Normal/Packet here can rubberband on Grim servers (see
    // RotationManager.packetRotate's 2026-08-13 comment for the original diagnosis).
    public ModeSetting rotate = new ModeSetting("Rotate", "Automatically rotates to the block when mining it.", "Packet", new String[]{"None", "MovementSync", "Normal", "Packet"});

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
    // Instant's own air-check normally idles (waits, doesn't resend) while the position reads
    // as air, only firing again once the block solidifies -- InstantTimeout is just the ceiling
    // on how long that idle wait lasts before giving up. Async instead keeps firing the rebreak
    // burst continuously through the air tick itself, ignoring InstantTimeout entirely (the
    // whole point is to not wait). Target acquisition (Auto/getTarget/handle) is untouched --
    // this only changes what an already-tracked primary/secondary/legacySecondary does while
    // ITS OWN position is air, never which positions get selected.
    public BooleanSetting async = new BooleanSetting("Async", "Keeps instantly re-firing even while the target position is currently air, instead of waiting for it to solidify.", new BooleanSetting.Visibility(instant, true), false);
    public BooleanSetting grim = new BooleanSetting("Grim", "Adds a bypass catered to the Grim anticheat.", false);
    // Was dropped entirely during the 1.21.4 -> 26.1.2 port -- Instant relies on this to make the
    // block go locally-air the INSTANT we fire the break packet, not whenever the server's own
    // block-update packet round-trips back. Without it, mining==true (Instant deliberately never
    // clears it) but the position's BlockState never locally changes, so process() just re-fires
    // the same STOP_DESTROY_BLOCK/swing burst on the SAME stale block every tick instead of ever
    // seeing canBeReplaced()==true and cancelling out to let the caller re-target -- i.e. Instant
    // never actually reacts to a target re-placing the block, it just spams the old one.
    public BooleanSetting strict = new BooleanSetting("Strict", "Waits for the server to tick you before switching back.", false);
    public BooleanSetting whileEating = new BooleanSetting("WhileEating", "Mines blocks while eating.", true);
    public ModeSetting whitelistMode = new ModeSetting("ListMode", "All = mine every block. WhiteList = mine only listed blocks. BlackList = mine every block except listed.", "All", new String[]{"All", "WhiteList", "BlackList"});
    public WhitelistSetting whitelist = new WhitelistSetting("Whitelist", "Blocks the WhiteList/BlackList mode compares against.", WhitelistSetting.Type.BLOCKS);

    public CategorySetting renderCategory = new CategorySetting("Render", "The category containing all settings related to rendering.");
    public ModeSetting render = new ModeSetting("Render", "Mode", "The rendering that will be applied to the blocks highlighted.", new CategorySetting.Visibility(renderCategory), "Both", new String[]{"None", "Fill", "Outline", "Both"});
    public ModeSetting animation = new ModeSetting("Animation", "The animation that will be used when rendering the block mining progress.", new ModeSetting.Visibility(render, "Fill", "Outline", "Both"), "Expand", new String[]{"None", "Expand", "Rise"});
    public ModeSetting color = new ModeSetting("Color", "The color that will be used when rendering the block mining.", new ModeSetting.Visibility(render, "Fill", "Outline", "Both"), "Smooth", new String[]{"Static", "Smooth", "Custom"});
    public ColorSetting fillColor = new ColorSetting("FillColor", "The color used for the fill rendering.", new ModeSetting.Visibility(render, "Fill", "Both"), ColorUtils.getDefaultFillColor());
    public ColorSetting outlineColor = new ColorSetting("OutlineColor", "The color used for the outline rendering.", new ModeSetting.Visibility(render, "Outline", "Both"), ColorUtils.getDefaultOutlineColor());
    public ModeSetting instantRender = new ModeSetting("InstantRender", "Instant", "The color that will be used for rendering instantly mined blocks.", new CategorySetting.Visibility(renderCategory), "None", new String[]{"None", "Default", "Custom"});
    public ColorSetting instantColor = new ColorSetting("InstantColor", "The custom color used for instantly mined blocks.", new ModeSetting.Visibility(instantRender, "Custom"), new ColorSetting.Color(new Color(148, 0, 211), false, false));

    // ═══════════════════════════════════════════════════════════════════
    // State ownership -- three DISTINCT machines, deliberately not one:
    //
    //  1. primary  (Action)     ACTIVE. Owns the progress loop, the START, the completing
    //                           STOP burst, rotations, slot switching, Instant/rebreak.
    //                           Exactly one at a time. Unchanged in spirit by this rewrite.
    //
    //  2. secondary (Secondary) PASSIVE. Never sends a digging packet, ever. Created only by
    //                           Action.demote(), which fires ONE real STOP_DESTROY_BLOCK for
    //                           the position being handed over. Below the server's 0.7
    //                           threshold that STOP does not break the block -- it parks it in
    //                           ServerPlayerGameMode's delayed-destroy slot (hasDelayedDestroy
    //                           / delayedDestroyPos / delayedTickStart, verified in .mcref
    //                           mojmap), which the SERVER then finishes on its own over the
    //                           following ticks with zero further packets from us. So all the
    //                           secondary does is watch canBeReplaced() and fire this project's
    //                           completion side-effects when the break actually lands, with a
    //                           timeout to write it off. This is homovore-public's
    //                           tickSecondary() model (github.com/leonetics/homovore-public).
    //
    //                           The old design made `secondary` just another Action, i.e. a
    //                           second copy of the ACTIVE machine, which re-drove packets for a
    //                           target the server was already finishing -- and when its
    //                           completing STOP got rejected it re-fired fireBreakBurst() every
    //                           tick forever (confirmed live 2026-08-12). That whole class of
    //                           bug is structurally impossible now: Secondary owns no sender.
    //
    //  3. rebreak  (Action.instantMine) ACTIVE, but only ever on the PRIMARY. Once a primary
    //                           completes with Instant on it stays alive and idle through the
    //                           air ticks, then fires a bare STOP the instant the position is
    //                           solid again. Untouched by this rewrite -- it is simply no
    //                           longer entangled with the secondary (an Action can never BE
    //                           the secondary anymore, so the old `if (secondary) instantMine
    //                           = false` cross-talk is gone).
    // ═══════════════════════════════════════════════════════════════════
    @Getter private Action primary = null;
    @Getter private Secondary secondary = null;
    // FarReach off: bản gốc 1.21.4's dual-Action doubleMine model, restored verbatim -- the
    // secondary slot is just another Action instance (same class, same start/process/cancel),
    // not the Secondary hold/release/ticks latch above. Only ever populated while
    // !farReach.getValue(); the demote()-based Secondary model above owns doubleMine when
    // FarReach is on. See Action's own farReach branches (start/process/cancel/getSpeed).
    @Getter private Action legacySecondary = null;

    /** homovore's SECONDARY_TIMEOUT: grace ticks past the predicted server completion. */
    private static final int SECONDARY_TIMEOUT = 10;
    // Secondary.hold()/release()'s single-slot latch state -- lives on the module, not on the
    // Secondary instance, since it names a REAL inventory slot that must be restored even if the
    // instance holding it gets replaced/dropped out from under it.
    private int secondaryHoldSlot = -1;
    private int secondaryOriginalSlot = -1;
    // ponytail: hard ceiling so a slow held item (crystal, totem...) can't wedge the one
    // secondary slot for minutes. Raise it only if a legitimately slow break gets cut off.
    private static final int SECONDARY_MAX_TICKS = 60;

    private SwitchAction switchAction = null;
    // Re-entrancy guard: cancel()/start() below send a ServerboundSetCarriedItemPacket themselves,
    // which re-fires this same PacketSendEvent.Post listener and matches the same condition --
    // without this guard that recurses forever (StackOverflowError).
    private boolean handlingSwitchReset = false;

    private final Timer instantTimer = new Timer();
    private final Timer mineTimer = new Timer();

    // FarReach/GrimDecoy's self-throttle, ported verbatim from homovore-public's SpeedMineModule
    // (canBegin/trackStarts/delayBalance/lastStopMs -- github.com/leonetics/homovore-public). This
    // is the piece that was missing after just porting the decoy send itself: it's homovore's OWN
    // local simulation of GrimAC's FastBreak.blockDelayBalance formula (checks/impl/breaking/
    // FastBreak.java: breakDelay>=275 ? balance*=0.9 : balance+=300-breakDelay; flags past 1000),
    // used to pre-emptively hold off starting a NEW mine (see Action.tryStart()) whenever doing so
    // NOW would push Grim's real balance past a safety margin (900) -- without it, decoy mode sends
    // TWICE the START_DESTROY_BLOCK traffic (trackStarts counts the decoy as its own start) with NO
    // pacing, which is worse for FastBreak than not using decoy at all, not better.
    private double delayBalance = 0;
    private long lastStopMs = 0;

    // homovore's `breakDelay` setting (default 6 ticks). Not exposed as a setting here on purpose
    // (user constraint: FarReach must stay a single toggle) -- hardcoded to homovore's default.
    // ponytail: fixed 6 ticks, promote to a NumberSetting only if a server needs different pacing.
    private static final int STOP_COOLDOWN_TICKS = 6;
    private int stopCooldown = 0;

    /**
     * homovore's `stopCooldown == 0 && canBegin()` gate (startMine/onTick). THIS is what was
     * missing: trackStarts() was already charging the balance but nothing ever refused to start.
     * Only applied while FarReach is on -- the plain (no-decoy) path is the pre-existing
     * known-good behaviour and stays unthrottled.
     */
    // Mode-agnostic "is the one extra mining slot taken" check -- FarReach on reads Secondary,
    // FarReach off reads legacySecondary. Both models only ever have one passive slot at a time.
    private boolean hasSecondarySlot() {
        return farReach.getValue() ? secondary != null : legacySecondary != null;
    }

    private boolean canStartNow() {
        // Mirrors process()'s own interactPaused gate: without this, a NEW Action created
        // while the real player is mid-eat (Auto retargeting a moving surround block, say)
        // skips process()'s pause check entirely -- tryStart() is called once straight out of
        // the Action constructor, unconditionally sending START_DESTROY_BLOCK + a swing packet
        // for the fresh position regardless of interactPaused. Existing actions already idle
        // correctly during a pause; only fresh ones were slipping through.
        if (interactPaused) return false;
        if (!farReach.getValue()) return true;
        return stopCooldown == 0 && canBegin();
    }

    private boolean canBegin() {
        long delay = System.currentTimeMillis() - lastStopMs;
        if (delay >= 275) return true; // grim decays the balance instead
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

    // cooldown mirrors homovore's stopBreak(slot, cooldown) flag: only the STOP that actually
    // completes a break arms the cooldown; the single hand-off STOP that Action.demote() sends
    // (homovore's demote() -> stopBreak(slot, false)) must not.
    private void markStop(boolean cooldown) {
        lastStopMs = System.currentTimeMillis();
        if (cooldown) stopCooldown = STOP_COOLDOWN_TICKS;
    }
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

    // Primary only -- the secondary is passive and sends nothing, so there is nothing for it to
    // rotate towards (homovore's tickSecondary() has no rotation either).
    @SubscribeEvent(priority = RotationPriorities.SPEED_MINE)
    public void onClientRotation(ClientRotationEvent event) {
        if (event.isCancelled()) return;

        Action active = primary != null && primary.isRotateActive() ? primary : null;
        if (active == null) return;

        Direction direction = WorldUtils.getClosestDirection(active.getPosition(), true);
        float[] rotations = RotationUtils.getRotations(WorldUtils.getHitVector(active.getPosition(), direction));
        event.setYaw(rotations[0]);
        event.setPitch(rotations[1]);
    }

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (isDeferringToProxy()) return;
        if (mc.player == null || mc.level == null) return;

        if (stopCooldown > 0) stopCooldown--;

        // Also drops the secondary if doubleMine got toggled off mid-flight -- the old
        // `doubleMine && ...` gate just stopped ticking it, leaving the slot occupied forever.
        // release() runs on every drop path (not just process()'s own internal ones) so toggling
        // Double off mid-latch doesn't strand the real inventory selection on the latched tool.
        if (farReach.getValue()) {
            if (secondary != null && !doubleMine.getValue()) {
                secondary.release();
                secondary = null;
            } else if (secondary != null && secondary.process()) {
                secondary = null;
            }
        } else if (doubleMine.getValue() && legacySecondary != null && legacySecondary.process()) {
            // bản gốc verbatim: no toggle-off cleanup case -- doubleMine flipping off mid-flight
            // just stops ticking it here, exactly like the original (known old quirk, not fixed
            // in this path on purpose -- see legacySecondary's field doc).
            legacySecondary = null;
        }
        if (primary != null && primary.process()) primary = null;

        // Sync mining state to the client for rendering
        if (isProxyActive()) {
            syncMiningStateToClient();
        }

        if (!auto.getValue()) return;
        BlockPos secondaryPos = farReach.getValue() ? (secondary != null ? secondary.getPosition() : null) : (legacySecondary != null ? legacySecondary.getPosition() : null);
        int secondaryPriority = farReach.getValue() ? (secondary != null ? secondary.getPriority() : 0) : (legacySecondary != null ? legacySecondary.getPriority() : 0);
        if ((primary != null && primary.getPriority() > 0 && !WorldUtils.isReplaceable(primary.getPosition())) || (secondaryPos != null && secondaryPriority > 0 && !WorldUtils.isReplaceable(secondaryPos)))
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

            // Deliberately does NOT skip the search just because primary is idling in Instant's
            // rebreak-wait (primary.isInstantMine()) -- that gate existed in the 1.21.4 original too,
            // but it isn't in homovore-public's model (hasFreePrimary() there is just "pos == null ||
            // finished", never gated on the idle/rebreak wait) and it's what caused Instant to stop
            // actively re-targeting the enemy's moving surround/phase positions while primed: this
            // whole inside/outside sweep would just never run again until InstantTimeout expired.
            // secondary/legacySecondary still blocks -- there's only ever room for the one extra slot.
            if (hasSecondarySlot()) return;

            if (target != null) {
                Runnable inside = () -> {
                    List<BlockPos> insidePositions = HoleUtils.getInsidePositions(target.player()).stream().filter(insidePosition -> !mc.level.getBlockState(insidePosition).canBeReplaced()).toList();;
                    for (BlockPos position : insidePositions) {
                        if (primary != null && hasSecondarySlot()) break;
                        if (isInvalid(position) || isOutOfRange(position)) continue;
                        handle(position, 0);
                    }
                };
                Runnable outside = () -> {
                    List<BlockPos> surroundPositions = HoleUtils.getFeetPositions(target.player(), true, false, true).stream().filter(pos -> !mc.level.getBlockState(pos).canBeReplaced()).toList();
                    if (HoleUtils.isPlayerInHole(target.player()) || !holeCheck.getValue()) {
                        for (BlockPos position : surroundPositions) {
                            if (primary != null && hasSecondarySlot()) break;
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
                        if (primary != null && hasSecondarySlot()) break;
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
                // Secondary deliberately untouched: it has no client-side mining state to reset.
                // Its break lives entirely in the server's delayed-destroy slot, which a hotbar
                // switch doesn't disturb, and it has no sender to restart in the first place.
                //
                // tryStart(), not start(): if the FastBreak balance/cooldown says no right now,
                // process()'s !started branch retries next tick instead of forcing the packets out.
                if (primary != null) {
                    primary.cancel();
                    primary.tryStart();
                }
                // bản gốc: doubleMine's dual-Action secondary DOES have real client-side mining
                // state (unlike the demote()-based Secondary latch above) and needs the same
                // switch-reset treatment.
                if (!farReach.getValue() && legacySecondary != null) {
                    legacySecondary.cancel();
                    legacySecondary.tryStart();
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
            legacySecondary = null;
            return;
        }

        // There was no onDisable at all before. Toggling the module off mid-mine just dropped
        // the in-flight Actions on the floor -- and since start() intentionally leaves the REAL
        // SERVER holding the pickaxe until process() switches back, killing the module before
        // that point stranded the server on the pickaxe permanently. Everything that placed
        // blocks afterwards (AutoCrystal especially) then silently placed nothing while the
        // proxy kept predicting the item was used. Cancel properly so the slot gets restored.
        // Secondary just gets dropped -- homovore's clearSecondary() sends nothing either, and
        // there is no in-flight client state of ours to unwind, EXCEPT the late-break tool latch
        // (see Secondary.hold()) which does need to hand the slot back on the way out.
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

    private boolean handle(BlockPos position, int priority) {
        if (mc.gameMode.getPlayerMode() == GameType.CREATIVE || mc.gameMode.getPlayerMode() == GameType.SPECTATOR) return false;
        if (mc.level.getBlockState(position).getBlock().defaultDestroyTime() == -1) return false;
        boolean listed = whitelist.isWhitelistContains(mc.level.getBlockState(position).getBlock());
        boolean allowedByList = switch (whitelistMode.getValue()) {
            case "WhiteList" -> listed;
            case "BlackList" -> !listed;
            default -> true; // All
        };
        if (!allowedByList) return false;
        if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(position)) > Mth.square(range.getValue().doubleValue()))
            return false;

        if ((primary != null && primary.getPosition().equals(position)) || (secondary != null && secondary.getPosition().equals(position)) || (legacySecondary != null && legacySecondary.getPosition().equals(position))) return true;

        if (!farReach.getValue()) {
            // bản gốc 1.21.4 verbatim dual-Action swap -- no demote()/latch model, the outgoing
            // primary just BECOMES legacySecondary (unless it's mid-Instant, in which case it's
            // dropped with no cancel, matching the original exactly).
            if (doubleMine.getValue()) {
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
            } else {
                if (primary != null) primary.cancel();
                primary = new Action(position, priority);
            }
            return true;
        }

        if (doubleMine.getValue()) {
            // homovore's startMine(): demote the outgoing primary into the passive slot if
            // there's room, otherwise abort it. The old code assigned `secondary = primary`,
            // i.e. kept running the ACTIVE state machine on it under a different field name,
            // and when the secondary slot was already taken it dropped the old primary on the
            // floor with no cancel at all (stranding the real server on the mining slot).
            if (primary != null) {
                Secondary demoted = secondary == null && !primary.isInstantMine() ? primary.demote() : null;
                if (demoted != null) secondary = demoted;
                else primary.cancel();
            }
            primary = new Action(position, priority);
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
        if (farReach.getValue()) return secondary != null && secondary.getPosition().equals(position);
        return legacySecondary != null && legacySecondary.getPosition().equals(position);
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

    // homovore-public's DECOY_Y_OFFSET (SpeedMineModule.java), verbatim -- see farReach's own doc.
    private static final int GRIM_DECOY_Y_OFFSET = 2000;

    // homovore's sendAction() (SpeedMineModule.java) sends EVERY block-break packet this way --
    // START/STOP/ABORT alike, not just the decoy -- always the raw 3-arg ServerboundPlayerActionPacket
    // constructor (sequence hardcoded to 0 by that ctor), NEVER through NetworkUtils.sendSequencedPacket/
    // BlockStatePredictionHandler's real incrementing sequence counter. Turns out that distinction is
    // exactly what was still breaking FarReach/GrimDecoy after the first "make the decoy raw" fix:
    // mixing a REAL incrementing sequence (from serverSendSequenced, used for the genuine start/stop)
    // with the decoy's seq=0 makes the sequence numbers GrimAC/vanilla observes from this player go
    // e.g. 5 -> 0 -> 6 -- backwards -- instead of homovore's uniform 0 -> 0 -> 0 throughout. Only
    // used when FarReach is on; the normal (non-FarReach) path is untouched and keeps using the real
    // sequenced sends, since there's no decoy there to desync against in the first place.
    private void sendRawPlayerAction(ServerboundPlayerActionPacket.Action action, BlockPos target, Direction face) {
        sendRawPlayerAction(action, target, face, false);
    }

    private void sendRawPlayerAction(ServerboundPlayerActionPacket.Action action, BlockPos target, Direction face, boolean cooldown) {
        if (action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) markStop(cooldown);
        serverSend(new ServerboundPlayerActionPacket(action, target, face));
    }

    /**
     * The passive doubleMine slot. See the state-ownership comment on the `secondary` field.
     *
     * Deliberately NOT an Action: it has no start(), no cancel(), no packet sender and no
     * rotation of any kind. Action.demote() already sent the single STOP that handed this
     * position to the server's own delayed-destroy path; from here we only observe.
     *
     * Matches homovore's CURRENT tickSecondary() (verified 2026-08-13 against the live GitHub
     * source, not the stale local checkout -- gh cli / raw.githubusercontent.com going forward):
     * progress is a pure LOCAL ESTIMATE computed off the fastest slot in the inventory (never the
     * currently-selected one, same as the primary's silent-mine trick -- no need to physically
     * hold anything for most of the break), and only ONE tick before that estimate predicts
     * completion does it actually latch the real tool into hand so the SERVER's own background
     * destroy-progress (ServerPlayerGameMode.continueDestroyBlock, which reads whatever is truly
     * equipped) can catch up and finish the block for real. That transient, late latch is what the
     * previous revision of this class was missing -- without it the server's own progress on this
     * position barely moves while the primary is free to hold something else the whole time, so
     * Double silently stopped finishing its second block. release() hands the slot back the moment
     * the latch is no longer needed (done, timed out, or dropped) exactly like homovore's
     * clearSecondary().
     */
    @Getter
    public class Secondary {
        private final BlockPos position;
        private final int priority;
        private BlockState state;
        private float progress;
        private float prevProgress;
        private int ticks;
        private boolean holding;

        private Secondary(BlockPos position, int priority, BlockState state, float progress) {
            this.position = position;
            this.priority = priority;
            this.state = state;
            this.progress = progress;
            this.prevProgress = progress;
        }

        /** Kept for the module API other modules already call (AutoTotem, Blocker). */
        public boolean isMining() {
            return true;
        }

        /** Always 1.0 -- the server needs full progress for a delayed destroy, not Threshold. */
        public float getSpeed() {
            return 1.0f;
        }

        /**
         * Clamped for display/proxy-sync. The raw field is allowed to overshoot 1.0 -- that
         * overshoot is exactly what the SECONDARY_TIMEOUT grace period measures -- but the
         * progress bar and the S2C_MINING_STATE sync must not see more than a full bar.
         */
        public float getProgress() {
            return Mth.clamp(progress, 0.0f, 1.0f);
        }

        /** @return true when this slot is done (broken, gone, or written off) and should be dropped. */
        public boolean process() {
            if (isOutOfRange(position)) {
                release();
                return true;
            }

            BlockState current = mc.level.getBlockState(position);
            if (current.canBeReplaced()) {
                // The server really did finish it. Fire this project's own completion
                // side-effects -- note this is AFTER the fact (the block is already gone),
                // unlike the primary which posts the event as it sends the breaking STOP.
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

            // homovore's `secondaryTicks >= expected - 1` -- one tick before the estimate says
            // this finishes, actually hold the real tool so the server can finish it for real.
            if (!holding && progress + delta >= 1.0f) hold(bestSlot);

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

        // Only while Switch has somewhere to switch TO -- None means the user wants zero
        // automatic slot changes, matching every other auto-switch path in this module.
        private void hold(int slot) {
            if (switchMode.getValue().equalsIgnoreCase("None")) return;
            int selected = mc.player.getInventory().getSelectedSlot();
            holding = true;
            if (selected == slot) return; // already there -- nothing to send, still marks holding
            secondaryOriginalSlot = selected;
            secondaryHoldSlot = slot;
            if (isProxyActive()) serverSend(new ServerboundSetCarriedItemPacket(slot));
            else mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
            // Same bug as fireBreakBurst's setSelectedSlot calls: mc.player IS the real
            // connected client's own player entity, so this is directly visible in the hotbar --
            // only Normal is meant to show the switch. This is DoubleMine+FarReach's actual
            // late-break tool latch, so it fires on essentially every secondary completion.
            if (switchMode.getValue().equalsIgnoreCase("Normal")) mc.player.getInventory().setSelectedSlot(slot);
        }

        /** Hands the latched slot back. Safe to call whether or not hold() ever actually switched. */
        private void release() {
            if (!holding) return;
            holding = false;
            if (secondaryHoldSlot == -1) return;
            int restore = secondaryOriginalSlot;
            secondaryHoldSlot = -1;
            secondaryOriginalSlot = -1;
            if (mc.player == null) return;
            // The equality shortcut below only makes sense for Normal, where local selection is
            // a faithful mirror of what the server actually holds. Silent/AltSwap/AltPickup
            // deliberately never moved it in hold() -- checking it here would see local still
            // sitting on `restore` (untouched) and wrongly skip the real server-side restore
            // packet, leaving the SERVER stuck on the held tool indefinitely.
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
        private int startSlot = -1; // slot we switched FROM at start

        // Mirrors homovore's own started/canBegin() gate: a fresh Action doesn't necessarily call
        // start() the instant it's created -- if canBegin() says doing so right now would push
        // GrimAC's own FastBreak balance too high, tryStart() just doesn't fire yet, and process()
        // retries it every tick (see the top of process()) until it's safe.
        private boolean started;

        // Reasserted every tick from SpeedMineModule.onClientRotation below instead of the old
        // one-shot rotate() call -- see RotationManager class doc. Reset at the top of every
        // process() call so a tick that no longer clears the late-mining gate stops holding it.
        private boolean rotateActive;

        public Action(BlockPos position, int priority) {
            this.position = position;
            this.state = mc.level.getBlockState(position);
            this.priority = priority;

            // homovore's startMine(): sets the target, then `if (stopCooldown == 0 && canBegin())
            // begin();` -- it does NOT unconditionally fire the packets. process() below retries
            // every tick while !started, exactly like homovore's onTick !started branch.
            tryStart();
        }

        /**
         * homovore's demote(): hand this in-flight break over to the passive secondary slot.
         *
         * Sends EXACTLY ONE real STOP_DESTROY_BLOCK for the current position (cooldown=false,
         * matching homovore's stopBreak(slot, false)) and nothing else, ever again. With
         * progress still under the server's 0.7 threshold that STOP doesn't break the block --
         * it parks it in ServerPlayerGameMode's delayed-destroy slot, which the server finishes
         * by itself. clientRemove is deliberately skipped for this STOP (see fireBreakBurst):
         * removing the block locally would make Secondary.process() instantly believe the break
         * landed on the very next tick.
         *
         * @return the new passive slot, or null if there's nothing live to hand over (caller
         *         then cancels this Action instead, like homovore's abortBreak() fallback).
         */
        private Secondary demote() {
            if (!started) return null;

            BlockState current = mc.level.getBlockState(position);
            if (current.canBeReplaced()) return null;

            Direction direction = WorldUtils.getClosestDirection(position, true);
            int slot = switchMode.getValue().equalsIgnoreCase("None") ? -1 : InventoryUtils.findFastestItem(current, InventoryUtils.HOTBAR_START, switchMode.getValue().equalsIgnoreCase("AltSwap") || switchMode.getValue().equalsIgnoreCase("AltPickup") ? InventoryUtils.INVENTORY_END : InventoryUtils.HOTBAR_END);
            if (slot == -1) slot = mc.player.getInventory().getSelectedSlot();

            fireBreakBurst(direction, slot, true);

            // progress is already in the server's own units (accumulated getMineSpeed per tick),
            // so it carries over directly as "what the server has accrued so far".
            return new Secondary(position, priority, current, progress);
        }

        /** homovore's `if (stopCooldown == 0 && canBegin()) begin();`. */
        private void tryStart() {
            if (!canStartNow()) return;
            start();
        }

        public boolean process() {
            rotateActive = false;

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
                started = false;
            }

            // homovore onTick's !started branch: drop the target if it's gone, otherwise keep
            // retrying the throttled start every tick until the FastBreak balance allows it.
            if (!started) {
                if (mc.level.getBlockState(position).canBeReplaced()) return true;
                tryStart();
                return false;
            }

            if (!farReach.getValue()) return legacyProcess();

            // Instant's real mechanism, verified against vanilla server source (.mcref mojmap,
            // ServerPlayerGameMode.handleBlockBreakAction, STOP_DESTROY_BLOCK branch) rather than
            // guessed: the server only actually breaks a block on STOP_DESTROY_BLOCK if the packet's
            // pos still matches its OWN this.destroyPos field (set by the last START_DESTROY_BLOCK
            // it accepted) -- and it computes ticksSpentDestroying as gameTicks - destroyProgressStart,
            // a field that is NEVER reset just because a break succeeded. So once the real START above
            // has been sent once, sending a BARE STOP_DESTROY_BLOCK for that same position again --
            // no new START needed -- reuses that stale, ever-growing tick count and satisfies the
            // server's own destroyProgress>=0.7 threshold trivially, breaking the block in one packet
            // regardless of real tool speed. This is homovore-public's "rebreak" (SpeedMineModule.java,
            // onTick's `finished` branch calling bare stopBreak() once state is solid again, no START)
            // -- same technique, and it needs no GrimAC/FarReach involvement, vanilla behaves this way
            // natively. fireBreakBurst() below already sends STOP+swing+slotswitch with no START, so
            // reaching the `if (mining)` branch further down while progress is already pinned at max
            // (from the prior completion) does exactly this the instant the position solidifies again
            // -- the ONLY piece needed here is to stay alive and idle through the air tick in between
            // instead of tearing the Action down, which is what canBeReplaced() unconditionally did.
            if (mc.level.getBlockState(position).canBeReplaced()) {
                if (instantMine) {
                    if (async.getValue()) {
                        // Async: never idle-wait through the air tick, never give up on
                        // InstantTimeout -- keep re-firing the rebreak burst continuously,
                        // paced only by InstantDelay. this.state still holds the last known
                        // solid block (only ever overwritten below when NOT air), so the slot
                        // pick stays meaningful. Target acquisition is untouched -- Auto's own
                        // scan (onPlayerUpdate/getTarget/handle) still runs every tick exactly
                        // as before; this only decides what an already-tracked Action does
                        // with its own position while it happens to read as air.
                        // Gap: this used to fire unconditionally on its own timer -- unlike the
                        // normal completion path (whileEating.getValue() || !isUsingItem()),
                        // Async's rebreak-through-air never checked eating at all, so it kept
                        // switching to the fast tool mid-eat regardless of interactPaused (whose
                        // pause only applies BEFORE this method is even reached -- if the client's
                        // UseItemPacket hadn't been processed on the Netty thread yet by this
                        // exact tick, interactPaused hadn't engaged yet either, and Async would
                        // fire right through that window). Reported as "gate for eating not
                        // strong enough -- still takes a while before I can eat."
                        if ((whileEating.getValue() || !mc.player.isUsingItem()) && instantTimer.hasTimeElapsed(instantDelay.getValue().longValue() * 50L)) {
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

                if ((rotate.getValue().equalsIgnoreCase("MovementSync") || rotate.getValue().equalsIgnoreCase("Normal")) && progress + (delta * 2) >= getSpeed()) {
                    float[] rots = RotationUtils.getRotations(WorldUtils.getHitVector(position, direction));
                    if (rotate.getValue().equalsIgnoreCase("MovementSync")) {
                        if (isProxyActive()) {
                            // Rot-only (no X/Y/Z) -- see RotationManager.packetRotate's comment.
                            // Sending position built from mc.player's proxy-mirrored coordinates
                            // races the client's own movement packets already being forwarded,
                            // causing rubberbanding on the real server.
                            serverSend(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                                    rots[0], rots[1],
                                    eu.client.pingbypass.PingBypassFlags.clientOnGround,
                                    eu.client.pingbypass.PingBypassFlags.clientHorizontalCollision));
                        } else {
                            rotateActive = true;
                        }
                    } else {
                        // "Normal" -- bản gốc 1.21.4 verbatim, both sides. Proxy sends a full
                        // Pos+Rot packet built from mc.player's live coordinates (known trade-off:
                        // GrimAC runs a full movement-prediction cycle for any packet carrying a
                        // position -- MovementSync exists to avoid exactly this). Local uses the
                        // OLD PriorityBlockingQueue<LegacyRotation> mechanism ported back verbatim
                        // (RotationManager.legacyRotate) -- deliberately NOT rotateActive/
                        // ClientRotationEvent: that system's computeMoveFix octant-remap didn't
                        // exist in bản gốc at all (a from-scratch addition of this port to fix a
                        // GrimAC issue THAT model introduces) and "Normal" was never meant to go
                        // through it -- confirmed live: Sprint Instant + Rotate=Normal, turning
                        // away from a mining target and pressing S moved at ~1-2km/h instead of
                        // full speed, because the remap (built for vanilla's own travel()) got
                        // read by Instant's separate direct-velocity path instead.
                        if (isProxyActive()) {
                            serverSend(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot(
                                    mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                                    rots[0], rots[1], mc.player.onGround(), mc.player.horizontalCollision));
                        } else {
                            EUClient.ROTATION_MANAGER.legacyRotate(rots, EUClient.ROTATION_MANAGER.getLegacyModulePriority(SpeedMineModule.this));
                        }
                    }
                }

                // Completing swaps the fast tool into mc.player.getInventory().getSelectedSlot()
                // -- read live, right here, whatever that currently is. WhileEating lets progress
                // keep accumulating while the player is mid-use-item, but ANY switch mode except
                // None sends a real ServerboundSetCarriedItemPacket -- Silent just doesn't also
                // touch the client's own displayed hotbar. The SERVER stops tracking "using item"
                // the instant its held item changes regardless of what the client shows, so a
                // Silent completion mid-eat silently cancelled the eat server-side every time
                // (this is what made Instant's rebreak loop -- same `mining` branch, block
                // resolidifies, fires again -- block eating outright: this check used to only
                // cover Normal/AltSwap/AltPickup and let Silent's switch straight through). Only
                // None is exempt because it's the only mode that genuinely sends nothing. Let
                // progress keep climbing (uncapped above) but hold off actually completing (and
                // switching) until the item use finishes for every mode that switches.
                boolean switchTouchesInventory = !switchMode.getValue().equalsIgnoreCase("None");
                if (progress >= getSpeed() && !state.canBeReplaced() && (whileEating.getValue() || !mc.player.isUsingItem())
                        && !(switchTouchesInventory && mc.player.isUsingItem())) {
                    if (!instantMine || instantTimer.hasTimeElapsed(instantDelay.getValue().longValue() * 50L)) {
                        fireBreakBurst(direction, slot, false);
                        if (!instantMine) mineTimer.reset();
                    }

                    attempts++;
                    // The backoff below is what stops a REJECTED completion (block doesn't
                    // actually die -- FarReach's Threshold not honored by this server's Grim,
                    // packet loss, whatever) from falling straight back into `if (mining)` next
                    // tick with progress still pinned at max and re-firing fireBreakBurst()
                    // every tick forever (confirmed live 2026-08-12: continuous
                    // STOP_DESTROY_BLOCK on one frozen position, unbounded). It used to be
                    // skipped for an Action that was serving as the module's secondary, which
                    // is precisely the case that spammed. There is no such case anymore -- an
                    // Action is never the secondary -- so this is now simply unconditional.
                    // Instant is the one deliberate exception, and it is bounded by
                    // InstantTimeout + the air-check above rather than by this flag.
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

                    // Never "drop me" here: the primary always stays until the air-check at the
                    // top of process() confirms the break (or Instant's rebreak wait ends). The
                    // old `return doubleMine && secondary` existed only to make the module drop
                    // its secondary Action reference -- Secondary owns that lifecycle itself now.
                    return false;
                }
            } else {
                // Only restart if the block is still there and we haven't just sent STOP -- OR
                // the block still hasn't broken 1s after we did (attempts > 0 forever blocked the
                // retry here otherwise: once mining stalls out, attempts is never reset back to 0
                // except by start() itself, so a block the server refuses to break -- packet loss,
                // desync, anticheat rejection -- got stuck at full progress forever with no way
                // to recover on its own).
                if (!mc.level.getBlockState(position).canBeReplaced() && (attempts == 0 || System.currentTimeMillis() - stallTime >= 1000L)) {
                    tryStart();
                }
            }

            return false;
        }

        /**
         * bản gốc 1.21.4 verbatim (FarReach off): no rebreak-wait/Instant-timeout grace on the
         * air-check, no stallTime backoff on the restart, and the completion return value tells
         * the module whether to drop this Action's own reference (true only when it's playing
         * the legacySecondary role) -- see handle()'s dual-Action swap and onPlayerUpdate's
         * `legacySecondary.process()` call. Reuses the shared Action fields (progress/mining/
         * instantMine/attempts/state) instead of a separate class since bản gốc's secondary was
         * never anything more than "another Action instance".
         */
        private boolean legacyProcess() {
            boolean isSecondaryRole = legacySecondary != null && position.equals(legacySecondary.getPosition());
            if (isSecondaryRole) instantMine = false;

            if (mc.level.getBlockState(position).canBeReplaced()) {
                if (instantMine) {
                    // Bug: this whole block was gated on async.getValue() before -- without
                    // Async, instantMine dropped straight to the unconditional cancel() below on
                    // the very first air tick (right after the FIRST successful break), instead
                    // of idling like the current (farReach-on) model's own instantMine branch
                    // does. Instant genuinely needs this baseline "wait, don't die" regardless of
                    // Async -- Async only changes what happens DURING the wait (keep firing vs
                    // idle), it was never what made Instant survive the air tick in the first
                    // place. Never applies to a legacySecondary Action (instantMine is always
                    // false for those, above).
                    if (async.getValue()) {
                        // Same gap as the current model's Async branch -- see its comment. Never
                        // checked eating on its own, only relied on interactPaused engaging
                        // upstream in time.
                        if ((whileEating.getValue() || !mc.player.isUsingItem()) && instantTimer.hasTimeElapsed(instantDelay.getValue().longValue() * 50L)) {
                            Direction asyncDirection = WorldUtils.getClosestDirection(position, true);
                            int asyncSlot = switchMode.getValue().equalsIgnoreCase("None") ? -1 : InventoryUtils.findFastestItem(this.state, InventoryUtils.HOTBAR_START, switchMode.getValue().equalsIgnoreCase("AltSwap") || switchMode.getValue().equalsIgnoreCase("AltPickup") ? InventoryUtils.INVENTORY_END : InventoryUtils.HOTBAR_END);
                            if (asyncSlot == -1) asyncSlot = mc.player.getInventory().getSelectedSlot();
                            legacyFireBreakBurst(asyncDirection, asyncSlot, isSecondaryRole);
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

                if ((rotate.getValue().equalsIgnoreCase("MovementSync") || rotate.getValue().equalsIgnoreCase("Normal")) && progress + (delta * 2) >= getSpeed()) {
                    float[] rots = RotationUtils.getRotations(WorldUtils.getHitVector(position, direction));
                    if (rotate.getValue().equalsIgnoreCase("MovementSync")) {
                        if (isProxyActive()) {
                            serverSend(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                                    rots[0], rots[1],
                                    eu.client.pingbypass.PingBypassFlags.clientOnGround,
                                    eu.client.pingbypass.PingBypassFlags.clientHorizontalCollision));
                        } else {
                            rotateActive = true;
                        }
                    } else {
                        // Same as the current model's Normal branch -- legacyRotate, not
                        // rotateActive (see that comment).
                        if (isProxyActive()) {
                            serverSend(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot(
                                    mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                                    rots[0], rots[1], mc.player.onGround(), mc.player.horizontalCollision));
                        } else {
                            EUClient.ROTATION_MANAGER.legacyRotate(rots, EUClient.ROTATION_MANAGER.getLegacyModulePriority(SpeedMineModule.this));
                        }
                    }
                }

                // switchTouchesInventory guard kept even in legacy mode -- it's the fix for the
                // Silent-mode mid-eat cancel bug (see process()'s own comment), not a "feel"
                // difference bản gốc's rebreak model ever had a stance on.
                boolean switchTouchesInventory = !switchMode.getValue().equalsIgnoreCase("None");
                if (progress >= getSpeed() && !state.canBeReplaced() && (whileEating.getValue() || !mc.player.isUsingItem())
                        && !(switchTouchesInventory && mc.player.isUsingItem())) {
                    legacyFireBreakBurst(direction, slot, isSecondaryRole);

                    attempts++;
                    if (!isSecondaryRole) {
                        if (!instant.getValue()) {
                            this.mining = false;
                        } else {
                            this.instantMine = true;
                            instantTimer.reset();
                        }
                    }

                    return doubleMine.getValue() && isSecondaryRole;
                }
            } else {
                // bản gốc verbatim: no stallTime backoff -- once attempts != 0 this genuinely
                // wedges until start()/cancel() resets it (known old limitation).
                if (!mc.level.getBlockState(position).canBeReplaced() && attempts == 0) {
                    start();
                }
            }

            return false;
        }

        // bản gốc verbatim inline burst (no demote() concept -- legacySecondary is a whole
        // separate Action, not a hand-off). rotate/grim/clientRemove/switch-back semantics are
        // the current project's -- only the FarReach decoy/threshold pieces are omitted.
        private void legacyFireBreakBurst(Direction direction, int slot, boolean isSecondaryRole) {
            EUClient.EVENT_HANDLER.post(new DestroyBlockEvent(position));

            if (rotate.getValue().equalsIgnoreCase("Packet")) {
                float[] rots = RotationUtils.getRotations(WorldUtils.getHitVector(position, direction));
                if (isProxyActive()) {
                    serverSend(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot(
                            mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                            rots[0], rots[1], mc.player.onGround(), mc.player.horizontalCollision));
                } else {
                    EUClient.ROTATION_MANAGER.packetRotate(rots);
                }
            }

            int previousSlot = mc.player.getInventory().getSelectedSlot();

            if (isProxyActive()) {
                int mineSlot = switchMode.getValue().equalsIgnoreCase("None") ? -1 : slot;
                boolean needSwitch = mineSlot != -1 && mineSlot != previousSlot;

                if (needSwitch) serverSend(new ServerboundSetCarriedItemPacket(mineSlot));
                serverSendSequenced(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, position, direction, seq));
                if (grim.getValue()) serverSend(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, position.above(500), direction));
                serverSend(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                if (needSwitch) serverSend(new ServerboundSetCarriedItemPacket(previousSlot));
            } else {
                InventoryUtils.switchSlot(switchMode.getValue(), slot, previousSlot);

                NetworkUtils.sendSequencedPacket(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, position, direction, seq));
                if (grim.getValue()) mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, position.above(500), direction));
                mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));

                if (strict.getValue() || (doubleMine.getValue() && isSecondaryRole)) switchAction = new SwitchAction(slot, previousSlot, System.currentTimeMillis());
                else if (switchAction == null) InventoryUtils.switchBack(switchMode.getValue(), slot, previousSlot);
            }

            mc.level.removeBlock(position, false);
        }

        // Extracted out of process()'s completion branch so Async's blind re-fire (see the
        // air-check above) can send the exact same rotate/switch/STOP_DESTROY/grim/swing/
        // clientRemove burst without duplicating it -- the two call sites used to drift out of
        // sync being separate copies.
        /**
         * @param demote true when this is demote()'s single hand-off STOP rather than a real
         *               completion. A demotion must NOT post DestroyBlockEvent (nothing broke
         *               yet -- Secondary posts it once the server confirms), must NOT arm the
         *               FarReach stop-cooldown (homovore's stopBreak(slot, false)), and must
         *               NOT clientRemove the block (that would make Secondary.process() see air
         *               on the very next tick and declare a break that never happened). It DOES
         *               defer the local switchback like Strict does, which leaves the tool held
         *               a moment longer -- free help for the server's delayed destroy.
         */
        private void fireBreakBurst(Direction direction, int slot, boolean demote) {
            fireBreakBurst(direction, slot, demote, !demote);
        }

        /**
         * @param armCooldown whether this burst arms FarReach's shared, MODULE-LEVEL stopCooldown
         *                    (see canStartNow()). Normally tracks !demote, but Async's continuous
         *                    rebreak-through-air loop needs its own bursts to NEVER arm it: that
         *                    cooldown gates tryStart() for every OTHER Action too (a moving Auto/
         *                    Surround target switching to a new position), so Async re-arming it
         *                    on every one of its own (deliberately rapid, no-wait) fires meant a
         *                    NEW target's tryStart() kept getting throttled behind it -- reported
         *                    as "mines ~7 blocks in a row then stalls a beat, repeatedly" (the
         *                    stall being STOP_COOLDOWN_TICKS after whichever Async fire happened
         *                    to land right before the target moved).
         */
        private void fireBreakBurst(Direction direction, int slot, boolean demote, boolean armCooldown) {
            if (!demote) EUClient.EVENT_HANDLER.post(new DestroyBlockEvent(position));

            if (rotate.getValue().equalsIgnoreCase("Packet")) {
                // bản gốc 1.21.4 verbatim: full Pos+Rot packet built from mc.player's live
                // coordinates. Known trade-off -- see RotationManager.packetRotate's 2026-08-13
                // comment: GrimAC runs a full movement-prediction cycle for ANY packet carrying
                // a position, so this can rubberband on Grim servers exactly like it used to.
                if (isProxyActive()) {
                    float[] rots = RotationUtils.getRotations(WorldUtils.getHitVector(position, direction));
                    serverSend(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot(
                            mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                            rots[0], rots[1], mc.player.onGround(), mc.player.horizontalCollision));
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
                //
                // mc.player.getInventory().setSelectedSlot() below is gated to Normal ONLY: mc.player
                // IS the real connected client's own player entity here (not a separate ghost --
                // "proxy" just means packets go straight to the real server connection), so this
                // setSelectedSlot() call is directly visible in the player's own hotbar. Silent/
                // AltSwap/AltPickup exist specifically so the player never sees the switch --
                // calling this unconditionally for every non-None mode flipped the visible
                // selection to the mining slot and back for one frame even under Silent, showing
                // as the mining slot's item (and its enchant glint) briefly appearing duplicated
                // over the real held item before snapping back.
                if (mineSlot != -1) {
                    serverSend(new ServerboundSetCarriedItemPacket(mineSlot));
                    if (switchMode.getValue().equalsIgnoreCase("Normal")) mc.player.getInventory().setSelectedSlot(mineSlot);
                }

                // FarReach: homovore's sendAction() is used for the completing STOP too, not just
                // the START/decoy -- uniform seq=0, and it's the STOP that arms stopCooldown.
                if (farReach.getValue()) {
                    sendRawPlayerAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, position, direction, armCooldown);
                } else {
                    serverSendSequenced(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, position, direction, seq));
                    markStop();
                }
                if (grim.getValue()) serverSend(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, position.above(500), direction));
                serverSend(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));

                if (mineSlot != -1) {
                    serverSend(new ServerboundSetCarriedItemPacket(realPreviousSlot));
                    if (switchMode.getValue().equalsIgnoreCase("Normal")) mc.player.getInventory().setSelectedSlot(realPreviousSlot);
                }
            } else {
                InventoryUtils.switchSlot(switchMode.getValue(), slot, previousSlot);

                if (farReach.getValue()) {
                    sendRawPlayerAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, position, direction, armCooldown);
                } else {
                    NetworkUtils.sendSequencedPacket(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, position, direction, seq));
                    markStop();
                }
                if (grim.getValue()) mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, position.above(500), direction));
                mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));

                if (strict.getValue() || demote) switchAction = new SwitchAction(slot, previousSlot, System.currentTimeMillis());
                else if (switchAction == null) InventoryUtils.switchBack(switchMode.getValue(), slot, previousSlot);
            }

            // Remove block client-side so modules (and process()'s own top-of-loop
            // canBeReplaced() check) see it as air immediately instead of waiting on the server's
            // block-update round-trip.
            if (!demote) {
                mc.level.removeBlock(position, false);
            }
        }

        public void render(PoseStack matrices) {
            // InstantRender only picks the COLOR used while idling on a broken block waiting to
            // rebreak ("None" = no special override, keep whatever `color` mode already produced;
            // "Custom" = instantColor below) -- it was wrongly doubling as "render nothing at all"
            // here, so the default (None) hid the 100%-progress box the whole air-wait.
            if (mc.level.getBlockState(position).canBeReplaced() && !instantMine)
                return;

            AABB box = new AABB(position);
            // Air-wait (block already broken, sitting on instantMine waiting to resolidify):
            // neither progress nor prevProgress get touched again until the mining branch resumes
            // (the top-of-process() air-check returns before ever reaching it), but partialTick
            // keeps climbing 0->1 every rendered frame regardless. Lerping between two frozen,
            // slightly-unequal values (prevProgress from the tick before completion, progress
            // pinned at max) with a moving partialTick produced a false 99%<->100% flicker every
            // tick boundary even though nothing was actually changing. Use the final value
            // directly while frozen -- nothing to interpolate toward.
            boolean airWaiting = mc.level.getBlockState(position).canBeReplaced() && instantMine;
            double progress = airWaiting ? this.progress / getSpeed()
                    : Mth.lerp(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false), prevProgress / getSpeed(), this.progress / getSpeed());

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

            // No doubleMine special case here anymore. This used to send STOP/START/STOP for
            // the NEW position when doubleMine was on -- a garbled stand-in for homovore's
            // demote(), which applies that STOP to the OLD position being handed over. On the
            // new position that trailing STOP does real damage: the server has exactly ONE
            // delayed-destroy slot (hasDelayedDestroy/delayedDestroyPos), and a STOP at ~0
            // progress claims it for whatever position it names. So the fresh primary got
            // parked in the slot the demoted secondary needs, and the secondary -- the block
            // actually waiting on the server to finish it -- got nothing. Now demote() sends
            // that one STOP for the right block and start() is plain homovore begin().
            // homovore's begin() calls trackStarts(decoy ? 2 : 1) as its first action -- the decoy
            // counts as its own start against the SAME balance the real one does, since GrimAC's
            // FastBreak sees both as independent START_DIGGING packets. See canBegin()'s own doc.
            trackStarts(farReach.getValue() ? 2 : 1);

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

                if (farReach.getValue()) {
                    // homovore's sendAction() throughout -- see sendRawPlayerAction's doc.
                    sendRawPlayerAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, position, direction);
                    BlockPos decoyPos = grimDecoyPos();
                    sendRawPlayerAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, decoyPos, WorldUtils.getClosestDirection(decoyPos, true));
                } else {
                    serverSendSequenced(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, position, direction, seq));
                }

                serverSend(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
            } else {
                // Local: no slot switch in start(), only at STOP_DESTROY moment
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

        /**
         * bản gốc 1.21.4 verbatim: no FarReach throttle/decoy/trackStarts. On the proxy, switches
         * to the fast tool immediately and KEEPS it held for the entire mining duration (the
         * caller explicitly chose this over the current model's "switch only at STOP_DESTROY"
         * fix -- known trade-off: another proxy-side module reselecting the slot mid-mine, e.g.
         * AutoCrystal's Normal switch, can win the fight over the held item while a legacy
         * Action is in flight).
         */
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
                // Do NOT switch back -- bản gốc keeps the fast tool held on the real server for
                // the whole mining duration. process()/legacyProcess() only switch back at the
                // completing STOP (fireBreakBurst's needSwitch dance), same as bản gốc.
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

            // Never ABORT a mine that was never started -- the throttle can hold an Action in the
            // !started state, and homovore's clearMine() sends nothing in that case either.
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
            this.started = false;

            this.instantMine = false;
        }

        /**
         * bản gốc 1.21.4 verbatim: ABORT is only ever sent when doubleMine is OFF -- with
         * doubleMine on, cancel() sends nothing at all (known old quirk, kept as-is).
         */
        private void legacyCancel() {
            if (!doubleMine.getValue()) {
                Direction direction = WorldUtils.getClosestDirection(position, true);
                if (isProxyActive()) {
                    serverSendSequenced(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, position, direction, seq));
                    serverSend(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                    serverSend(new ServerboundSetCarriedItemPacket(mc.player.getInventory().getSelectedSlot()));
                } else {
                    NetworkUtils.sendSequencedPacket(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, position, direction, seq));
                    mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                }
            }

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

        // See farReach's own doc / GRIM_DECOY_Y_OFFSET -- 2000 blocks below the real target, out of
        // the world, so the real server silently no-ops the (fake) START_DESTROY_BLOCK sent there.
        private BlockPos grimDecoyPos() {
            return position.below(GRIM_DECOY_Y_OFFSET);
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
