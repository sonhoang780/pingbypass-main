package eu.client.modules.impl.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import eu.client.utils.IMinecraft;

public class CrystalPlacementHelper implements IMinecraft {
    
    public static class PlacementResult {
        public Direction direction;
        public Vec3 hitVec;
        
        public PlacementResult(Direction direction, Vec3 hitVec) {
            this.direction = direction;
            this.hitVec = hitVec;
        }
    }

    public static PlacementResult getVisiblePlacement(BlockPos position) {
        if (mc.player == null || mc.level == null) return new PlacementResult(Direction.UP, net.minecraft.world.phys.Vec3.atCenterOf(position).add(0, 0.5, 0));
        
        Vec3 eye = mc.player.getEyePosition();
        
        Direction[] faces = new Direction[]{Direction.UP, Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST, Direction.DOWN};
        double[] offsets = new double[]{0.5, 0.1, 0.9, 0.3, 0.7};
        
        // 1. Strict Line of Sight Check (30 points)
        for (Direction face : faces) {
            for (double ox : offsets) {
                for (double oy : offsets) {
                    Vec3 point = getPointOnFace(position, face, ox, oy);
                    BlockHitResult result = mc.level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
                    
                    if (result != null && result.getType() == HitResult.Type.BLOCK && result.getBlockPos().equals(position)) {
                        return new PlacementResult(result.getDirection(), result.getLocation());
                    }
                }
            }
        }
        
        // 2. Fallback: 100% Eclipsed
        // Find the CLOSEST face that is EXPOSED (not covered by a solid block).
        Direction closestExposed = null;
        double minDistance = Double.MAX_VALUE;
        
        for (Direction face : faces) {
            // A face is exposed if the adjacent block doesn't completely block interaction.
            // For safety, we check if the adjacent block is air, liquid, or replaceable.
            BlockPos adjacent = position.relative(face);
            net.minecraft.world.level.block.state.BlockState state = mc.level.getBlockState(adjacent);
            
            // If it's a full solid block (cannot be replaced), we can't click this face!
            if (!state.canBeReplaced()) {
                continue; // Face is covered
            }
            
            Vec3 hitVec = getPointOnFace(position, face, 0.5, 0.5); // Center of the face
            double dist = eye.distanceToSqr(hitVec);
            
            if (dist < minDistance) {
                minDistance = dist;
                closestExposed = face;
            }
        }
        
        if (closestExposed != null) {
            return new PlacementResult(closestExposed, getPointOnFace(position, closestExposed, 0.5, 0.5));
        }
        
        // Ultimate fallback (should never happen for valid crystals since UP is always exposed to air)
        return new PlacementResult(Direction.UP, net.minecraft.world.phys.Vec3.atCenterOf(position).add(0, 0.5, 0));
    }
    
    private static Vec3 getPointOnFace(BlockPos pos, Direction face, double off1, double off2) {
        double x = pos.getX();
        double y = pos.getY();
        double z = pos.getZ();
        
        switch (face) {
            case UP: return new Vec3(x + off1, y + 1.0, z + off2);
            case DOWN: return new Vec3(x + off1, y + 0.0, z + off2);
            case SOUTH: return new Vec3(x + off1, y + off2, z + 1.0);
            case NORTH: return new Vec3(x + off1, y + off2, z + 0.0);
            case EAST: return new Vec3(x + 1.0, y + off1, z + off2);
            case WEST: return new Vec3(x + 0.0, y + off1, z + off2);
        }
        return new Vec3(x + 0.5, y + 0.5, z + 0.5);
    }
}
