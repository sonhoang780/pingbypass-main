package eu.client.modules.impl.combat;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PacketReceiveEvent;
import eu.client.events.impl.PlayerJumpEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.modules.impl.movement.HitboxDesyncModule;
import eu.client.modules.impl.movement.SpeedModule;
import eu.client.modules.impl.movement.StepModule;
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
import java.util.HashSet;
import java.util.List;
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
    public BooleanSetting extension = new BooleanSetting("Extension", "Extends the surround if there are entities obstructing block placement.", true);
    public BooleanSetting whileEating = new BooleanSetting("WhileEating", "Places blocks normally while eating.", true);
    // Ported from Sydney-Legacy's Surround -- both were declared-but-dropped during the port
    // (ClientboundBlockUpdatePacket sat imported and unused; there was no PlayerPositionLook
    // handling at all).
    public BooleanSetting chorusCenter = new BooleanSetting("CenterOnTP", "Centers you if you have just teleported to surround against crystals easier.", true);
    public BooleanSetting predict = new BooleanSetting("Predict", "Replaces a surround block instantly the moment we see the server's own break packet for it, instead of waiting for the next normal placement cycle.", true);

    public BooleanSetting selfDisable = new BooleanSetting("SelfDisable", "Toggles off the module once it is finished with placing.", false);
    public BooleanSetting jumpDisable = new BooleanSetting("JumpDisable", "Toggles off the module whenever your Y level changes.", true);
    public BooleanSetting itemDisable = new BooleanSetting("ItemDisable", "Toggles off the module whenever you run out of items to place with.", true);

    public BooleanSetting stepToggle = new BooleanSetting("StepToggle", "Toggles the step module when you surround.", false);
    public BooleanSetting speedToggle = new BooleanSetting("SpeedToggle", "Toggles the speed module when you surround.", false);

    public BooleanSetting render = new BooleanSetting("Render", "Whether or not to render the place position.", true);

    private Set<BlockPos> targetPositions = new HashSet<>();
    private BlockPos lastPosition = null;

    private int ticks = 0;
    private int blocksPlaced = 0;
    // Set when the server acks a chorus-fruit teleport while chorusCenter is on -- the actual
    // re-center happens on the NEXT player-update tick (the teleport packet itself only carries
    // the new position; mc.player's own position needs a tick to actually reflect it here).
    private boolean awaitChorusCenter = false;

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
        if(center.getValue()) mc.player.setPos(lastPosition.getX() + 0.5, lastPosition.getY(), lastPosition.getZ() + 0.5);
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
            if (itemDisable.getValue()) {
                EUClient.CHAT_MANAGER.tagged("You are currently not holding any blocks.", getName());
                setToggled(false);
            }

            targetPositions.clear();
            return;
        }

        int slot = InventoryUtils.findHardestBlock(0, 8);
        int previousSlot = mc.player.getInventory().getSelectedSlot();

        if (slot == -1) {
            if (itemDisable.getValue()) {
                EUClient.CHAT_MANAGER.tagged("No blocks could be found in your hotbar.", getName());
                setToggled(false);
            }

            targetPositions.clear();
            return;
        }

        targetPositions = HoleUtils.getFeetPositions(mc.player, extension.getValue(), floor.getValue(), false);

        HitboxDesyncModule module = EUClient.MODULE_MANAGER.getModule(HitboxDesyncModule.class);
        List<BlockPos> positions = targetPositions.stream().filter(position -> mc.player.distanceToSqr(Vec3.atCenterOf(position)) <= Mth.square(range.getValue().doubleValue()))
                .filter(position -> WorldUtils.isPlaceable(position, module.isToggled() && !module.close.getValue()))
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

            InventoryUtils.switchSlot(autoSwitch.getValue(), slot, previousSlot);
            WorldUtils.placeBlock(packet.getPos(), direction, InteractionHand.MAIN_HAND, rotate.getValue(), crystalDestruction.getValue(), render.getValue());
            blocksPlaced++;
            InventoryUtils.switchBack(autoSwitch.getValue(), slot, previousSlot);
        }

        if (event.getPacket() instanceof ClientboundAddEntityPacket packet && packet.getType().equals(EntityType.END_CRYSTAL)) {
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

                    InventoryUtils.switchSlot(autoSwitch.getValue(), slot, previousSlot);

                    WorldUtils.placeBlock(position, direction, InteractionHand.MAIN_HAND, () -> {
                        mc.player.connection.send(new ServerboundAttackPacket(crystal.getId()));
                        mc.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                    }, rotate.getValue(), false, render.getValue());
                    blocksPlaced++;

                    InventoryUtils.switchBack(autoSwitch.getValue(), slot, previousSlot);

                    break;
                }
            }
        }
    }

    @SubscribeEvent
    public void onDisable() {
        lastPosition = null;
        awaitChorusCenter = false;
        targetPositions.clear();

        ticks = 0;
        blocksPlaced = 0;
    }

    @Override
    public String getMetaData() {
        return String.valueOf(targetPositions.size());
    }
}
