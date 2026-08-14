package eu.client.modules.impl.combat;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PacketReceiveEvent;
import eu.client.events.impl.PlayerJumpEvent;
import eu.client.events.impl.PlayerMineEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.modules.impl.movement.HitboxDesyncModule;
import eu.client.modules.impl.movement.SpeedModule;
import eu.client.modules.impl.movement.StepModule;
import eu.client.modules.impl.movement.TickShiftModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.HoleUtils;
import eu.client.utils.minecraft.InventoryUtils;
import eu.client.utils.minecraft.PositionUtils;
import eu.client.utils.minecraft.WorldUtils;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RegisterModule(name = "Surround", description = "Automatically places blocks at your feet to prevent crystal damage.", category = Module.Category.COMBAT)
public class SurroundModule extends Module {
    public ModeSetting autoSwitch = new ModeSetting("Switch", "The mode that will be used for automatically switching to necessary items.", "Silent", InventoryUtils.SWITCH_MODES);
    public ModeSetting timing = new ModeSetting("Timing", "The timing that will be used in replacing broken surround blocks.", "Sequential", new String[]{"Vanilla", "Sequential"});
    public NumberSetting limit = new NumberSetting("Limit", "The maximum number of blocks that can be placed each group.", 4, 1, 20);
    public NumberSetting delay = new NumberSetting("Delay", "The delay in ticks between each group of placements.", 0, 0, 20);
    public NumberSetting range = new NumberSetting("Range", "The maximum range at which the blocks will be placed at.", 5.0, 0.0, 12.0);
    public BooleanSetting await = new BooleanSetting("Await", "Waits for blocks to be registered by the client before placing on them.", false);
    public BooleanSetting rotate = new BooleanSetting("Rotate", "Whether or not you should rotate when you place blocks.", true);
    public BooleanSetting strictDirection = new BooleanSetting("StrictDirection", "Only places using directions that face you.", false);
    public BooleanSetting crystalDestruction = new BooleanSetting("CrystalDestruction", "Destroys any crystals that interfere with block placement.", true);
    public BooleanSetting center = new BooleanSetting("Center", "Puts you in the center of the block when you surround.", false);
    public BooleanSetting floor = new BooleanSetting("Floor", "Places blocks under your feet as well.", true);
    // Ported from homovore's SurroundModule (MineProtect/AvoidHelpingOpponents), renamed per
    // request. Descriptions written for the end user, not the implementation detail.
    public BooleanSetting blocker = new BooleanSetting("Blocker", "Pre-places extra blocks where an enemy is digging toward you.", true);
    public BooleanSetting advoidHelp = new BooleanSetting("AdvoidHelp", "Skips placements that would also wall in a nearby enemy.", true);
    public NumberSetting advoidHelpHealth = new NumberSetting("AdvoidHelpHealth", "Below this health, place every block regardless -- ignores AdvoidHelp entirely.", new BooleanSetting.Visibility(advoidHelp, true), 10.0f, 0.0f, 20.0f);
    public BooleanSetting whileEating = new BooleanSetting("WhileEating", "Places blocks normally while eating.", true);
    // Ported from Sydney-Legacy's Surround -- both were declared-but-dropped during the port
    // (ClientboundBlockUpdatePacket sat imported and unused; there was no PlayerPositionLook
    // handling at all).
    public BooleanSetting chorusCenter = new BooleanSetting("CenterOnTP", "Centers you if you have just teleported to surround against crystals easier.", true);
    public BooleanSetting predict = new BooleanSetting("Predict", "Replaces a surround block instantly the moment we see the server's own break packet for it, instead of waiting for the next normal placement cycle.", true);

    public BooleanSetting selfDisable = new BooleanSetting("SelfDisable", "Toggles off the module once it is finished with placing.", false);
    public BooleanSetting jumpDisable = new BooleanSetting("JumpDisable", "Toggles off the module whenever your Y level changes.", true);

