package eu.client.modules.impl.miscellaneous;

import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.InventoryUtils;
import eu.client.utils.minecraft.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

@RegisterModule(name = "Scaffold", description = "Places blocks under the player to bridge automatically.", category = Module.Category.MISCELLANEOUS)
public class ScaffoldModule extends Module {
    public ModeSetting autoSwitch = new ModeSetting("Switch", "The mode that will be used for automatically switching to blocks.", "Silent", InventoryUtils.SWITCH_MODES);
    public NumberSetting range = new NumberSetting("Range", "Range to place blocks.", 4.0, 1.0, 6.0);
    public BooleanSetting keepY = new BooleanSetting("KeepY", "Maintains the player's Y level while moving.", false);
    public BooleanSetting downwards = new BooleanSetting("Downwards", "Places blocks below your feet when sneaking.", true);
    public BooleanSetting tower = new BooleanSetting("Tower", "Quickly towers up when jumping.", true);
    public NumberSetting towerSpeed = new NumberSetting("TowerSpeed", "Vertical tower speed.", new BooleanSetting.Visibility(tower, true), 0.42, 0.1, 1.0);
    public BooleanSetting rotate = new BooleanSetting("Rotate", "Rotates towards block placements.", true);
    public BooleanSetting strictDirection = new BooleanSetting("StrictDirection", "Only places using directions facing you.", false);
    public BooleanSetting airPlace = new BooleanSetting("AirPlace", "Places blocks in the air without support.", false);
    public NumberSetting delay = new NumberSetting("Delay", "Delay in ticks between placements.", 0, 0, 10);
    public BooleanSetting render = new BooleanSetting("Render", "Renders placed positions.", true);

    private int groundPosY = Integer.MIN_VALUE;
    private BlockPos lastPlacement = null;
    private int ticks = 0;

    @Override
    public void onEnable() {
        groundPosY = Integer.MIN_VALUE;
        lastPlacement = null;
        ticks = 0;
    }

    @Override
    public void onDisable() {
        groundPosY = Integer.MIN_VALUE;
        lastPlacement = null;
    }

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (mc.player == null || mc.level == null) return;

        if (ticks < delay.getValue().intValue()) {
            ticks++;
            return;
        }

        int maxSlot = autoSwitch.getValue().equalsIgnoreCase("AltSwap") || autoSwitch.getValue().equalsIgnoreCase("AltPickup") ? 35 : 8;
        int slot = findValidBlockSlot(0, maxSlot);
        if (slot == -1) return;

        // Towering
        if (tower.getValue() && mc.options.keyJump.isDown() && !isMovingHorizontally()) {
            mc.player.setDeltaMovement(mc.player.getDeltaMovement().x, towerSpeed.getValue().doubleValue(), mc.player.getDeltaMovement().z);
        }

        int posY = (int) Math.floor(mc.player.getY());
        if (keepY.getValue() && isMovingHorizontally()) {
            if (mc.player.onGround() || groundPosY < mc.level.getMinY()) {
                groundPosY = posY;
            }
            posY = groundPosY;
        } else if (downwards.getValue() && mc.options.keyShift.isDown()) {
            posY = mc.player.getBlockY() - 1;
        } else {
            groundPosY = posY;
        }

        BlockPos playerBlockPos = new BlockPos(mc.player.getBlockX(), posY, mc.player.getBlockZ());
        BlockPos targetPos = playerBlockPos.below();

        List<BlockPos> placements = getScaffoldPlacements(targetPos);
        if (placements.isEmpty()) return;

        int prevSlot = mc.player.getInventory().getSelectedSlot();
        if (!InventoryUtils.switchSlot(autoSwitch.getValue(), slot, prevSlot)) return;

        for (BlockPos pos : placements) {
            if (!mc.level.getBlockState(pos).canBeReplaced()) continue;

            Direction dir = WorldUtils.getDirection(pos, strictDirection.getValue());
            if (dir == null) {
                if (airPlace.getValue()) {
                    WorldUtils.placeBlock(pos.below(), Direction.UP, InteractionHand.MAIN_HAND, rotate.getValue(), false, render.getValue());
                    lastPlacement = pos;
                }
                continue;
            }

            if (WorldUtils.placeBlock(pos, dir, InteractionHand.MAIN_HAND, rotate.getValue(), false, render.getValue())) {
                lastPlacement = pos;
            }
        }

