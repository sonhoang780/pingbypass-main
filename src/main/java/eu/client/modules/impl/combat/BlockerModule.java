package eu.client.modules.impl.combat;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerMineEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.modules.impl.player.SpeedMineModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.HoleUtils;
import eu.client.utils.minecraft.InventoryUtils;
import eu.client.utils.minecraft.WorldUtils;
import eu.client.utils.system.ThreadExecutor;
import eu.client.utils.system.Timer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@RegisterModule(name = "Blocker", description = "Places blocks to stop enemies from placing crystals.", category = Module.Category.COMBAT, proxyEnhanced = true)
public class BlockerModule extends Module {
    public ModeSetting autoSwitch = new ModeSetting("Switch", "The mode that will be used for automatically switching to necessary items.", "Silent", InventoryUtils.SWITCH_MODES);
    public BooleanSetting asynchronous = new BooleanSetting("Asynchronous", "Performs calculations on separate threads.", true);
    public NumberSetting limit = new NumberSetting("Limit", "The number of blocks that can be placed per tick.", 4, 1, 20);
    public NumberSetting delay = new NumberSetting("Delay", "The amount of ticks that have to be waited for between placements.", 0, 0, 20);
    public NumberSetting range = new NumberSetting("Range", "The maximum range at which the blocks will be placed at.", 5.0, 0.0, 12.0);
    public BooleanSetting rotate = new BooleanSetting("Rotate", "Sends a packet rotation whenever placing a block.", true);
    public BooleanSetting strictDirection = new BooleanSetting("StrictDirection", "Only places using directions that face you.", false);
    public BooleanSetting crystalDestruction = new BooleanSetting("CrystalDestruction", "Destroys any crystals that interfere with block placement.", true);
    public BooleanSetting whileEating = new BooleanSetting("WhileEating", "Places blocks normally while eating.", true);

    public BooleanSetting feet = new BooleanSetting("Feet", "Places on feet level blocks.", true);
    public BooleanSetting head = new BooleanSetting("Head", "Places on head level blocks.", true);

    public BooleanSetting render = new BooleanSetting("Render", "Whether or not to render the place position.", true);

    private final CopyOnWriteArrayList<Position> targetPositions = new CopyOnWriteArrayList<>();
    private Mine mine = null;

    private int ticks = 0;

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (shouldRunOnProxy()) return;
        if (!whileEating.getValue() && eu.client.utils.minecraft.EntityUtils.isEating()) return;