    public BooleanSetting stepToggle = new BooleanSetting("StepToggle", "Toggles the step module when you surround.", false);
    public BooleanSetting speedToggle = new BooleanSetting("SpeedToggle", "Toggles the speed module when you surround.", false);
    // Speed and TickShift are commonly bound to the same key (TickShift's own boost only applies
    // while Speed's Strafe/StrafeStrict mode is actively driving the timer, see SpeedModule's
    // isDrivingTimer() doc) -- disabling only one of the pair here desyncs their toggle state
    // relative to that shared bind (one press now turns them back on/off out of step with each
    // other) until the user notices and manually re-syncs them.
    public BooleanSetting tickShiftToggle = new BooleanSetting("TickShiftToggle", "Toggles the TickShift module when you surround.", false);

    public BooleanSetting render = new BooleanSetting("Render", "Whether or not to render the place position.", true);

    private Set<BlockPos> targetPositions = new HashSet<>();
    private BlockPos lastPosition = null;

    private int ticks = 0;
    private int blocksPlaced = 0;
    // Set when the server acks a chorus-fruit teleport while chorusCenter is on -- the actual
    // re-center happens on the NEXT player-update tick (the teleport packet itself only carries
    // the new position; mc.player's own position needs a tick to actually reflect it here).
    private boolean awaitChorusCenter = false;

    private long lastCrystalAttackTime = 0;

    // Blocker: actorID -> the position they're currently mining, fed by PlayerMineEvent (the same
    // event BreakHighlightModule uses) rather than depending on that module being toggled.
    private final Map<Integer, BlockPos> activeMines = new HashMap<>();
    private static final Direction[] HORIZONTALS = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    // No PingBypass skip anywhere in this module (removed) -- matches earthhack's real Surround,
    // which has no proxy-side port at all; its own description even warns it's "not recommended
    // when using this on a PingBypass proxy" rather than offering a dedicated proxy mode. Runs as
    // plain client-side dumb-pipe unconditionally, same as without PingBypass.
    @Override
    public void onEnable() {
        if (mc.player == null || mc.level == null) return;
        lastPosition = PositionUtils.getFlooredPosition(mc.player);
        awaitChorusCenter = false;

        if(stepToggle.getValue() && EUClient.MODULE_MANAGER.getModule(StepModule.class).isToggled()) EUClient.MODULE_MANAGER.getModule(StepModule.class).setToggled(false);
        if(speedToggle.getValue() && EUClient.MODULE_MANAGER.getModule(SpeedModule.class).isToggled()) EUClient.MODULE_MANAGER.getModule(SpeedModule.class).setToggled(false);
        if(tickShiftToggle.getValue() && EUClient.MODULE_MANAGER.getModule(TickShiftModule.class).isToggled()) EUClient.MODULE_MANAGER.getModule(TickShiftModule.class).setToggled(false);
        if(center.getValue()) mc.player.setPos(lastPosition.getX() + 0.5, lastPosition.getY(), lastPosition.getZ() + 0.5);
    }

    @SubscribeEvent
    public void onPlayerMine(PlayerMineEvent event) {
        if (!blocker.getValue() || mc.player == null) return;
        if (event.getActorID() == mc.player.getId()) return;
        activeMines.put(event.getActorID(), event.getPosition());
    }

    @SubscribeEvent
    public void onPlayerJump(PlayerJumpEvent event) {
        if (jumpDisable.getValue()) {
            setToggled(false);
        }
    }

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (mc.player == null || mc.level == null) return;

        if (awaitChorusCenter) {
            BlockPos currentPos = PositionUtils.getFlooredPosition(mc.player);
            mc.player.setPos(currentPos.getX() + 0.5, mc.player.getY(), currentPos.getZ() + 0.5);
            lastPosition = currentPos;
            awaitChorusCenter = false;
        }

        if (jumpDisable.getValue() && (mc.player.fallDistance > 2.0f || ((EUClient.MODULE_MANAGER.getModule(StepModule.class).isToggled() || EUClient.MODULE_MANAGER.getModule(SpeedModule.class).isToggled()) && (lastPosition == null || lastPosition.getY() != PositionUtils.getFlooredPosition(mc.player).getY())))) {
            setToggled(false);
            return;
        }

        if (!whileEating.getValue() && mc.player.isUsingItem()) return;
        blocksPlaced = 0;

