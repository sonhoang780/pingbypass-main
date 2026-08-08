package eu.client.modules.impl.movement;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerMoveEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.HoleUtils;
import eu.client.utils.minecraft.InventoryUtils;
import eu.client.utils.minecraft.MovementUtils;
import eu.client.utils.minecraft.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

@RegisterModule(name = "HoleSnap", description = "Pulls you toward your nearest hole.", category = Module.Category.MOVEMENT)
public class HoleSnapModule extends Module {
    public NumberSetting range = new NumberSetting("Range", "Range for the holes.", 5, 1, 8);
    public BooleanSetting doubleHoles = new BooleanSetting("DoubleHoles", "Whether or not to snap you to double holes.", true);
    public BooleanSetting quadHoles = new BooleanSetting("QuadHoles", "Whether or not to snap you to quad holes.", true);
    // Was declared but never actually read anywhere -- now wired up for real: instead of
    // disabling the instant you're centered in a hole, keep looking for the next nearest
    // DIFFERENT hole and snap to that one too, letting you hop hole-to-hole instead of stopping
    // after the first one.
    public BooleanSetting step = new BooleanSetting("Step", "Keeps snapping to the next nearest hole instead of stopping once you've reached one.", false);
    public BooleanSetting fillHole = new BooleanSetting("FillHole", "Fills every cell of the hole you just stepped out of, once you've fully left its footprint (needs Step).", new BooleanSetting.Visibility(step, true), false);
    // Same placement/render controls Surround/SelfTrap/HoleFill expose -- FillHole's placeBlock
    // call itself already went through the shared WorldUtils.placeBlock, but hardcoded "Silent"/
    // true/true/false instead of reading real settings, and (the actual root cause of "không place
    // được" on a real server) resolved each cell's direction against ONLY the pre-existing world
    // state -- an interior cell of a double/quad hole with no already-solid neighbor (every side
    // still air, since nothing's been placed yet) had no valid face to click and just silently
    // got dropped from the queue. Fixed below by tracking cells placed earlier in this same fill
    // run and passing them to getDirection's exceptions list, exactly like Surround's
    // placedPositions -- later cells can then reference an EARLIER cell in the same run as a valid
    // face the instant it's placed, not just genuinely pre-existing blocks.
    public ModeSetting autoSwitch = new ModeSetting("Switch", "The mode that will be used for automatically switching to necessary items.", "Silent", InventoryUtils.SWITCH_MODES);
    public BooleanSetting rotate = new BooleanSetting("Rotate", "Sends a packet rotation whenever placing a block.", true);
    public BooleanSetting strictDirection = new BooleanSetting("StrictDirection", "Only places using directions that face you.", false);
    public BooleanSetting crystalDestruction = new BooleanSetting("CrystalDestruction", "Destroys any crystals that interfere with block placement.", true);
    public BooleanSetting render = new BooleanSetting("Render", "Whether or not to render the place position.", true);
    // Ported from example-addon-master's HoleSnap ("Timer" tick-speed multiplier while snapping,
    // its own CustomTimer.multiplier -- this client already has an equivalent real mechanism,
    // WORLD_MANAGER.setTimerMultiplier, the same one TimerModule itself drives). Set on enable and
    // refreshed every move tick so dragging the slider live takes effect without retoggling;
    // restored to normal on disable so it never leaves the game permanently sped up.
    public NumberSetting timer = new NumberSetting("Timer", "The tick-speed multiplier to run at while HoleSnap is active (1.0 = normal speed).", 1.0f, 0.5f, 10.0f);

    public AABB hole = null;

    // The hole the player was ALREADY standing in when HoleSnap picked its target this run (null
    // if they weren't in one) -- remembered for two things: excluding it from the Step search (so
    // Step actually moves you somewhere instead of re-picking distance-0 "the hole you're already
    // in"), and knowing what to backfill once FillHole is done with it.
    private HoleUtils.Hole startingHole = null;

    // The hole snapped to LAST run -- persists across enable/disable (NOT reset in onEnable) so
    // toggling HoleSnap back on right after landing in a hole doesn't just snap you right back
    // into the same one. Cleared/replaced only once you actually land in a genuinely different
    // hole, matching "vào hole khác rồi mới đổi".
    private AABB lastUsedHole = null;

