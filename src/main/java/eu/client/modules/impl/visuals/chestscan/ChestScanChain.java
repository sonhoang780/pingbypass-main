package eu.client.modules.impl.visuals.chestscan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public final class ChestScanChain {

    private ChestScanChain() {}

    public static Map<BlockPos, BlockPos> findEdges(Level level, Set<BlockPos> trackedChests, BlockPos center, int radiusBlocks) {
        Map<BlockPos, BlockPos> edges = new HashMap<>();
        double radiusSq = (double) radiusBlocks * radiusBlocks;

        Deque<BlockPos> frontier = new ArrayDeque<>(trackedChests);
        Set<BlockPos> visited = new HashSet<>(trackedChests);

        while (!frontier.isEmpty()) {
            BlockPos chestPos = frontier.poll();
            if (chestPos.distSqr(center) > radiusSq) continue;

            BlockPos belowPos = chestPos.below();
            BlockState belowState = level.getBlockState(belowPos);
            if (belowState.getBlock() instanceof HopperBlock) {
                Direction facing = belowState.getValue(HopperBlock.FACING);
                BlockPos dest = belowPos.relative(facing);
                if (isChest(level.getBlockState(dest))) {
                    edges.put(chestPos, dest);
                    if (visited.add(dest)) frontier.add(dest);
                }
            }

            for (Direction dir : Direction.values()) {
                BlockPos hopperPos = chestPos.relative(dir);
                BlockState hopperState = level.getBlockState(hopperPos);
                if (!(hopperState.getBlock() instanceof HopperBlock)) continue;
                if (hopperState.getValue(HopperBlock.FACING) != dir.getOpposite()) continue;
                BlockPos sourcePos = hopperPos.above();
                if (isChest(level.getBlockState(sourcePos))) {
                    edges.put(sourcePos, chestPos);
                    if (visited.add(sourcePos)) frontier.add(sourcePos);
                }
            }
        }
        return edges;
    }

    private static boolean isChest(BlockState state) {
        return state.getBlock() instanceof ChestBlock;
    }

    public static Set<BlockPos> inferEmpty(Map<BlockPos, BlockPos> edges, Map<BlockPos, ChestScanStore.ChestStatus> realStatuses) {
        Map<BlockPos, List<BlockPos>> reverse = new HashMap<>();
        for (Map.Entry<BlockPos, BlockPos> e : edges.entrySet()) {
            reverse.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }

        Set<BlockPos> inferred = new HashSet<>();
        Set<BlockPos> visited = new HashSet<>();
        for (Map.Entry<BlockPos, ChestScanStore.ChestStatus> e : realStatuses.entrySet()) {
            if (e.getValue() == ChestScanStore.ChestStatus.FULL) continue;
            BlockPos start = e.getKey();
            if (!visited.add(start)) continue;

            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                BlockPos cur = queue.poll();
                for (BlockPos parent : reverse.getOrDefault(cur, List.of())) {
                    if (!visited.add(parent)) continue;
                    if (!realStatuses.containsKey(parent)) inferred.add(parent);
                    queue.add(parent);
                }
            }
        }
        return inferred;
    }
}