        InventoryUtils.switchBack(autoSwitch.getValue(), slot, prevSlot);
        ticks = 0;
    }

    private boolean isMovingHorizontally() {
        return Math.abs(mc.player.getDeltaMovement().x) > 0.05 || Math.abs(mc.player.getDeltaMovement().z) > 0.05;
    }

    private int findValidBlockSlot(int start, int end) {
        for (int i = start; i <= end; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem) {
                Block block = blockItem.getBlock();
                if (!block.defaultBlockState().canBeReplaced() && block.defaultBlockState().blocksMotion()) {
                    return i;
                }
            }
        }
        return -1;
    }

    private List<BlockPos> getScaffoldPlacements(BlockPos targetPos) {
        List<BlockPos> placements = new ArrayList<>();
        if (isPlaceableDirect(targetPos) || airPlace.getValue()) {
            placements.add(targetPos);
            return placements;
        }

        // Shoreline Bresenham support interpolation
        if (lastPlacement != null) {
            int x0 = lastPlacement.getX();
            int y0 = lastPlacement.getY();
            int z0 = lastPlacement.getZ();
            int x1 = targetPos.getX();
            int y1 = targetPos.getY();
            int z1 = targetPos.getZ();

            int dx = x1 - x0;
            int dy = y1 - y0;
            int dz = z1 - z0;
            int sx = Integer.compare(dx, 0);
            int sy = Integer.compare(dy, 0);
            int sz = Integer.compare(dz, 0);

            dx = Math.abs(dx);
            dy = Math.abs(dy);
            dz = Math.abs(dz);

            int ax = dx << 1;
            int ay = dy << 1;
            int az = dz << 1;

            int steps = 0;
            if (dx >= dy && dx >= dz) {
                int yd = ay - dx;
                int zd = az - dx;
                while (true) {
                    BlockPos p = new BlockPos(x0, y0, z0);
                    ensurePlaceableWithSupport(p, placements);
                    if (++steps > 8 || (x0 == x1 && y0 == y1 && z0 == z1)) break;
                    if (yd >= 0) { y0 += sy; yd -= ax; }
                    if (zd >= 0) { z0 += sz; zd -= ax; }
                    x0 += sx;
                    yd += ay;
                    zd += az;
                }
            } else if (dy >= dx && dy >= dz) {
                int xd = ax - dy;
                int zd = az - dy;
                while (true) {
                    BlockPos p = new BlockPos(x0, y0, z0);
                    ensurePlaceableWithSupport(p, placements);
                    if (++steps > 8 || (x0 == x1 && y0 == y1 && z0 == z1)) break;
                    if (xd >= 0) { x0 += sx; xd -= ay; }
                    if (zd >= 0) { z0 += sz; zd -= ay; }
                    y0 += sy;
                    xd += ax;
                    zd += az;
                }
            } else {
                int xd = ax - dz;
                int yd = ay - dz;
                while (true) {
                    BlockPos p = new BlockPos(x0, y0, z0);
                    ensurePlaceableWithSupport(p, placements);
                    if (++steps > 8 || (x0 == x1 && y0 == y1 && z0 == z1)) break;
                    if (xd >= 0) { x0 += sx; xd -= az; }
                    if (yd >= 0) { y0 += sy; yd -= az; }
                    z0 += sz;
                    xd += ax;
                    yd += ay;
                }
            }
        }

        if (placements.isEmpty()) {
            ensurePlaceableWithSupport(targetPos, placements);
        }

        return placements;
    }

    private boolean isPlaceableDirect(BlockPos pos) {
        if (!mc.level.getBlockState(pos).canBeReplaced()) return false;
        Direction face = WorldUtils.getDirection(pos, strictDirection.getValue());
        return face != null;
    }

    private void ensurePlaceableWithSupport(BlockPos pos, List<BlockPos> out) {
        if (!mc.level.getBlockState(pos).canBeReplaced()) return;

        Direction face = WorldUtils.getDirection(pos, strictDirection.getValue());
        if (face != null) {
            if (!out.contains(pos) && isWithinRange(pos)) out.add(pos);
            return;
        }

        BlockPos support = getSupportingBlock(pos);
        if (support != null) {
            if (!out.contains(support) && isWithinRange(support)) {
                out.add(support);
            }
        } else {
            BlockPos down = pos.below();
            int depth = 0;
            while (depth++ < 3 && mc.level.getBlockState(down).canBeReplaced()) {
                if (WorldUtils.getDirection(down, strictDirection.getValue()) != null) {
                    if (!out.contains(down) && isWithinRange(down)) {
                        out.add(down);
                    }
                    break;
                }
                down = down.below();
            }
        }

        if (!out.contains(pos) && isWithinRange(pos)) {
            out.add(pos);
        }
    }

    private BlockPos getSupportingBlock(BlockPos pos) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = pos.relative(dir);
            if (WorldUtils.getDirection(side, strictDirection.getValue()) != null) {
                return side;
            }
        }
        return null;
    }

    private boolean isWithinRange(BlockPos pos) {
        return mc.player.distanceToSqr(pos.getCenter()) <= range.getValue().doubleValue() * range.getValue().doubleValue();
    }
}