    // Real jump key state for the auto-hop-over-obstruction case in onPlayerMove -- see there.
    private boolean jumpPressed = false;
    // Counts jump ATTEMPTS (not ticks) against the current target -- a single vanilla jump only
    // ever clears a 1-block obstruction. A wall taller than that (or geometry with no valid
    // walk-in path at all) makes every attempt fail identically forever; there's no amount of
    // retrying that fixes an obstruction a real player couldn't jump over either. Past a small
    // attempt count, give up on THIS hole and let a different candidate get picked instead of
    // being stuck in place bouncing against the same wall indefinitely.
    private int jumpAttempts = 0;
    // A single AABB slot used to mean "just this one specific hole is excluded" -- fine for the
    // jump-timeout case (naturally only ever fails on ONE target before falling back to try
    // others), but isHoleReachable's mid-approach recheck can reject a DIFFERENT hole every single
    // tick, and a single slot only ever remembers the MOST RECENT rejection. With 2+ genuinely
    // blocked candidates on one side, that let the picker ping-pong back and forth between them
    // forever (A rejected -> excluded, pick B -> B rejected -> excluded, A no longer excluded ->
    // pick A again -> ...), never reaching holes.isEmpty() to actually disable (reported: "không
    // đi được mà không disable HoleSnap"). Accumulates every rejection this attempt-session instead
    // of remembering only the last one.
    private final java.util.Set<AABB> unreachableHoles = new java.util.HashSet<>();
    // Consecutive give-ups (jump-timeout OR isHoleReachable rejection) across DIFFERENT targets --
    // excluding one unreachable hole and immediately picking the next-nearest doesn't help when
    // several candidates all require clearing the same too-tall wall (e.g. overlapping single/
    // double/quad detections around the same physical gap), which just repeats the whole cycle on
    // a "new" target forever with no visible progress. A hard circuit breaker regardless of the
    // exact cause: give up on HoleSnap entirely after this many consecutive failures in a row.
    private int giveUpCount = 0;
    private static final int MAX_CONSECUTIVE_GIVE_UPS = 3;
    // Ticks since the current jump press started -- see the timeout comment in onPlayerMove.
    private int jumpWaitTicks = 0;

    // Ported from Sydney-Legacy -- used by FakeLagModule's OnlyOnHoleLeave to detect the exact
    // tick you step out of a hole (regardless of whether HoleSnap itself is even toggled).
    public static boolean isInHole(BlockPos pos) {
        return HoleUtils.getSingleHole(pos, 1, false) != null || HoleUtils.getDoubleHole(pos, 1) != null || HoleUtils.getQuadHole(pos, 1) != null;
    }

    // The hole Step just moved you OUT of, filled one cell per tick once you've actually left its
    // footprint -- filling while still standing over it would place a block right under/around
    // your own feet mid-transition.
    private final Deque<BlockPos> fillQueue = new ArrayDeque<>();
    private AABB pendingFillHole = null;
    // Ticks spent waiting for the player to "leave" pendingFillHole's footprint -- by the time
    // pendingFillHole gets set, the player is already frozen centered on the NEW hole (Step's own
    // move is done, this tick's movement got cancelled same as any other centered landing), not
    // still walking away from the old one. If the new hole is adjacent to or overlaps the old
    // one's footprint, the player's frozen position never actually leaves it, and the contains()
    // check below waited forever -- FillHole silently never ran (the reported "vài case nó không
    // hoạt động"). Force it to start anyway past this timeout instead of waiting on a departure
    // that may never happen.
    private int fillWaitTicks = 0;
    private static final int MAX_FILL_WAIT_TICKS = 20;
    // Cells already placed THIS fill run -- see the getDirection exceptions comment above.
    private final List<BlockPos> filledPositions = new ArrayList<>();