        if (ticks < delay.getValue().intValue()) {
            ticks++;
            return;
        }

        if (autoSwitch.getValue().equalsIgnoreCase("None") && !(mc.player.getMainHandItem().getItem() instanceof BlockItem)) {
            // Ported from the deleted ItemDisable setting's own notification -- always on now
            // (hardcoded, matches this module's other now-hardcoded "always on" behaviors) instead
            // of gated behind a toggle.
            EUClient.CHAT_MANAGER.tagged("You are currently not holding any blocks.", getName());
            setToggled(false);
            targetPositions.clear();
            return;
        }

        int slot = InventoryUtils.findHardestBlock(0, 8);
        int previousSlot = mc.player.getInventory().getSelectedSlot();

        if (slot == -1) {
            EUClient.CHAT_MANAGER.tagged("No blocks could be found in your hotbar.", getName());
            setToggled(false);
            targetPositions.clear();
            return;
        }

        targetPositions = HoleUtils.getFeetPositions(mc.player, true, floor.getValue(), false);

        if (blocker.getValue()) applyBlocker();

        // Homovore's own crystal defense (findThreateningCrystal/breakCrystal) runs every tick
        // regardless of whether anything is actively being placed this cycle -- our own
        // onPacketReceive handler below only ever reacts ONCE, off the crystal's spawn packet, with
        // no retry if that single attack packet gets dropped/desynced/rejected (reach check racing
        // the rotation packet, anti-cheat, etc). That's the actual gap ("Timing=Sequential vẫn kém
        // ngang Vanilla" even after un-gating it from Timing) -- polling here every tick keeps
        // re-targeting the SAME still-alive threatening crystal until it's actually gone, the same
        // way the rest of homovore's module operates.
        attackThreateningCrystal();

        Set<BlockPos> helpBlockedPositions = advoidHelp.getValue() ? computeHelpBlocked() : Set.of();

        HitboxDesyncModule module = EUClient.MODULE_MANAGER.getModule(HitboxDesyncModule.class);
        List<BlockPos> positions = targetPositions.stream().filter(position -> mc.player.distanceToSqr(Vec3.atCenterOf(position)) <= Mth.square(range.getValue().doubleValue()))
                .filter(position -> WorldUtils.isPlaceable(position, module.isToggled() && !module.close.getValue()))
                .filter(position -> !helpBlockedPositions.contains(position))
                .toList();

        if (positions.isEmpty()) {
            if (selfDisable.getValue()) setToggled(false);
            return;
        }

        InventoryUtils.switchSlot(autoSwitch.getValue(), slot, previousSlot);

        List<BlockPos> placedPositions = new ArrayList<>();
        for (BlockPos position : positions) {
            if (blocksPlaced >= limit.getValue().intValue()) break;

            Direction direction = WorldUtils.getDirection(position, placedPositions, strictDirection.getValue());
            if (direction == null) {
                BlockPos supportPosition = position.offset(0, -1, 0);
                if (!WorldUtils.isPlaceable(supportPosition)) continue;

                Direction supportDirection = WorldUtils.getDirection(supportPosition, placedPositions, strictDirection.getValue());
                if (supportDirection == null) continue;

                // Only count/reserve the slot as used when a block ACTUALLY placed -- a false
                // return means a crystal was in the way and only got attacked this tick (see
                // WorldUtils.placeBlock), and burning the per-tick limit on a no-op starved the
                // other, unblocked positions of their own attempt.
                if (!WorldUtils.placeBlock(supportPosition, supportDirection, InteractionHand.MAIN_HAND, rotate.getValue(), crystalDestruction.getValue(), render.getValue()))
                    continue;
                placedPositions.add(supportPosition);
                blocksPlaced++;

                if (blocksPlaced >= limit.getValue().intValue()) break;
                if (await.getValue()) continue;

                direction = WorldUtils.getDirection(position, placedPositions, strictDirection.getValue());
                if (direction == null) continue;
            }

            if (!WorldUtils.placeBlock(position, direction, InteractionHand.MAIN_HAND, rotate.getValue(), crystalDestruction.getValue(), render.getValue()))
                continue;
            placedPositions.add(position);
            blocksPlaced++;
        }