        Runnable runnable = () -> {
            if (mc.player == null || mc.level == null) return;

            SpeedMineModule module = EUClient.MODULE_MANAGER.getModule(SpeedMineModule.class);
            if (mine != null && (module.getPrimary() != null && mine.position().equals(module.getPrimary().getPosition())) || (module.getSecondary() != null && mine.position().equals(module.getSecondary().getPosition()))) {
                mine = null;
                return;
            }

            int blocksPlaced = 0;
            if (ticks < delay.getValue().intValue()) {
                ticks++;
                return;
            }

            HashSet<BlockPos> feetPositions = HoleUtils.getFeetPositions(mc.player, true, false, false);
            List<BlockPos> insidePositions = HoleUtils.getInsidePositions(mc.player);

            if (mine != null && mine.timer().hasTimeElapsed(Math.max(mine.breakTime() - 200L, 0L))) {
                BlockPos position = mine.position();
                if (mine.type() == MineType.FEET && feet.getValue()) {
                    if (feetPositions.contains(mine.position())) {
                        targetPositions.add(new Position(position, position.above()));

                        for (Direction direction : Direction.values()) {
                            if (!direction.getAxis().isHorizontal()) continue;
                            targetPositions.add(new Position(position, position.relative(direction)));
                        }
                    }

                    mine = null;
                } else if ((mine.type() == MineType.HEAD || mine.type() == MineType.SIDE) && head.getValue()) {
                    if ((mine.type() == MineType.HEAD && insidePositions.contains(mine.position().below().below())) || (mine.type() == MineType.SIDE && feetPositions.contains(mine.position().below()))) {
                        targetPositions.add(new Position(position, position.above()));
                    }

                    mine = null;
                }
            }

            targetPositions.removeIf(position -> !WorldUtils.isPlaceable(position.position()));
            targetPositions.removeIf(position -> mc.player.distanceToSqr(Vec3.atCenterOf(position.position())) > Mth.square(range.getValue().doubleValue()));

            targetPositions.removeIf(position -> !feetPositions.contains(position.original()) && !feetPositions.contains(position.original().below()) && !insidePositions.contains(position.original().below().below()));

            if (targetPositions.isEmpty()) return;

            int slot = InventoryUtils.findHardestBlock(0, autoSwitch.getValue().equalsIgnoreCase("AltSwap") || autoSwitch.getValue().equalsIgnoreCase("AltPickup") ? 35 : 8);
            int previousSlot = mc.player.getInventory().getSelectedSlot();

            if (slot == -1) {
                targetPositions.clear();
                return;
            }

            InventoryUtils.switchSlot(autoSwitch.getValue(), slot, previousSlot);

            for (Position position : new ArrayList<>(targetPositions)) {
                if (blocksPlaced >= limit.getValue().intValue()) break;

                Direction direction = WorldUtils.getDirection(position.position(), null, strictDirection.getValue());
                if (direction == null) continue;

                WorldUtils.placeBlock(position.position(), direction, InteractionHand.MAIN_HAND, rotate.getValue(), crystalDestruction.getValue(), render.getValue());
                targetPositions.remove(position);
                blocksPlaced++;
            }

            InventoryUtils.switchBack(autoSwitch.getValue(), slot, previousSlot);

            ticks = 0;
        };

        if (asynchronous.getValue()) ThreadExecutor.execute(runnable);
        else runnable.run();
    }

    @SubscribeEvent
    public void onPlayerMine(PlayerMineEvent event) {
        if (shouldRunOnProxy()) return;
        if (mc.player == null || mc.level == null) return;
        if (mine != null && mine.position().equals(event.getPosition())) return;

        SpeedMineModule module = EUClient.MODULE_MANAGER.getModule(SpeedMineModule.class);
        if ((module.getPrimary() != null && event.getPosition().equals(module.getPrimary().getPosition())) || (module.getSecondary() != null && event.getPosition().equals(module.getSecondary().getPosition())))
            return;

        Entity entity = mc.level.getEntity(event.getActorID());

        if (entity == mc.player) return;
        if (!(entity instanceof Player player)) return;
        if (EUClient.FRIEND_MANAGER.contains(player.getName().getString())) return;

        HashSet<BlockPos> feetPositions = HoleUtils.getFeetPositions(mc.player, true, false, false);
        List<BlockPos> insidePositions = HoleUtils.getInsidePositions(mc.player);

        if (feet.getValue() && feetPositions.contains(event.getPosition())) {
            mine = new Mine(event.getPosition(), new Timer(), WorldUtils.getBreakTime(player, mc.level.getBlockState(event.getPosition())), MineType.FEET);
            return;
        }

        if (head.getValue()) {
            if (feetPositions.contains(event.getPosition().below())) {
                mine = new Mine(event.getPosition(), new Timer(), WorldUtils.getBreakTime(player, mc.level.getBlockState(event.getPosition())), MineType.SIDE);
            }

            if (insidePositions.contains(event.getPosition().below().below())) {
                mine = new Mine(event.getPosition(), new Timer(), WorldUtils.getBreakTime(player, mc.level.getBlockState(event.getPosition())), MineType.HEAD);
            }
        }
    }

    @Override
    public void onDisable() {
        targetPositions.clear();
    }

    @Override
    public String getMetaData() {
        if (targetPositions == null) return "0";
        return String.valueOf(targetPositions.size());
    }

    private record Mine(BlockPos position, Timer timer, float breakTime, MineType type) { }
    private record Position(BlockPos original, BlockPos position) { }
    private enum MineType {
        FEET, HEAD, SIDE
    }
}
