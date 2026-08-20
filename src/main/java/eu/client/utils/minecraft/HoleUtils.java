package eu.client.utils.minecraft;

import eu.client.EUClient;
import eu.client.modules.impl.movement.HitboxDesyncModule;
import eu.client.utils.IMinecraft;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Vec3i;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;

public class HoleUtils implements IMinecraft {
    private static final Vec3i[] holeOffsets = new Vec3i[]{new Vec3i(0, -1, 0), new Vec3i(1, 0, 0), new Vec3i(-1, 0,0), new Vec3i(0, 0, 1), new Vec3i(0, 0, -1)};

    private static final Vec3i[] singleOffsets = {new Vec3i(-1, 0, 0), new Vec3i(1, 0, 0), new Vec3i(0, 0, -1), new Vec3i(0, 0, 1), new Vec3i(0, -1, 0)};
    private static final Vec3i[] doubleXOffsets = {new Vec3i(-1, 0, 0), new Vec3i(0, 0, -1), new Vec3i(0, 0, 1), new Vec3i(0, -1, 0), new Vec3i(2, 0, 0), new Vec3i(1, 0, -1), new Vec3i(1, 0, 1), new Vec3i(1, -1, 0)};
    private static final Vec3i[] doubleZOffsets = {new Vec3i(0, 0, -1), new Vec3i(-1, 0, 0), new Vec3i(1, 0, 0), new Vec3i(0, -1, 0), new Vec3i(0, 0, 2), new Vec3i(-1, 0, 1), new Vec3i(1, 0, 1), new Vec3i(0, -1, 1)};
    private static final Vec3i[] quadOffsets = {new Vec3i(-1, 0, 0), new Vec3i(0, 0, -1), new Vec3i(0, -1, 0), new Vec3i(2, 0, 0), new Vec3i(1, 0, -1), new Vec3i(1, -1, 0), new Vec3i(-1, 0, 1), new Vec3i(0, 0, 2), new Vec3i(0, -1, 1), new Vec3i(2, 0, 1), new Vec3i(1, 0, 2), new Vec3i(1, -1, 1)};

    public static boolean isPlayerInHole(Player player) {
        return HoleUtils.getFeetPositions(player, true, true, false).stream().noneMatch(position -> mc.level.getBlockState(position).canBeReplaced());
    }

