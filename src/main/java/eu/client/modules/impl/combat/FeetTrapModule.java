package eu.client.modules.impl.combat;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PacketReceiveEvent;
import eu.client.events.impl.PlayerJumpEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.HoleUtils;
import eu.client.utils.minecraft.InventoryUtils;
import eu.client.utils.minecraft.NetworkUtils;
import eu.client.utils.minecraft.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ported from Nami's FeetTrapFeature (nami-public-mc.1.21.11,
 * namidevelopment.kiriyaga.nami.impl.feature.combat.FeetTrapFeature + its TrapComponent).
 *
 * What it actually is: Nami has no separate "Surround" -- FeetTrap IS their surround. Its target
 * set is BlockUtils.getSurround(player, 0, extension), i.e. the horizontal ring around every floor
 * cell the hitbox occupies plus the block underneath, which is exactly what this project's
 * HoleUtils.getFeetPositions(player, extension, floor, false) already returns. The one thing Nami
 * has that SurroundModule does not is Corners: the four diagonals at feet level, which close the
 * gaps a crystal can be placed in when you are standing on a block edge.
 *
 * Full TrapComponent port (minus Simulate, which just swaps a real packet send for a client-only
 * gamemode call and has no reach-affecting behaviour worth carrying over): Attack (doBreak, ported
 * verbatim -- rotate + server-side reach raycast + attack packet on any crystal landing on a trap
 * cell), AntiBreak (pre-extends the trap around a spot an enemy is actively mining through, reusing
 * this project's own PlayerMineEvent -- same signal SurroundModule.applyBlocker already runs on),
 * AirPlace with the Grim offhand-swap trick (InteractionUtils.airPlace's grim branch: swap the
 * block to offhand, place from there, swap back -- so the placement packet's hand never matches the
 * hand your held-item last changed on, same anti-desync idea NoSlow's own offhand dance relies on),
 * and StrictDirection/MultiTask/Swing, all wired the same as Nami's own settings.
 */
@RegisterModule(name = "FeetTrap", description = "Places blocks around your feet to block crystal placements.", category = Module.Category.COMBAT)
public class FeetTrapModule extends Module {
    public ModeSetting autoSwitch = new ModeSetting("Switch", "The mode that will be used for automatically switching to necessary items.", "Silent", InventoryUtils.SWITCH_MODES);
    public NumberSetting limit = new NumberSetting("Limit", "The number of blocks that can be placed per tick.", 4, 1, 20);
    public NumberSetting delay = new NumberSetting("Delay", "The amount of ticks waited between each group of placements.", 0, 0, 20);
    public NumberSetting range = new NumberSetting("Range", "The maximum range at which the blocks will be placed at.", 5.0, 0.0, 12.0);
    public BooleanSetting extension = new BooleanSetting("Extension", "Extends the trap if there are entities obstructing block placement.", false);
    public BooleanSetting floor = new BooleanSetting("Floor", "Places blocks under your feet as well.", true);
    public BooleanSetting corners = new BooleanSetting("Corners", "Also covers the four diagonals at feet level.", false);
    public BooleanSetting jumpDisable = new BooleanSetting("JumpDisable", "Toggles off the module whenever you leave the ground.", false);
    public BooleanSetting rotate = new BooleanSetting("Rotate", "Sends a packet rotation whenever placing a block.", true);
    public BooleanSetting strictDirection = new BooleanSetting("StrictDirection", "Only places using directions that face you.", true);
    public BooleanSetting airPlace = new BooleanSetting("AirPlace", "Places blocks in the air using the block below as support, facing UP, when no direct neighbor is available.", false);
    public BooleanSetting grim = new BooleanSetting("Grim", "AirPlace only: places from the offhand via a swap-and-swapback trick instead of the mainhand.", new BooleanSetting.Visibility(airPlace, true), false);
    public BooleanSetting multiTask = new BooleanSetting("MultiTask", "Allows placing while an item is already in use (eating, blocking, drawing a bow).", false);
    public BooleanSetting swing = new BooleanSetting("Swing", "Sends a swing packet whenever placing a block.", true);
    public BooleanSetting antiBreak = new BooleanSetting("AntiBreak", "Pre-places extra blocks around a spot an enemy is actively digging through toward your trap.", false);
    public BooleanSetting selfDisable = new BooleanSetting("SelfDisable", "Toggles off the module once it is finished with placing.", false);
    public BooleanSetting render = new BooleanSetting("Render", "Whether or not to render the place position.", true);

    public BooleanSetting attack = new BooleanSetting("Attack", "Attacks any crystal that lands on one of your trap positions.", false);
    public BooleanSetting attackRotate = new BooleanSetting("AttackRotate", "Rotate", "Rotates toward the crystal before attacking it.", new BooleanSetting.Visibility(attack, true), true);
    public NumberSetting attackRange = new NumberSetting("AttackRange", "Range", "The maximum range at which crystals will be attacked.", new BooleanSetting.Visibility(attack, true), 3.0f, 1.0f, 6.0f);
    public NumberSetting attackAge = new NumberSetting("AttackAge", "Age", "The minimum age (in ticks) a crystal must reach before being attacked.", new BooleanSetting.Visibility(attack, true), 5, 0, 20);
    public BooleanSetting attackMultiTask = new BooleanSetting("AttackMultiTask", "Multitask", "Allows attacking while an item is already in use.", new BooleanSetting.Visibility(attack, true), true);
    public BooleanSetting attackSwing = new BooleanSetting("AttackSwing", "Swing", "Sends a swing packet whenever attacking a crystal.", new BooleanSetting.Visibility(attack, true), true);

    private int ticks = 0;
    private int targetCount = 0;
    private Set<BlockPos> targetPositions = new LinkedHashSet<>();

    // Ported from Nami's BreakPredictionService/PlayerBreakState verbatim (its own
    // "current"+"doubleMine" two-task model, own hardcoded netherite/efficiency-5 speed estimate,
    // own /30 divisor) rather than reusing this project's PlayerMineEvent/SurroundModule.applyBlocker
    // shape -- keyed by entity id instead of Nami's UUID (same identity, no extra lookup needed
    // since ClientboundBlockDestructionPacket already carries the id).
    private static class BreakTask {
        BlockPos pos;
        float targetSpeed;
        float progress;
        boolean active;

        void start(BlockPos pos, float speed) {
            this.pos = pos;
            this.targetSpeed = speed;
            this.progress = 0;
            this.active = true;
        }

        void copyFrom(BreakTask other) {
            this.pos = other.pos;
            this.targetSpeed = other.targetSpeed;
            this.progress = other.progress;
            this.active = other.active;
        }

        void reset() {
            this.active = false;
            this.progress = 0;
        }
    }

    private class BreakState {
        final int entityId;
        final BreakTask current = new BreakTask();
        final BreakTask doubleMine = new BreakTask();

        BreakState(int entityId) {
            this.entityId = entityId;
        }

        void startBreak(BlockPos pos, float speed) {
            if (current.active && !current.pos.equals(pos)) {
                if (!doubleMine.active) {
                    doubleMine.copyFrom(current);
                    doubleMine.targetSpeed = 1.0f;
                }
                current.reset();
            }
            if (!current.active || !current.pos.equals(pos)) current.start(pos, speed);
        }

        void onTick() {
            tickTask(current);
            tickTask(doubleMine);
        }

        private void tickTask(BreakTask task) {
            if (!task.active) return;

            if (!(mc.level.getEntity(entityId) instanceof Player player)) {
                task.reset();
                return;
            }

            BlockState state = mc.level.getBlockState(task.pos);
            if (state.isAir()) {
                task.active = false;
                return;
            }

            float hardness = state.getDestroySpeed(mc.level, task.pos);
            if (hardness == -1.0f) return;

            float speed = getMiningSpeed(state, player);
            task.progress += speed / hardness / 30.0f;
            if (task.progress >= task.targetSpeed) task.active = false;
        }

        boolean isBreaking(BlockPos pos) {
            if (current.pos != null && current.pos.equals(pos) && current.active) return true;
            return doubleMine.pos != null && doubleMine.pos.equals(pos) && doubleMine.active;
        }
    }

    // entity id -> break state, populated straight off ClientboundBlockDestructionPacket --
    // BreakPredictionService's own player map, unpruned same as the reference (a stale id just sits
    // inactive; onPacketReceive/startBreak reactivates it the moment that id mines again).
    private final Map<Integer, BreakState> breakStates = new HashMap<>();

    private float getMiningSpeed(BlockState state, Player player) {
        ItemStack stack = new ItemStack(Items.NETHERITE_PICKAXE);
        float speed = stack.getDestroySpeed(state);
        if (speed > 1.0f) {
            int level = 5;
            speed += (level * level + 1);
        }
        if (!player.onGround()) speed /= 5.0f;
        return speed;
    }

    @Override
    public String getMetaData() {
        return String.valueOf(targetCount);
    }

    @Override
    public void onDisable() {
        ticks = 0;
        targetCount = 0;
        targetPositions.clear();
        breakStates.clear();
    }

    @SubscribeEvent
    public void onPlayerJump(PlayerJumpEvent event) {
        if (jumpDisable.getValue()) setToggled(false);
    }

    // Ported from BreakPredictionService.onPacketReceive verbatim (minus the extra MC.execute hop,
    // which only existed there to get back onto the client thread -- PacketReceiveEvent already
    // fires there in this project).
    @SubscribeEvent
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!antiBreak.getValue() || mc.level == null) return;
        if (!(event.getPacket() instanceof ClientboundBlockDestructionPacket packet)) return;
        if (!(mc.level.getEntity(packet.getId()) instanceof Player)) return;

        breakStates.computeIfAbsent(packet.getId(), BreakState::new).startBreak(packet.getPos(), 0.7f);
    }

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (mc.player == null || mc.level == null) return;

        // Nami's own jumpDisable check -- it toggles off the moment you are airborne, not only on
        // the jump keypress (stepping off a ledge counts too).
        if (jumpDisable.getValue() && !mc.player.onGround()) {
            setToggled(false);
            return;
        }

        if (ticks < delay.getValue().intValue()) {
            ticks++;
            return;
        }

        if (autoSwitch.getValue().equalsIgnoreCase("None") && (!(mc.player.getMainHandItem().getItem() instanceof BlockItem blockItem) || !isBlastProof(blockItem.getBlock()))) {
            EUClient.CHAT_MANAGER.tagged("You are currently not holding any blast-proof blocks.", getName());
            setToggled(false);
            return;
        }

        int slot = findBlastProofBlock();
        int previousSlot = mc.player.getInventory().getSelectedSlot();
        if (slot == -1) {
            EUClient.CHAT_MANAGER.tagged("No blast-proof blocks could be found in your hotbar.", getName());
            setToggled(false);
            return;
        }

        targetPositions = getTargets();
        if (antiBreak.getValue()) applyAntiBreak();

        List<BlockPos> positions = targetPositions.stream()
                .filter(position -> mc.player.distanceToSqr(Vec3.atCenterOf(position)) <= Mth.square(range.getValue().doubleValue()))
                .filter(WorldUtils::isPlaceable)
                .toList();

        targetCount = positions.size();

        if (attack.getValue()) attackTrapCrystals();

        if (positions.isEmpty()) {
            if (selfDisable.getValue()) setToggled(false);
            return;
        }

        if (!multiTask.getValue() && mc.player.isUsingItem()) return;

        if (rotate.getValue()) EUClient.ROTATION_MANAGER.silentRotate(mc.player.getYRot(), 90f);
        InventoryUtils.switchSlot(autoSwitch.getValue(), slot, previousSlot);

        int placed = 0;
        List<BlockPos> placedPositions = new ArrayList<>();
        for (BlockPos position : positions) {
            if (placed >= limit.getValue().intValue()) break;

            Direction direction = WorldUtils.getDirection(position, placedPositions, strictDirection.getValue());
            if (direction == null) {
                if (!airPlace.getValue()) continue;
                if (!placeAir(position.below(), Direction.UP)) continue;
                placedPositions.add(position);
                placed++;
                continue;
            }

            if (!WorldUtils.placeBlock(position, direction, InteractionHand.MAIN_HAND, false, true, render.getValue())) continue;
            placedPositions.add(position);
            placed++;
        }

        InventoryUtils.switchBack(autoSwitch.getValue(), slot, previousSlot);
        ticks = 0;
    }

    // Ported from InteractionUtils.airPlace's Grim branch verbatim: swap the block onto the
    // offhand, place from OFF_HAND, swing, then swap the offhand item straight back. A normal
    // MAIN_HAND air-place packet has no supporting neighbor at all, which some anticheats flag on
    // its own -- routing it through the same SWAP_ITEM_WITH_OFFHAND dance a legit player uses for
    // shield/totem timing makes the place packet's hand line up with a hand-swap that already has a
    // legitimate reason to happen.
    private boolean placeAir(BlockPos position, Direction direction) {
        if (!mc.level.getBlockState(position.relative(direction.getOpposite())).canBeReplaced()) return false;

        if (!grim.getValue()) return WorldUtils.placeBlock(position, direction, InteractionHand.MAIN_HAND, false, true, render.getValue());

        BlockHitResult hitResult = new BlockHitResult(Vec3.atCenterOf(position.relative(direction.getOpposite())), direction, position, false);

        mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
        NetworkUtils.sendSequencedPacket(sequence -> new ServerboundUseItemOnPacket(InteractionHand.OFF_HAND, hitResult, sequence));
        if (swing.getValue()) mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.OFF_HAND));
        mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
        return true;
    }

    // Ported from TrapComponent.doBreak verbatim: rotate toward the crystal, confirm it's actually
    // reachable off the last server-acked rotation (or the eye is already inside its box), then
    // attack. WorldUtils.canSee/canSeeBlock is this project's own equivalent of Nami's
    // raycastTarget-against-serverYRot/XRot reach check.
    private void attackTrapCrystals() {
        for (EndCrystal crystal : mc.level.getEntitiesOfClass(EndCrystal.class, new AABB(mc.player.blockPosition()).inflate(range.getValue().doubleValue() + 3.0))) {
            if (crystal.tickCount < attackAge.getValue().intValue()) continue;

            AABB crystalBox = crystal.getBoundingBox();
            boolean threatens = false;
            for (BlockPos pos : targetPositions) {
                if (!mc.level.getBlockState(pos).canBeReplaced()) continue;
                if (new AABB(pos).intersects(crystalBox)) {
                    threatens = true;
                    break;
                }
            }
            if (!threatens) continue;

            if (crystalBox.distanceToSqr(mc.player.getEyePosition()) > Mth.square(attackRange.getValue().doubleValue())) continue;
            if (!attackMultiTask.getValue() && mc.player.isUsingItem()) continue;

            if (attackRotate.getValue()) {
                float[] rotations = eu.client.utils.rotations.RotationUtils.getRotations(crystal.getBoundingBox().getCenter());
                EUClient.ROTATION_MANAGER.silentRotate(rotations[0], rotations[1]);

                boolean insideBox = crystalBox.contains(mc.player.getEyePosition());
                if (!insideBox && !WorldUtils.canSee(crystal)) continue;
            }

            mc.getConnection().send(new ServerboundAttackPacket(crystal.getId()));
            if (attackSwing.getValue()) mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        }
    }

    // Ported from TrapComponent.onTick's AntiBreak block verbatim: for every trap position currently
    // being mined (per the BreakPredictionService port above), queue every non-DOWN neighbor of it
    // as an extra target -- skipping one already in the set, already queued, not replaceable, or
    // with an entity standing in it (see isEntityBlocking's own note on that condition's naming).
    private void applyAntiBreak() {
        for (BreakState state : breakStates.values()) state.onTick();
        if (!antiBreak.getValue() || targetPositions.isEmpty()) return;

        List<BlockPos> extraTargets = new ArrayList<>();
        for (BlockPos pos : targetPositions) {
            boolean breaking = false;
            for (BreakState state : breakStates.values()) {
                if (state.isBreaking(pos)) {
                    breaking = true;
                    break;
                }
            }
            if (!breaking) continue;

            for (Direction dir : Direction.values()) {
                if (dir == Direction.DOWN) continue;

                BlockPos around = pos.relative(dir);
                if (targetPositions.contains(around)) continue;
                if (extraTargets.contains(around)) continue;
                if (!mc.level.getBlockState(around).canBeReplaced()) continue;
                if (isEntityBlocking(around)) continue;

                extraTargets.add(around);
            }
        }
        targetPositions.addAll(extraTargets);
    }

    // Ported from BlockUtils.isPlaceable(pos, distance) verbatim, including its own naming (it
    // actually means "an entity occupies this position", not "you may place a block here") and its
    // own distance param being compared straight against distanceToSqr (so effectively ~3.16 blocks,
    // not 10) -- both kept as-is rather than "fixed" to stay a faithful port.
    private boolean isEntityBlocking(BlockPos pos) {
        AABB blockBox = new AABB(pos);
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.distanceToSqr(mc.player) > 10) continue;
            if (entity instanceof EndCrystal) continue;
            if (entity instanceof ItemEntity) continue;
            if (entity instanceof Arrow) continue;
            if (entity.getBoundingBox().intersects(blockBox)) return true;
        }
        return false;
    }

    private Set<BlockPos> getTargets() {
        Set<BlockPos> positions = new LinkedHashSet<>(HoleUtils.getFeetPositions(mc.player, extension.getValue(), floor.getValue(), false));
        if (!corners.getValue()) return positions;

        // Nami's corners block, verbatim: the four diagonals of every floor cell the bounding box
        // overlaps, added only when isEntityBlocking (Nami's own isPlaceable) says nothing's already
        // standing there.
        AABB box = mc.player.getBoundingBox();
        int y = Mth.floor(mc.player.getY());
        for (int x = Mth.floor(box.minX); x < Math.ceil(box.maxX); x++) {
            for (int z = Mth.floor(box.minZ); z < Math.ceil(box.maxZ); z++) {
                BlockPos base = new BlockPos(x, y, z);
                for (BlockPos corner : new BlockPos[]{base.north().east(), base.north().west(), base.south().east(), base.south().west()}) {
                    if (!isEntityBlocking(corner)) positions.add(corner);
                }
            }
        }
        return positions;
    }

    // Same pick-the-hardest-blast-proof-block rule Surround/SelfTrap use.
    // ponytail: third copy of this pair in the combat package -- worth hoisting into InventoryUtils
    // the next time any of the three needs a change, not worth touching two working modules now.
    private int findBlastProofBlock() {
        float bestHardness = -1;
        int bestSlot = -1;

        for (int i = 0; i <= 8; i++) {
            if (!(mc.player.getInventory().getItem(i).getItem() instanceof BlockItem item)) continue;

            Block block = item.getBlock();
            if (!isBlastProof(block)) continue;

            float hardness = block.defaultDestroyTime();
            if (hardness == -1) return i;
            if (hardness > bestHardness) {
                bestHardness = hardness;
                bestSlot = i;
            }
        }

        return bestSlot;
    }

    private boolean isBlastProof(Block block) {
        return block == Blocks.OBSIDIAN ||
               block == Blocks.ENDER_CHEST ||
               block == Blocks.CRYING_OBSIDIAN ||
               block == Blocks.NETHERITE_BLOCK ||
               block == Blocks.RESPAWN_ANCHOR ||
               block == Blocks.ANCIENT_DEBRIS ||
               block == Blocks.ENCHANTING_TABLE ||
               block == Blocks.ANVIL ||
               block == Blocks.CHIPPED_ANVIL ||
               block == Blocks.DAMAGED_ANVIL;
    }
}