    @Override
    public void onEnable() {
        hole = null;
        startingHole = null;
        fillQueue.clear();
        pendingFillHole = null;
        filledPositions.clear();
        fillWaitTicks = 0;
        jumpAttempts = 0;
        jumpWaitTicks = 0;
        unreachableHoles.clear();
        giveUpCount = 0;
        EUClient.WORLD_MANAGER.setTimerMultiplier(timer.getValue().floatValue());

        // Step actively walks you across/out of holes -- Surround (fills the hole you're
        // standing in around you) fights that the moment you start moving, placing blocks in
        // your way mid-hop. Only relevant with Step on; plain HoleSnap just walks into one hole
        // and stops, which Surround doesn't conflict with.
        if (step.getValue()) {
            eu.client.modules.impl.combat.SurroundModule surround = EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.combat.SurroundModule.class);
            if (surround.isToggled()) surround.setToggled(false);
        }
    }

    @Override
    public void onDisable() {
        EUClient.WORLD_MANAGER.setTimerMultiplier(1.0f);
        // Don't leave the real jump key physically held down if HoleSnap gets toggled off mid-hop.
        if (jumpPressed) {
            mc.options.keyJump.setDown(false);
            jumpPressed = false;
        }
        jumpWaitTicks = 0;
    }

    @SubscribeEvent
    public void onPlayerMove(PlayerMoveEvent event) {
        EUClient.WORLD_MANAGER.setTimerMultiplier(timer.getValue().floatValue());
        if (getNull()) return;

        if (pendingFillHole != null) {
            // Only start backfilling once the player's feet have actually left the old hole's
            // footprint -- Step's move toward the NEW hole can take several ticks, during which
            // the player is still technically standing in/over the old one. Capped: the player is
            // already frozen centered on the NEW hole by the time this gets set, not still walking
            // -- if the new hole is adjacent to/overlapping the old one, they'd never register as
            // "left" at all, so give up waiting and fill anyway past MAX_FILL_WAIT_TICKS.
            if (pendingFillHole.contains(mc.player.getX(), pendingFillHole.minY + 0.5, mc.player.getZ())
                    && fillWaitTicks++ < MAX_FILL_WAIT_TICKS) return;
            if (!fillQueue.isEmpty()) {
                fillNext();
                return;
            }
            // Fill done -- this was the last step of the one-shot Step+FillHole run, disable now
            // instead of falling through into picking a brand new target.
            pendingFillHole = null;
            filledPositions.clear();
            setToggled(false);
            return;
        }

        // Only guards PICKING a NEW target during a long unrelated fall (knockback/lava dive/etc,
        // don't impulsively snap to whatever's below) -- must NOT also block steering toward a
        // target already locked in. Blanket-returning here killed moveTowards() too, so jumping off
        // a ledge straight at a hole more than 5 blocks down (a completely normal, intentional way
        // to reach one) got zero horizontal correction the entire way down and just missed it
        // (reported: "tụt xuống mà client không jump vào được").
        if (mc.player.fallDistance >= 5.0f && hole == null) return;

        // `hole` is the TARGET we're walking toward, not "whatever's nearest right now" -- the
        // nearest hole to your CURRENT position is trivially the one you're standing in (distance
        // 0), so re-picking it every tick made `centered` true instantly without ever moving.
        // HoleSnap is now always a ONE-SHOT: pick a target once, walk to it (or just re-center if
        // you're already exactly where you need to be), then disable itself. Whether you're
        // already standing in a valid hole when it's toggled on decides WHAT that target is:
        //   - already in one, Step off  -> target = that SAME hole (just nudge to its exact center)
        //   - already in one, Step on   -> target = a DIFFERENT hole (current one excluded,
        //                                  1x1x1 singles preferred), so Step actually moves you
        //   - not in one                -> target = nearest valid hole in range (unchanged)
        if (hole == null && !pickTarget()) return;

        // Enemy filled the hole we're currently walking toward mid-approach -- without this,
        // moveTowards() below just keeps aiming at now-solid ground forever (the "stuck, không vào
        // được" report). Check every tick: if the target's no longer actually a hole, drop it and
        // immediately re-pick THIS SAME tick instead of waiting out a jump-timeout against a wall
        // that isn't even a hole anymore. Worst case (nothing else reachable) pickTarget's own
        // holes.isEmpty() check disables the module, matching "không có hole thì tự động disable".
        if (hole != null && !isHoleStillValid(hole)) {
            unreachableHoles.add(hole);
            hole = null;
            startingHole = null;
            jumpAttempts = 0;
            if (!pickTarget()) return;
        }

        // Epsilon, not exact double equality -- moveTowards' own closing-the-gap clamp lands you
        // WITHIN a hair of the target (sub-pixel collision-resolution rounding), never bit-exact on
        // it, and a player who simply walked into the hole themselves before toggling HoleSnap on
        // is basically never sitting on the literal block+0.5 double either. Exact equality meant
        // "centered" could go a full tick (or forever) without ever firing -- the reported "stands
        // still until I press a movement key myself" (any key nudges X/Z off the stale cached value
        // enough to re-trigger moveTowards, which then DOES converge and immediately reads as
        // centered next pass).
        // A raw Y-drift threshold ("player somehow got too high") doesn't actually reflect whether
        // the target is reachable -- the real question is whether a real block is physically in the
        // way. isHoleReachable walks the actual column between player and hole: if player is at/
        // below the hole, an obstruction taller than one jump can clear (a real StepModule silently
        // auto-climbing a whole staircase with horizontalCollision never once reading true, "kẹt
        // luôn") means it's not reachable; if player ended up above the hole, any solid block
        // sitting between them and the hole blocks a straight drop-in the same way.
        if (!isHoleReachable(hole)) {
            unreachableHoles.add(hole);
            hole = null;
            startingHole = null;
            jumpAttempts = 0;

            // Same circuit breaker the jump-timeout path uses -- a real geometric rejection like
            // this is exactly the kind of "tried, genuinely can't reach it" failure that should
            // count toward giving up entirely, not just toward excluding this one hole.
            if (++giveUpCount >= MAX_CONSECUTIVE_GIVE_UPS) {
                EUClient.CHAT_MANAGER.tagged("Gave up reaching a hole " + giveUpCount + " times in a row, disabling.", getName());
                setToggled(false);
                return;
            }

            if (!pickTarget()) return;
        }

        boolean centered = Math.abs(mc.player.getX() - hole.getCenter().x) < 0.03
                && Math.abs(mc.player.getY() - hole.minY) < 0.05
                && Math.abs(mc.player.getZ() - hole.getCenter().z) < 0.03;

        if (!centered) {
            // Without Step, there's no jump logic at all -- moveTowards() below is a straight-line
            // X/Z aim at the hole regardless of terrain, so walking at the SAME Y as an obstructing
            // wall just pins the player against it forever. hole.minY being below the player's OWN
            // Y doesn't mean "walk down to reach it" -- getSingleHole/etc. only ever return a
            // STANDABLE air cell (not an enclosed pit), so hole.minY < player Y just means it's a
            // ledge one step down, reached the SAME way as any 1-block obstruction: hop up onto the
            // wall in front, then walk forward off its far edge and fall in.
            //
            // Real bug in BOTH earlier attempts: mc.player.setDeltaMovement(...) doesn't actually
            // produce a jump here. ClientPlayerEntityMixin's move() only applies event.getMovement()
            // (via super.move()) when event.isCancelled() -- HoleSnap never cancels the event on the
            // jump tick, so vanilla's OWN move() runs with its OWN already-computed movement vector
            // (fixed before this handler even ran, from THIS tick's aiStep()/travel()), completely
            // ignoring the deltaMovement we just poked. Nothing physically moved -- verified against
            // SpeedModule's Grim autoJump (the ONE proven-working auto-jump in this codebase),
            // which never touches deltaMovement.y for its jump either: it presses the REAL vanilla
            // jump key (mc.options.keyJump) and lets vanilla's own jumpFromGround()/aiStep() do it
            // through the normal input pipeline. Same mechanism here.
            // Explicit request: with HoleSnap's own Step setting on, don't press the real jump key
            // at all -- Step's hole-to-hole hopping already gets you across ledges its own way, a
            // manual jump on top just fights it. Still counted as an "attempt" below though (just
            // without the key press) -- a wall Step can't clear needs the SAME give-up handling.
            if (mc.player.horizontalCollision && mc.player.onGround() && !jumpPressed) {
                if (!step.getValue()) mc.options.keyJump.setDown(true);
                jumpPressed = true;
                jumpWaitTicks = 0;
                jumpAttempts++;

                if (jumpAttempts > 5) {
                    mc.options.keyJump.setDown(false);
                    jumpPressed = false;
                    jumpAttempts = 0;
                    unreachableHoles.add(hole);
                    hole = null;
                    startingHole = null;

                    if (++giveUpCount >= MAX_CONSECUTIVE_GIVE_UPS) {
                        EUClient.CHAT_MANAGER.tagged("Gave up reaching a hole " + giveUpCount + " times in a row, disabling.", getName());
                        setToggled(false);
                    }
                    return;
                }
            }
            if (jumpPressed) {
                jumpWaitTicks++;
                // A wall taller than one block (or the player's hitbox otherwise wedged, e.g.
                // pinned under a ceiling) means the jump can NEVER clear it -- onGround() then
                // never once flips false, the only signal this used to release on. jumpPressed got
                // stuck true forever, which blocks the `!jumpPressed` guard above from ever firing
                // again, which in turn means jumpAttempts never reaches its give-up threshold --
                // the reported "mãi không chịu tắt, chỉ khi mở GUI mới dừng" (a Screen forcibly
                // releasing held keys was the only thing that ever broke the deadlock). Time out
                // the attempt after half a second even if onGround() never budges, so the state
                // machine can never get permanently wedged waiting on a transition that may not
                // exist for this geometry.
                if (!mc.player.onGround() || jumpWaitTicks > 10) {
                    mc.options.keyJump.setDown(false);
                    jumpPressed = false;
                    jumpWaitTicks = 0;
                }
            }

            MovementUtils.moveTowards(event, hole.getCenter(), MovementUtils.getPotionSpeed(MovementUtils.DEFAULT_SPEED));
            return;
        }

        // Made real progress toward this target (centered, or at least airborne clearing the
        // obstruction) -- the attempt counter only tracks CONSECUTIVE failures against the SAME
        // hole while stuck at its base, not overall jump usage.
        jumpAttempts = 0;
        giveUpCount = 0;

        // Landed dead-center this exact tick -- moveTowards() (above) only ever ran while NOT
        // centered, so nothing has zeroed this tick's movement yet. Reflex-holding a movement key
        // together with jump (common: jump into the hole, W still held from the approach) let real
        // input slide the player back out the instant the module disables and stops overriding
        // movement, landing-then-immediately-sliding-out in the same motion. Cancel this tick's
        // movement too, same as moveTowards does, so the snap actually holds.
        event.setMovement(new net.minecraft.world.phys.Vec3(0, event.getMovement().y, 0));
        ((eu.client.mixins.accessors.Vec3dAccessor) mc.player.getDeltaMovement()).setX(0);
        ((eu.client.mixins.accessors.Vec3dAccessor) mc.player.getDeltaMovement()).setZ(0);
        event.setCancelled(true);

        if (EUClient.MODULE_MANAGER.getModule(StepModule.class).isToggled()) EUClient.MODULE_MANAGER.getModule(StepModule.class).setToggled(false);
        if (EUClient.MODULE_MANAGER.getModule(SpeedModule.class).isToggled()) EUClient.MODULE_MANAGER.getModule(SpeedModule.class).setToggled(false);

        // Reached the target. FillHole backfills the hole we started in (only meaningful when
        // Step actually moved us away from one) -- that runs over several more ticks, so hold off
        // disabling until the pendingFillHole block above finishes draining the queue. Otherwise
        // (no fill needed) we're done right now.
        if (fillHole.getValue() && step.getValue() && startingHole != null) {
            pendingFillHole = startingHole.box();
            fillWaitTicks = 0;
            queueFillPositions(startingHole.box());
            hole = null;
            startingHole = null;
            return;
        }

        setToggled(false);
        hole = null;
        startingHole = null;
    }

    // Shared by both the initial "hole == null" pick and the mid-approach "target got filled,
    // re-pick now" recovery -- factored out so the exclusion rules (lastUsedHole, unreachableHoles)
    // only live in one place. Returns false (and disables the module) when nothing valid is left.
    private boolean pickTarget() {
        // A hole marked unreachable from wherever the player was standing THEN doesn't necessarily
        // stay unreachable -- HoleSnap keeps moving (walking partway toward whatever it tries next)
        // between rejections, and a wall that blocked line-of-sight-ish reachability from one spot
        // can easily not block it anymore from another. Self-heal the blacklist against the
        // player's CURRENT position every pick instead of excluding by identity forever.
        unreachableHoles.removeIf(this::isHoleReachable);

        HoleUtils.Hole detected = currentHole();

        // Still geometrically read as "in" the hole we just used (mid-jump/airborne, not actually
        // out yet -- e.g. mashing Space and the HoleSnap bind together) doesn't count as "already
        // in a hole" for target-selection purposes here, or the Step-off branch's "just recenter
        // in current" shortcut snaps you straight back down into the hole you were trying to leave.
        HoleUtils.Hole starting = (detected != null && lastUsedHole != null && detected.box().equals(lastUsedHole)) ? null : detected;

        List<HoleUtils.Hole> holes;
        if (starting != null && !step.getValue()) {
            holes = List.of(starting);
        } else if (starting != null) {
            holes = prioritizeSingle(getHoles().stream().filter(h -> !h.box().equals(starting.box())).toList());
        } else {
            holes = getHoles();
        }

        // Hard-exclude the last hole actually used, UNLESS that's literally the only candidate
        // left (fall back to it rather than doing nothing).
        if (lastUsedHole != null) {
            List<HoleUtils.Hole> filtered = holes.stream().filter(h -> !h.box().equals(lastUsedHole)).toList();
            if (!filtered.isEmpty()) holes = filtered;
        }

        // Same for targets that gave up (jump-timeout, geometrically unreachable, OR just-got-
        // filled) -- UNLIKE lastUsedHole, never fall back to re-picking one: it's proven unreachable/
        // gone (as of the self-heal check above), so falling back here would re-pick the exact same
        // dead hole every time, forever.
        if (!unreachableHoles.isEmpty()) {
            holes = holes.stream().filter(h -> !unreachableHoles.contains(h.box())).toList();
        }

        if (holes.isEmpty()) {
            // Nothing valid to snap to -- don't sit toggled-on doing nothing forever.
            setToggled(false);
            return false;
        }

        startingHole = starting;
        hole = holes.get(0).box();
        if (!hole.equals(lastUsedHole)) lastUsedHole = hole;
        return true;
    }

    // Re-checks whether a previously-picked target AABB is still actually a hole -- an enemy
    // filling it mid-approach doesn't change the box's coordinates, only whether HoleUtils still
    // detects a matching hole there.
    private boolean isHoleStillValid(AABB box) {
        BlockPos origin = BlockPos.containing(box.minX + 0.5, box.minY, box.minZ + 0.5);

        HoleUtils.Hole single = HoleUtils.getSingleHole(origin, 1);
        if (single != null && single.box().equals(box)) return true;

        if (doubleHoles.getValue()) {
            HoleUtils.Hole doubleHole = HoleUtils.getDoubleHole(origin, 1);
            if (doubleHole != null && doubleHole.box().equals(box)) return true;
        }

        if (quadHoles.getValue()) {
            HoleUtils.Hole quadHole = HoleUtils.getQuadHole(origin, 1);
            if (quadHole != null && quadHole.box().equals(box)) return true;
        }

        return false;
    }

    // Walks the actual block column between the player and a target hole instead of a raw Y-drift
    // guess.
    //   - Player at/below the hole: scan straight up the hole's own opening. A wall no taller than
    //     one block above the player's own feet is exactly what the existing obstruction jump
    //     already clears (see the horizontalCollision/jumpPressed block below) -- only a block
    //     ABOVE that reach genuinely blocks it.
    //   - Player above the hole: any solid block sitting between the hole's floor and the player
    //     blocks a straight drop-in, reachable or not otherwise.
    private boolean isHoleReachable(AABB box) {
        int holeX = (int) Math.floor(box.getCenter().x);
        int holeZ = (int) Math.floor(box.getCenter().z);
        int holeY = (int) box.minY;
        int playerY = mc.player.blockPosition().getY();

        // Two separate columns can each independently block reaching the hole, so both need
        // checking:
        //   - the WALL the player has to clear stands BETWEEN them and the hole, not necessarily
        //     above the hole's own opening at all (screenshot: "hole trước mặt... cách 1 bức tường
        //     cao 3 block", still picked as valid by a version that only ever checked straight up
        //     from the hole itself).
        //   - the CEILING directly above the hole's own opening can independently block it too
        //     (an overhang) even when the approach wall itself is perfectly climbable -- clearing
        //     the wall doesn't help if there's nowhere to land once you're up there.
        double dx = mc.player.getX() - box.getCenter().x;
        double dz = mc.player.getZ() - box.getCenter().z;
        int wallX = holeX + (Math.abs(dx) >= Math.abs(dz) ? (int) Math.signum(dx) : 0);
        int wallZ = holeZ + (Math.abs(dx) >= Math.abs(dz) ? 0 : (int) Math.signum(dz));

        return isColumnClear(wallX, wallZ, holeY, playerY) && isColumnClear(holeX, holeZ, holeY, playerY);
    }

    private boolean isColumnClear(int x, int z, int holeY, int playerY) {
        if (playerY <= holeY) {
            for (int y = holeY; y <= playerY + 3; y++) {
                if (y > playerY + 1 && !mc.level.getBlockState(new BlockPos(x, y, z)).canBeReplaced()) {
                    return false;
                }
            }
        } else {
            for (int y = holeY + 1; y < playerY; y++) {
                if (!mc.level.getBlockState(new BlockPos(x, y, z)).canBeReplaced()) {
                    return false;
                }
            }
        }

        return true;
    }

    // Is the player standing in a hole RIGHT NOW, regardless of range/Y filtering (those are for
    // picking a NEW target; this is "what am I already in"). Checks all 4 possible origin corners
    // since a double/quad hole's box can extend in either +X/+Z direction from its stored corner.
    private HoleUtils.Hole currentHole() {
        BlockPos pos = mc.player.blockPosition();
        double cx = pos.getX() + 0.5, cy = pos.getY() + 0.5, cz = pos.getZ() + 0.5;

        for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                BlockPos origin = pos.offset(dx, 0, dz);

                HoleUtils.Hole single = HoleUtils.getSingleHole(origin, 1);
                if (single != null && single.box().contains(cx, cy, cz)) return single;

                if (doubleHoles.getValue()) {
                    HoleUtils.Hole doubleHole = HoleUtils.getDoubleHole(origin, 1);
                    if (doubleHole != null && doubleHole.box().contains(cx, cy, cz)) return doubleHole;
                }

                if (quadHoles.getValue()) {
                    HoleUtils.Hole quadHole = HoleUtils.getQuadHole(origin, 1);
                    if (quadHole != null && quadHole.box().contains(cx, cy, cz)) return quadHole;
                }
            }
        }

        return null;
    }

    // 1x1x1 holes first, otherwise keep the existing nearest-first order (sorted() is stable).
    private List<HoleUtils.Hole> prioritizeSingle(List<HoleUtils.Hole> holes) {
        return holes.stream().sorted(Comparator.comparingInt(h -> h.type() == HoleUtils.HoleType.SINGLE ? 0 : 1)).toList();
    }

    private void queueFillPositions(AABB box) {
        int minX = (int) Math.floor(box.minX), maxX = (int) Math.ceil(box.maxX) - 1;
        int minZ = (int) Math.floor(box.minZ), maxZ = (int) Math.ceil(box.maxZ) - 1;
        int y = (int) box.minY;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                fillQueue.add(new BlockPos(x, y, z));
            }
        }
    }

    private void fillNext() {
        BlockPos pos = fillQueue.peek();
        if (pos == null) return;

        if (!WorldUtils.isPlaceable(pos)) {
            fillQueue.poll();
            return;
        }

        boolean altSlots = autoSwitch.getValue().equalsIgnoreCase("AltSwap") || autoSwitch.getValue().equalsIgnoreCase("AltPickup");
        int slot = InventoryUtils.findHardestBlock(0, altSlots ? 35 : 8);
        if (slot == -1) {
            fillQueue.clear();
            return;
        }

        int previousSlot = mc.player.getInventory().getSelectedSlot();

        // Same as Surround's placedPositions -- an interior cell of a double/quad hole can have
        // every side still air (nothing placed yet), leaving no pre-existing solid face to click.
        // filledPositions lets a cell reference one placed EARLIER in this same run as a valid
        // face too, not just genuinely pre-existing world blocks.
        Direction direction = WorldUtils.getDirection(pos, filledPositions, strictDirection.getValue());
        if (direction == null) {
            // No solid face anywhere around it yet (not even from earlier cells this run) --
            // same fallback Surround uses: place a support block directly below first, then
            // retry THIS cell's direction off of that.
            BlockPos supportPosition = pos.offset(0, -1, 0);
            if (!WorldUtils.isPlaceable(supportPosition)) {
                fillQueue.poll();
                return;
            }

            Direction supportDirection = WorldUtils.getDirection(supportPosition, filledPositions, strictDirection.getValue());
            if (supportDirection == null) {
                fillQueue.poll();
                return;
            }

            InventoryUtils.switchSlot(autoSwitch.getValue(), slot, previousSlot);
            boolean supportPlaced = WorldUtils.placeBlock(supportPosition, supportDirection, InteractionHand.MAIN_HAND, rotate.getValue(), crystalDestruction.getValue(), render.getValue());
            InventoryUtils.switchBack(autoSwitch.getValue(), slot, previousSlot);

            if (!supportPlaced) return;
            filledPositions.add(supportPosition);

            direction = WorldUtils.getDirection(pos, filledPositions, strictDirection.getValue());
            if (direction == null) return;
        }

        InventoryUtils.switchSlot(autoSwitch.getValue(), slot, previousSlot);
        boolean placed = WorldUtils.placeBlock(pos, direction, InteractionHand.MAIN_HAND, rotate.getValue(), crystalDestruction.getValue(), render.getValue());
        InventoryUtils.switchBack(autoSwitch.getValue(), slot, previousSlot);

        // false = a crystal was sitting there and only got attacked this tick (see
        // WorldUtils.placeBlock) -- leave this position at the front of the queue and retry it
        // next tick instead of dropping it.
        if (placed) {
            filledPositions.add(pos);
            fillQueue.poll();
        }
    }

    private List<HoleUtils.Hole> getHoles() {
        List<HoleUtils.Hole> holes = new ArrayList<>();

        for (int i = 0; i < EUClient.WORLD_MANAGER.getRadius(range.getValue().doubleValue()); i++) {
            BlockPos position = mc.player.blockPosition().offset(EUClient.WORLD_MANAGER.getOffset(i));

            // Was excluding same-level (Y == player's Y) candidates without Step, on the assumption
            // plain HoleSnap could only ever fall straight down into a lower hole -- no longer true
            // now that the obstruction jump is a REAL vanilla jump (mc.options.keyJump), which can
            // actually hop up INTO a same-level hole across a 1-block lip too. Only still exclude
            // strictly-above candidates (nothing here climbs multiple levels) -- a +1 relaxation was
            // tried and reverted per request; the real fix for "reachable hole one step up got
            // filtered out" is isHoleReachable's own per-candidate column check downstream, not
            // loosening this coarse cutoff.
            if (position.getY() > mc.player.getY()) continue;

            HoleUtils.Hole singleHole = HoleUtils.getSingleHole(position, 1);
            if (singleHole != null) {
                holes.add(singleHole);
                continue;
            }

            if (doubleHoles.getValue()) {
                HoleUtils.Hole doubleHole = HoleUtils.getDoubleHole(position, 1);
                if (doubleHole != null) {
                    holes.add(doubleHole);
                    continue;
                }
            }

            if (quadHoles.getValue()) {
                HoleUtils.Hole quadHole = HoleUtils.getQuadHole(position, 1);
                if (quadHole != null) {
                    holes.add(quadHole);
                }
            }
        }

        return holes.stream().sorted(Comparator.comparing(h -> mc.player.distanceToSqr(h.box().getCenter().x, h.box().getCenter().y, h.box().getCenter().z))).toList();
    }
}