    public static List<BlockPos> getInsidePositions(Entity targetEntity) {
        if (targetEntity == null || mc.level == null) return Collections.emptyList();
        List<BlockPos> targetPositions = new ArrayList<>();
        AABB box = targetEntity.getBoundingBox();

        boolean isHorizontal = targetEntity.isSwimming()
                || targetEntity.isVisuallySwimming()
                || targetEntity.isVisuallyCrawling()
                || (targetEntity instanceof LivingEntity living && living.isFallFlying())
                || targetEntity.getPose() == Pose.SWIMMING
                || targetEntity.getPose() == Pose.FALL_FLYING
                || (box.maxY - box.minY) <= 1.0;

        if (isHorizontal) {
            // When swimming/crawling, Minecraft's server AABB is only 0.6x0.6x0.6 around origin,
            // but the player's 1.8m body extends along their body yaw.
            // Sample along the 1.8m body line (head to feet) with a 0.3m radius to cover all occupied blocks.
            float yaw = targetEntity.getYRot();
            double rad = Math.toRadians(yaw);
            double dirX = -Math.sin(rad);
            double dirZ = Math.cos(rad);

            double posX = targetEntity.getX();
            double posY = targetEntity.getY();
            double posZ = targetEntity.getZ();

            // Sample from -0.9m to +0.9m along body axis and +-0.3m width
            for (double dist = -0.9; dist <= 0.9; dist += 0.2) {
                for (double width = -0.3; width <= 0.3; width += 0.3) {
                    double sx = posX + dirX * dist - dirZ * width;
                    double sz = posZ + dirZ * dist + dirX * width;
                    BlockPos pos = new BlockPos((int) Math.floor(sx), (int) Math.floor(posY), (int) Math.floor(sz));
                    if (!targetPositions.contains(pos)) {
                        targetPositions.add(pos);
                    }
                }
            }

            // Also include standard bounding box
            int minX = (int) Math.floor(box.minX);
            int maxX = (int) Math.ceil(box.maxX);
            int minY = (int) Math.floor(box.minY);
            int maxY = (int) Math.ceil(box.maxY);
            int minZ = (int) Math.floor(box.minZ);
            int maxZ = (int) Math.ceil(box.maxZ);

            for (int x = minX; x < maxX; x++) {
                for (int y = minY; y < maxY; y++) {
                    for (int z = minZ; z < maxZ; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!targetPositions.contains(pos)) {
                            targetPositions.add(pos);
                        }
                    }
                }
            }
        } else {
            int minX = (int) Math.floor(box.minX);
            int maxX = (int) Math.ceil(box.maxX);
            int minY = (int) Math.floor(box.minY);
            int minZ = (int) Math.floor(box.minZ);
            int maxZ = (int) Math.ceil(box.maxZ);
            // nami AutoMineFeature.crouchingShouldMinePhase scans full minY..maxY when crouched
            // (vs standsShouldMinePhase's single feet row) -- was missing here, torso-level phase
            // block in a 2-tall crouch pocket never got picked up. Standing/swim/crawl untouched.
            int scanMaxY = targetEntity.isCrouching() ? (int) Math.ceil(box.maxY) : minY + 1;

            for (int x = minX; x < maxX; x++) {
                for (int y = minY; y < scanMaxY; y++) {
                    for (int z = minZ; z < maxZ; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!targetPositions.contains(pos)) {
                            targetPositions.add(pos);
                        }
                    }
                }
            }
        }

        if (targetPositions.isEmpty()) {
            targetPositions.add(PositionUtils.getFlooredPosition(targetEntity));
        }

        return targetPositions;
    }

    public static HashSet<BlockPos> getFeetPositions(Player target, boolean extension, boolean floor, boolean targetOnly) {
        HashSet<BlockPos> positions = new HashSet<>();
        HashSet<BlockPos> blacklist = new HashSet<>();

        HitboxDesyncModule hitboxDesyncModule = EUClient.MODULE_MANAGER.getModule(HitboxDesyncModule.class);

        BlockPos feetPos = PositionUtils.getFlooredPosition(target);
        blacklist.add(feetPos);
        blacklist.addAll(getInsidePositions(target));

        if (extension) {
            for (Direction dir : Direction.values()) {
                if (dir.getAxis().isVertical()) continue;
                BlockPos off = feetPos.relative(dir);

                List<Player> collisions = WorldUtils.getCollisions(off);
                if (collisions.isEmpty()) continue;

                for (Player player : collisions) {
                    if ((player == mc.player && hitboxDesyncModule.isToggled() && !hitboxDesyncModule.close.getValue()))
                        continue;
                    if (targetOnly && player != target)
                        continue;

                    AABB box = player.getBoundingBox();
                    for (int x = (int) Math.floor(box.minX); x < Math.ceil(box.maxX); x++) {
                        for (int z = (int) Math.floor(box.minZ); z < Math.ceil(box.maxZ); z++) {
                            blacklist.add(new BlockPos(x, feetPos.getY(), z));
                        }
                    }
                    blacklist.addAll(getInsidePositions(player));
                }
            }
        }

        for (BlockPos pos : blacklist) {
            if(floor) positions.add(pos.below());

            for (Direction dir : Direction.values()) {
                if (!dir.getAxis().isHorizontal()) continue;
                BlockPos off = pos.relative(dir);
                if(!blacklist.contains(off)) positions.add(off);
            }
        }

        if (target == mc.player && hitboxDesyncModule.isToggled() && hitboxDesyncModule.close.getValue()) {
            List<BlockPos> desyncPositions = new ArrayList<>();

            Vec3 vec3d = mc.player.blockPosition().getCenter();
            boolean flagX = (vec3d.x - mc.player.getX()) > 0;
            boolean flagZ = (vec3d.z - mc.player.getZ()) > 0;

            if (flagX && flagZ) {
                desyncPositions.add(new BlockPos(mc.player.blockPosition().offset(-1, 0, 0)));
                desyncPositions.add(new BlockPos(mc.player.blockPosition().offset(0, 0, -1)));
            }

            if (!flagX && flagZ) {
                desyncPositions.add(new BlockPos(mc.player.blockPosition().offset(1, 0, 0)));
                desyncPositions.add(new BlockPos(mc.player.blockPosition().offset(0, 0, -1)));
            }

            if (flagX && !flagZ) {
                desyncPositions.add(new BlockPos(mc.player.blockPosition().offset(-1, 0, 0)));
                desyncPositions.add(new BlockPos(mc.player.blockPosition().offset(0, 0, 1)));
            }

            if (!flagX && !flagZ) {
                desyncPositions.add(new BlockPos(mc.player.blockPosition().offset(1, 0, 0)));
                desyncPositions.add(new BlockPos(mc.player.blockPosition().offset(0, 0, 1)));
            }

            positions.removeIf(desyncPositions::contains);
        }

        return positions;
    }

    // Was built off a SINGLE floored feet position, assuming the player always sits fully inside
    // one 1x1 floor cell -- standing near the middle/edge of a 2x2 or 1x2 hole means the hitbox
    // straddles TWO (or four) floor cells, and only the one PositionUtils.getFlooredPosition()
    // happened to pick got its ring of walls covered. Whichever adjacent cell the hitbox also
    // occupied was left with no trap positions at all, leaving that side of the hole open (the
    // reported "không cover được hết khi đứng giữa ô 2x2/1x2"). Now walks every floor cell the
    // player's own bounding box actually overlaps (same multi-cell detection getFeetPositions
    // already does for isPlayerInHole) and unions the trap ring for each one.
    public static List<BlockPos> getTrapPositions(Player player, boolean partial, boolean head, boolean antiStep, boolean antiBomb, boolean strictDirection) {
        java.util.LinkedHashSet<BlockPos> positions = new java.util.LinkedHashSet<>();
        HashSet<BlockPos> occupiedCells = getOccupiedFloorCells(player);

        for (BlockPos position : occupiedCells) {
            // The "staircase" side -- the y+1 wall block and the y+2 block on top of it that the
            // roof (0,2,0) is anchored against -- used to be hardcoded to +X (fullTrapOffsets'
            // (1,1,0)/(1,2,0), and the same pair in the partial offsets). That works for a player
            // sitting inside ONE floor cell, where every horizontal neighbour is a real hole wall.
            // Straddling a 2x2/1x2 hole makes +X the airspace ABOVE ANOTHER OCCUPIED FLOOR CELL:
            // open hole interior with no solid neighbour in any direction, so getDirection() came
            // back null, nothing was ever attempted (hence zero render preview) and -- because the
            // roof at y+2 is only ever diagonal to the y+1 walls, never adjacent -- that cell's
            // roof lost its only possible anchor too. Pick a side that actually faces a wall.
            Direction wallDirection = null;
            for (Direction dir : Direction.values()) {
                if (!dir.getAxis().isHorizontal()) continue;
                if (occupiedCells.contains(position.relative(dir))) continue;
                if (wallDirection == null) wallDirection = dir;
                if (WorldUtils.getDirection(position.relative(dir).above(), strictDirection) != null) {
                    wallDirection = dir;
                    break;
                }
            }
            if (wallDirection == null) wallDirection = Direction.EAST;

            if (antiStep) {
                addWallOffset(positions, occupiedCells, position, new Vec3i(1, 2, 0));
                addWallOffset(positions, occupiedCells, position, new Vec3i(-1, 2, 0));
                addWallOffset(positions, occupiedCells, position, new Vec3i(0, 2, 1));
                addWallOffset(positions, occupiedCells, position, new Vec3i(0, 2, -1));
            }

            if (antiBomb) {
                positions.add(position.offset(0, 3, 0));
            }

            if (partial) {
                BlockPos headPosition = position.offset(0, 2, 0);
                if (WorldUtils.getDirection(headPosition, strictDirection) != null) {
                    positions.add(headPosition);
                    continue;
                }

                positions.add(position.relative(wallDirection).above());
                positions.add(position.relative(wallDirection).above(2));
                positions.add(headPosition);
            } else {
                for (Direction dir : Direction.values()) {
                    if (!dir.getAxis().isHorizontal()) continue;
                    if (occupiedCells.contains(position.relative(dir))) continue;
                    positions.add(position.relative(dir).above());
                }

                if (head) {
                    positions.add(position.relative(wallDirection).above(2));
                    positions.add(position.offset(0, 2, 0));
                }
            }
        }

        return new ArrayList<>(positions);
    }

    // Mirrors getFeetPositions' `!blacklist.contains(off)` skip: a horizontal offset (side wall)
    // that lands over ANOTHER occupied floor cell is interior space above the player's own
    // footprint, not a real wall -- there's no solid neighbor there (it's open air over the same
    // hole), so getDirection() can never find a face and the position sat dead forever with no
    // render/place attempt at all (the reported "miss block" gaps on a 2-wide stance). Pure
    // vertical offsets (head/antiBomb, x=z=0) are never interior and always added.
    private static void addWallOffset(java.util.Set<BlockPos> positions, HashSet<BlockPos> occupiedCells, BlockPos base, Vec3i vec3i) {
        if (vec3i.getX() != 0 || vec3i.getZ() != 0) {
            BlockPos floorBelow = base.offset(vec3i.getX(), 0, vec3i.getZ());
            if (occupiedCells.contains(floorBelow)) return;
        }
        positions.add(base.offset(vec3i));
    }

    // Every floor-level BlockPos the player's own AABB actually overlaps -- 1 cell normally, up to
    // 4 when straddling a 2x2/1x2 opening. Same collision-box union getFeetPositions' blacklist
    // loop uses, factored out so getTrapPositions can cover all of them instead of just
    // PositionUtils.getFlooredPosition()'s single pick.
    private static HashSet<BlockPos> getOccupiedFloorCells(Player target) {
        HashSet<BlockPos> cells = new HashSet<>();
        BlockPos feetPos = PositionUtils.getFlooredPosition(target);
        cells.add(feetPos);

        for (Direction dir : Direction.values()) {
            if (dir.getAxis().isVertical()) continue;

            AABB box = target.getBoundingBox();
            BlockPos off = feetPos.relative(dir);
            // Cheap pre-check before the real overlap test: is the neighbouring cell even within
            // the hitbox's horizontal footprint?
            if (!(box.minX < off.getX() + 1 && box.maxX > off.getX() && box.minZ < off.getZ() + 1 && box.maxZ > off.getZ()))
                continue;

            for (int x = (int) Math.floor(box.minX); x < Math.ceil(box.maxX); x++) {
                for (int z = (int) Math.floor(box.minZ); z < Math.ceil(box.maxZ); z++) {
                    cells.add(new BlockPos(x, feetPos.getY(), z));
                }
            }
        }

        return cells;
    }

    // Loosened (2026-08-06): used to hard-reject a hole entirely unless EVERY wall was one of the
    // 4 classic blast-resistant blocks (bedrock/obsidian/respawn anchor/ender chest) -- fine for
    // PvP-safe crystal holing, but far too strict for general "walk into a hole" use (example-addon's
    // own HoleSnap port only ever required a wall to be solid at all, isBlock(): non-empty collision
    // shape, no block-type restriction). Now any solid block counts as a valid wall; the safety
    // classification (still SAFE/UNSAFE when every wall happens to qualify, same as before)
    // downgrades to NONE the moment any wall is just an ordinary solid block, instead of rejecting
    // the whole hole.
    //
    // MIXED used to mean "walls are a mix of bedrock and another blast-proof block (obsidian/
    // anchor/ender chest)" -- but that's still a fully blast-proof hole, just not pure bedrock, so
    // 3 bedrock walls + 1 obsidian wall was reported as MIXED when it should just be UNSAFE (the
    // weaker of the two tiers, same as an all-obsidian hole). SAFE only when EVERY wall is bedrock;
    // any other blast-proof block anywhere downgrades the whole hole to UNSAFE. A wall that isn't
    // blast-proof at all (but still solid) still forces NONE below, same as before.
    private static HoleSafety classifyWalls(BlockPos position, Vec3i[] offsets) {
        boolean allBedrock = true;
        boolean allBlastProof = true;

        for (Vec3i offset : offsets) {
            BlockPos pos = position.offset(offset);
            var block = mc.level.getBlockState(pos).getBlock();
            boolean bedrock = block.equals(Blocks.BEDROCK);
            boolean otherBlastProof = block.equals(Blocks.OBSIDIAN) || block.equals(Blocks.RESPAWN_ANCHOR) || block.equals(Blocks.ENDER_CHEST);

            if (!bedrock && !otherBlastProof) {
                allBlastProof = false;
                if (mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty()) return null;
                continue;
            }

            if (!bedrock) allBedrock = false;
        }

        if (!allBlastProof) return HoleSafety.NONE;
        return allBedrock ? HoleSafety.SAFE : HoleSafety.UNSAFE;
    }

    public static Hole getSingleHole(BlockPos position, double height) {
        return getSingleHole(position, height, true);
    }

    public static Hole getSingleHole(BlockPos position, double height, boolean reachable) {
        if (!mc.level.getBlockState(position).getBlock().equals(Blocks.AIR)) return null;
        if (!mc.level.getBlockState(position.above()).getBlock().equals(Blocks.AIR) && reachable) return null;
        if (!mc.level.getBlockState(position.above().above()).getBlock().equals(Blocks.AIR) && reachable) return null;

        HoleSafety safety = classifyWalls(position, singleOffsets);
        if (safety == null) return null;

        return new Hole(new AABB(position.getX(), position.getY(), position.getZ(), position.getX() + 1, position.getY() + height, position.getZ() + 1), HoleType.SINGLE, safety);
    }

    public static Hole getDoubleHole(BlockPos position, double height) {
        if (!mc.level.getBlockState(position).getBlock().equals(Blocks.AIR)) return null;
        if (!mc.level.getBlockState(position.above()).getBlock().equals(Blocks.AIR)) return null;
        if (!mc.level.getBlockState(position.above().above()).getBlock().equals(Blocks.AIR)) return null;

        boolean x = mc.level.getBlockState(position.offset(new Vec3i(1, 0, 0))).getBlock().equals(Blocks.AIR) && mc.level.getBlockState(position.offset(new Vec3i(1, 0, 0)).above()).getBlock().equals(Blocks.AIR) && mc.level.getBlockState(position.offset(new Vec3i(1, 0, 0)).above().above()).getBlock().equals(Blocks.AIR);
        boolean z = mc.level.getBlockState(position.offset(new Vec3i(0, 0, 1))).getBlock().equals(Blocks.AIR) && mc.level.getBlockState(position.offset(new Vec3i(0, 0, 1)).above()).getBlock().equals(Blocks.AIR) && mc.level.getBlockState(position.offset(new Vec3i(0, 0, 1)).above().above()).getBlock().equals(Blocks.AIR);

        if (!x && !z) return null;

        AABB box = null;
        HoleSafety safety = null;

        if (x) {
            HoleSafety s = classifyWalls(position, doubleXOffsets);
            if (s != null) {
                box = new AABB(position.getX(), position.getY(), position.getZ(), position.getX() + 2, position.getY() + height, position.getZ() + 1);
                safety = s;
            }
        }

        if (z && box == null) {
            HoleSafety s = classifyWalls(position, doubleZOffsets);
            if (s != null) {
                box = new AABB(position.getX(), position.getY(), position.getZ(), position.getX() + 1, position.getY() + height, position.getZ() + 2);
                safety = s;
            }
        }

        if (box == null) return null;

        return new Hole(box, HoleType.DOUBLE, safety);
    }

    public static Hole getQuadHole(BlockPos position, double height) {
        if (!mc.level.getBlockState(position).getBlock().equals(Blocks.AIR)) return null;
        if (!mc.level.getBlockState(position.above()).getBlock().equals(Blocks.AIR)) return null;
        if (!mc.level.getBlockState(position.offset(new Vec3i(1, 0, 0))).getBlock().equals(Blocks.AIR)) return null;
        if (!mc.level.getBlockState(position.offset(new Vec3i(0, 0, 1))).getBlock().equals(Blocks.AIR)) return null;
        if (!mc.level.getBlockState(position.offset(new Vec3i(1, 0, 1))).getBlock().equals(Blocks.AIR)) return null;
        if (!mc.level.getBlockState(position.above().above()).getBlock().equals(Blocks.AIR)) return null;
        if (!mc.level.getBlockState(position.offset(new Vec3i(1, 0, 0)).above()).getBlock().equals(Blocks.AIR)) return null;
        if (!mc.level.getBlockState(position.offset(new Vec3i(1, 0, 0)).above().above()).getBlock().equals(Blocks.AIR)) return null;
        if (!mc.level.getBlockState(position.offset(new Vec3i(0, 0, 1)).above()).getBlock().equals(Blocks.AIR)) return null;
        if (!mc.level.getBlockState(position.offset(new Vec3i(0, 0, 1)).above().above()).getBlock().equals(Blocks.AIR)) return null;
        if (!mc.level.getBlockState(position.offset(new Vec3i(1, 0, 1)).above()).getBlock().equals(Blocks.AIR)) return null;
        if (!mc.level.getBlockState(position.offset(new Vec3i(1, 0, 1)).above().above()).getBlock().equals(Blocks.AIR)) return null;

        HoleSafety safety = classifyWalls(position, quadOffsets);
        if (safety == null) return null;

        return new Hole(new AABB(position.getX(), position.getY(), position.getZ(), position.getX() + 2, position.getY() + height, position.getZ() + 2), HoleType.QUAD, safety);
    }

    public record Hole(AABB box, HoleType type, HoleSafety safety) {}
    public enum HoleType { SINGLE, DOUBLE, QUAD }
    public enum HoleSafety { SAFE, UNSAFE, NONE }
}