        InventoryUtils.switchBack(autoSwitch.getValue(), slot, previousSlot);

        ticks = 0;
    }

    @SubscribeEvent
    public void onPacketReceive(PacketReceiveEvent event) {
        if (mc.player == null || mc.level == null) return;

        // Unconditional (not gated on Sequential timing) -- matches Sydney's own ordering. A
        // chorus-fruit teleport lands the player at an arbitrary sub-block offset; centering onto
        // the floored block makes the very next Surround cycle place cleanly against all 4 sides
        // instead of half-hanging over an edge.
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket && chorusCenter.getValue() && mc.player.isUsingItem() && mc.player.getUseItem().getItem() == Items.CHORUS_FRUIT) {
            awaitChorusCenter = true;
        }

        // Crystal defense: independent of Timing -- that setting only governs how BROKEN surround
        // blocks get replaced (see its own description), it has nothing to do with keeping a
        // crystal from landing on a surround cell in the first place. Used to sit nested under the
        // Sequential-only `return` below by accident, so Timing=Vanilla got zero proactive reaction
        // to a crystal spawning exactly on a surround position -- homovore's own crystal defense
        // (SurroundModule.findThreateningCrystal/breakCrystal) has no such dependency either, it
        // just always runs. Reacting off the spawn packet itself beats homovore's tick-polled
        // approach anyway: fires the instant the crystal exists server-side instead of waiting for
        // this tick's placement attempt (or next tick) to stumble onto it.
        if (crystalDestruction.getValue() && event.getPacket() instanceof ClientboundAddEntityPacket packet && packet.getType().equals(EntityType.END_CRYSTAL)) {
            // Only for the bounding-box/position math -- a freshly `new`'d client-local entity gets
            // its id from Entity's own local static counter, NOT the server's real id (that's
            // packet.getId(), used below for the actual attack). Sending an attack against
            // crystal.getId() targeted a bogus id the server has no entity for -- a complete no-op
            // that silently ate every reactive attack this branch ever sent, regardless of ping.
            EndCrystal crystal = new EndCrystal(mc.level, packet.getX(), packet.getY(), packet.getZ());

            for (BlockPos position : targetPositions) {
                if (new AABB(position).intersects(crystal.getBoundingBox()) && targetPositions.contains(position)) {

                    if (blocksPlaced > limit.getValue().intValue()) return;
                    if (!whileEating.getValue() && mc.player.isUsingItem()) return;

                    int slot = InventoryUtils.findHardestBlock(0, autoSwitch.getValue().equalsIgnoreCase("AltSwap") || autoSwitch.getValue().equalsIgnoreCase("AltPickup") ? 35 : 8);
                    int previousSlot = mc.player.getInventory().getSelectedSlot();

                    if (slot == -1) return;

                    Direction direction = WorldUtils.getDirection(position, strictDirection.getValue());
                    if (direction == null) return;

                    // Runs on the netty IO thread; an Alt* switch to a slot outside the hotbar can't
                    // be done safely from here (see InventoryUtils.switchSlot) -- placing with the
                    // wrong item in hand is worse than skipping this reactive attempt, the tick-
                    // polled attackThreateningCrystal()/next placement cycle still covers it.
                    if (!InventoryUtils.switchSlot(autoSwitch.getValue(), slot, previousSlot)) return;

                    WorldUtils.placeBlock(position, direction, InteractionHand.MAIN_HAND, () -> {
                        mc.player.connection.send(new ServerboundAttackPacket(packet.getId()));
                        mc.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                    }, rotate.getValue(), false, render.getValue());
                    blocksPlaced++;

                    InventoryUtils.switchBack(autoSwitch.getValue(), slot, previousSlot);

                    break;
                }
            }
        }

        if (!timing.getValue().equalsIgnoreCase("Sequential"))
            return;

        if (predict.getValue() && event.getPacket() instanceof ClientboundBlockUpdatePacket packet && packet.getBlockState().isAir() && targetPositions.contains(packet.getPos())) {
            if (blocksPlaced > limit.getValue().intValue()) return;
            if (!whileEating.getValue() && mc.player.isUsingItem()) return;

            int slot = InventoryUtils.findHardestBlock(0, autoSwitch.getValue().equalsIgnoreCase("AltSwap") || autoSwitch.getValue().equalsIgnoreCase("AltPickup") ? 35 : 8);
            int previousSlot = mc.player.getInventory().getSelectedSlot();
            if (slot == -1) return;

            Direction direction = WorldUtils.getDirection(packet.getPos(), strictDirection.getValue());
            if (direction == null) return;

            if (!InventoryUtils.switchSlot(autoSwitch.getValue(), slot, previousSlot)) return;
            WorldUtils.placeBlock(packet.getPos(), direction, InteractionHand.MAIN_HAND, rotate.getValue(), crystalDestruction.getValue(), render.getValue());
            blocksPlaced++;
            InventoryUtils.switchBack(autoSwitch.getValue(), slot, previousSlot);
        }
    }

    // Ported from homovore's SurroundModule.findThreateningCrystal/threatensSurround/breakCrystal --
    // rate-limited to 50ms (matches homovore's own onTick(priority=1) throttle) so it doesn't spam
    // an attack packet every single tick once a crystal's already dead-but-not-yet-confirmed.
    private void attackThreateningCrystal() {
        if (!crystalDestruction.getValue()) return;

        long now = System.currentTimeMillis();
        if (now - lastCrystalAttackTime < 50L) return;

        Vec3 eye = mc.player.getEyePosition();
        EndCrystal best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (net.minecraft.world.entity.Entity entity : mc.level.getEntities((net.minecraft.world.entity.Entity) null, new AABB(mc.player.blockPosition()).inflate(8.0), e -> e instanceof EndCrystal)) {
            EndCrystal crystal = (EndCrystal) entity;
            if (!threatensSurround(crystal.blockPosition())) continue;

            double distSq = crystal.distanceToSqr(eye);
            if (distSq > 36.0 || distSq >= bestDistSq) continue;
            bestDistSq = distSq;
            best = crystal;
        }

        if (best == null) return;

        lastCrystalAttackTime = now;
        // No packetRotate -- see WorldUtils.destroyCrystals's own doc: attack reach is pure
        // distance, and faking a look here only risks tripping rotation-based anticheat right next
        // to an attack packet for zero gain. Matches Shoreline's own crystal-defense attack.
        mc.player.connection.send(new ServerboundAttackPacket(best.getId()));
        mc.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
    }

    // A crystal cell counts as threatening if it sits ON or directly beside (within 1 horizontally,
    // 2 vertically -- covers both the ring itself and the head-height space above it) any currently
    // wanted surround position, matching homovore's own nearSurroundCell radius.
    private boolean threatensSurround(BlockPos cell) {
        for (BlockPos pos : targetPositions) {
            if (Math.abs(pos.getX() - cell.getX()) <= 1 && Math.abs(pos.getZ() - cell.getZ()) <= 1 && Math.abs(pos.getY() - cell.getY()) <= 2) return true;
        }
        return false;
    }

    // Ported from homovore's SurroundModule.applyMineProtect/applyMineProtectBlock. When someone
    // near you is actively digging (PlayerMineEvent, above), pre-add the blocks around the spot
    // they're breaking through -- above/below it, plus the 2 diagonals and the 1 straight block
    // extending outward from your feet in that direction -- into targetPositions, so the very next
    // placement cycle already has a fresh wall ready the instant their hole opens instead of
    // reacting a whole cycle late.
    private void applyBlocker() {
        // Prune stale mines (the miner's gone, or their target block already broke/got replaced)
        // -- otherwise a mine that never resulted in a new PlayerMineEvent (player disconnected,
        // walked off, or actually broke through) would keep reinforcing a dead spot forever.
        activeMines.entrySet().removeIf(e -> mc.level.getEntity(e.getKey()) == null || mc.level.getBlockState(e.getValue()).canBeReplaced());

        for (BlockPos mined : activeMines.values()) {
            for (BlockPos feet : ownFootCells()) {
                if (mined.getY() != feet.getY()) continue;
                int dx = mined.getX() - feet.getX();
                int dz = mined.getZ() - feet.getZ();
                if (Math.abs(dx) + Math.abs(dz) != 1) continue;

                targetPositions.add(mined.above());
                targetPositions.add(mined.below());
                if (dx != 0) {
                    targetPositions.add(feet.offset(dx, 0, 1));
                    targetPositions.add(feet.offset(dx, 0, -1));
                    targetPositions.add(feet.offset(dx * 2, 0, 0));
                } else {
                    targetPositions.add(feet.offset(1, 0, dz));
                    targetPositions.add(feet.offset(-1, 0, dz));
                    targetPositions.add(feet.offset(0, 0, dz * 2));
                }
            }
        }
    }

    // Ported from homovore's SurroundModule.computeHelpBlocked/buildOpponentSurroundPoses. Builds
    // the same wall-ring every nearby enemy would want for THEIR OWN surround, then blocks our own
    // placement candidates that land in that ring unless they're also close to our own footprint --
    // stops you from finishing a wall segment that happens to also seal an enemy in while you're
    // fighting them at melee range. Skipped when your own health is critical (you need every
    // block, not a courtesy check).
    //
    // Homovore's own original ALSO skips this whenever the player isn't straddling 2+ foot cells
    // (minX==maxX && minZ==maxZ) -- but standing in a single 1x1 cell IS the normal case for
    // melee-range PVP (the exact scenario this feature exists for: fighting someone right next to
    // you), so that bypass silently disabled AdvoidHelp for the single most common case it's
    // actually meant to catch (reported: "bật advoid helping sao no con giup cuc duoc" while
    // standing normally next to a target, health well above the threshold). Dropped -- health is
    // the only remaining escape hatch.
    private Set<BlockPos> computeHelpBlocked() {
        Set<BlockPos> myFootCells = ownFootCells();
        if (mc.player.getHealth() < advoidHelpHealth.getValue().floatValue()) return Set.of();

        Set<BlockPos> opponentRing = new HashSet<>();
        for (Player player : mc.level.players()) {
            if (player == mc.player) continue;
            if (EUClient.FRIEND_MANAGER.contains(player.getName().getString())) continue;
            if (mc.player.distanceToSqr(player) > 25.0) continue;
            if (!mc.level.getBlockState(player.blockPosition()).canBeReplaced()) continue;

            for (BlockPos cell : ownFootCells(player)) {
                for (Direction dir : HORIZONTALS) {
                    BlockPos adjacent = cell.relative(dir);
                    if (ownFootCells(player).contains(adjacent)) continue;
                    opponentRing.add(adjacent);
                }
                opponentRing.add(cell.below());
                opponentRing.add(cell);
            }
        }

        if (opponentRing.isEmpty()) return Set.of();

        Set<BlockPos> blocked = new HashSet<>();
        for (BlockPos pos : opponentRing) {
            boolean nearMine = false;
            for (BlockPos cell : myFootCells) {
                if (pos.equals(cell) || pos.equals(cell.above()) || pos.equals(cell.below())
                        || pos.equals(cell.north()) || pos.equals(cell.south()) || pos.equals(cell.east()) || pos.equals(cell.west())) {
                    nearMine = true;
                    break;
                }
            }
            if (!nearMine) blocked.add(pos);
        }
        return blocked;
    }

    private Set<BlockPos> ownFootCells() {
        return ownFootCells(mc.player);
    }

    private Set<BlockPos> ownFootCells(Player player) {
        Set<BlockPos> cells = new HashSet<>();
        AABB box = player.getBoundingBox();
        int y = player.blockPosition().getY();
        for (int x = (int) Math.floor(box.minX); x < Math.ceil(box.maxX); x++) {
            for (int z = (int) Math.floor(box.minZ); z < Math.ceil(box.maxZ); z++) {
                cells.add(new BlockPos(x, y, z));
            }
        }
        return cells;
    }

    @SubscribeEvent
    public void onDisable() {
        lastPosition = null;
        awaitChorusCenter = false;
        targetPositions.clear();
        activeMines.clear();

        ticks = 0;
        blocksPlaced = 0;
    }

    @Override
    public String getMetaData() {
        return String.valueOf(targetPositions.size());
    }
}
