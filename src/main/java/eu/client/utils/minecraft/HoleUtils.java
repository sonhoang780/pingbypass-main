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

public class HoleUtils implements IMinecraft {
    private static final Vec3i[] holeOffsets = new Vec3i[]{new Vec3i(0, -1, 0), new Vec3i(1, 0, 0), new Vec3i(-1, 0,0), new Vec3i(0, 0, 1), new Vec3i(0, 0, -1)};
    private static final Vec3i[] fullTrapOffsets = new Vec3i[]{new Vec3i(1, 1, 0), new Vec3i(0, 1, 1), new Vec3i(-1, 1, 0), new Vec3i(0, 1, -1), new Vec3i(1, 2, 0), new Vec3i(0, 2, 0)};

    private static final Vec3i[] singleOffsets = {new Vec3i(-1, 0, 0), new Vec3i(1, 0, 0), new Vec3i(0, 0, -1), new Vec3i(0, 0, 1), new Vec3i(0, -1, 0)};
    private static final Vec3i[] doubleXOffsets = {new Vec3i(-1, 0, 0), new Vec3i(0, 0, -1), new Vec3i(0, 0, 1), new Vec3i(0, -1, 0), new Vec3i(2, 0, 0), new Vec3i(1, 0, -1), new Vec3i(1, 0, 1), new Vec3i(1, -1, 0)};
    private static final Vec3i[] doubleZOffsets = {new Vec3i(0, 0, -1), new Vec3i(-1, 0, 0), new Vec3i(1, 0, 0), new Vec3i(0, -1, 0), new Vec3i(0, 0, 2), new Vec3i(-1, 0, 1), new Vec3i(1, 0, 1), new Vec3i(0, -1, 1)};
    private static final Vec3i[] quadOffsets = {new Vec3i(-1, 0, 0), new Vec3i(0, 0, -1), new Vec3i(0, -1, 0), new Vec3i(2, 0, 0), new Vec3i(1, 0, -1), new Vec3i(1, -1, 0), new Vec3i(-1, 0, 1), new Vec3i(0, 0, 2), new Vec3i(0, -1, 1), new Vec3i(2, 0, 1), new Vec3i(1, 0, 2), new Vec3i(1, -1, 1)};

    public static boolean isPlayerInHole(Player player) {
        return HoleUtils.getFeetPositions(player, true, true, false).stream().noneMatch(position -> mc.level.getBlockState(position).canBeReplaced());
    }

    public static List<BlockPos> getInsidePositions(Entity targetEntity) {
        List<BlockPos> targetPositions = new ArrayList<>();
        BlockPos targetPosition = PositionUtils.getFlooredPosition(targetEntity);

        for(Vec3i vec3i : holeOffsets) {
            if (!(vec3i.getY() < targetPosition.getY())) continue;
            BlockPos offsetPosition = targetPosition.offset(vec3i);

            List<Entity> collidingEntities = mc.level.getEntities((Entity) null, new AABB(offsetPosition), entity -> true).stream().filter(entity -> entity == targetEntity).toList();
            if (collidingEntities.isEmpty()) continue;

            AABB box = collidingEntities.getFirst().getBoundingBox();

            for (int x = (int) Math.floor(box.minX); x < Math.ceil(box.maxX); x++) {
                for (int z = (int) Math.floor(box.minZ); z < Math.ceil(box.maxZ); z++) {
                    BlockPos pos = new BlockPos(x, targetPosition.getY(), z);
                    if(!targetPositions.contains(pos)) targetPositions.add(pos);
                }
            }
        }
        if(targetPositions.isEmpty()) targetPositions.add(targetPosition);

        return targetPositions;
    }

    public static HashSet<BlockPos> getFeetPositions(Player target, boolean extension, boolean floor, boolean targetOnly) {
        HashSet<BlockPos> positions = new HashSet<>();
        HashSet<BlockPos> blacklist = new HashSet<>();

        HitboxDesyncModule hitboxDesyncModule = EUClient.MODULE_MANAGER.getModule(HitboxDesyncModule.class);

        BlockPos feetPos = PositionUtils.getFlooredPosition(target);
        blacklist.add(feetPos);

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

    public static List<BlockPos> getTrapPositions(Player player, boolean partial, boolean head, boolean antiStep, boolean antiBomb, boolean strictDirection) {
        List<BlockPos> positions = new ArrayList<>();
        BlockPos position = PositionUtils.getFlooredPosition(player);

        if (antiStep) {
            positions.add(position.offset(1, 2, 0));
            positions.add(position.offset(-1, 2, 0));
            positions.add(position.offset(0, 2, 1));
            positions.add(position.offset(0, 2, -1));
        }

        if (antiBomb) {
            positions.add(position.offset(0, 3, 0));
        }

        if (partial) {
            BlockPos headPosition = position.offset(0, 2, 0);
            if (WorldUtils.getDirection(headPosition, strictDirection) != null) {
                positions.add(headPosition);
                return positions;
            }

            Vec3i[] offsets = new Vec3i[]{new Vec3i(1, 1, 0), new Vec3i(1, 2, 0), new Vec3i(0, 2, 0)};
            for (Vec3i vec3i : offsets) positions.add(position.offset(vec3i));
        } else {
            for (Vec3i vec3i : fullTrapOffsets) {
                if(!head && vec3i.getY()==2) continue;
                positions.add(position.offset(vec3i));
            }
        }

        return positions;
    }

    // Loosened (2026-08-06): used to hard-reject a hole entirely unless EVERY wall was one of the
    // 4 classic blast-resistant blocks (bedrock/obsidian/respawn anchor/ender chest) -- fine for
    // PvP-safe crystal holing, but far too strict for general "walk into a hole" use (example-addon's
    // own HoleSnap port only ever required a wall to be solid at all, isBlock(): non-empty collision
    // shape, no block-type restriction). Now any solid block counts as a valid wall; the safety
    // classification (still BEDROCK/OBSIDIAN/MIXED when every wall happens to qualify, same as
    // before) downgrades to NONE the moment any wall is just an ordinary solid block, instead of
    // rejecting the whole hole.
    private static HoleSafety classifyWalls(BlockPos position, Vec3i[] offsets) {
        HoleSafety safety = null;
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

            if (bedrock) {
                if (safety == HoleSafety.OBSIDIAN) safety = HoleSafety.MIXED;
                else if (safety != HoleSafety.MIXED) safety = HoleSafety.BEDROCK;
            } else {
                if (safety == HoleSafety.BEDROCK) safety = HoleSafety.MIXED;
                else if (safety != HoleSafety.MIXED) safety = HoleSafety.OBSIDIAN;
            }
        }

        if (!allBlastProof) return HoleSafety.NONE;
        return safety == null ? HoleSafety.OBSIDIAN : safety;
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
    public enum HoleSafety { OBSIDIAN, MIXED, BEDROCK, NONE }
}

