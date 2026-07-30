package eu.client.utils.minecraft;

import eu.client.utils.IMinecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public class PositionUtils implements IMinecraft {
    public static BlockPos getFlooredPosition(Entity entity) {
        return new BlockPos(entity.getBlockX(), Mth.floor(((entity.getY() - Math.floor(entity.getY())) > 0.8) ? (Math.floor(entity.getY()) + 1.0) : Math.floor(entity.getY())), entity.getBlockZ());
    }

    public static AABB getRadius(Entity entity, double radius) {
        return new AABB(Mth.floor(entity.getX() - radius), Mth.floor(entity.getY() - radius), Mth.floor(entity.getZ() - radius), Mth.floor(entity.getX() + radius), Mth.floor(entity.getY() + radius), Mth.floor(entity.getZ() + radius));
    }

    public static AABB extrapolate(Player entity, int ticks) {
        if (entity == null) return null;

        double deltaX = entity.getX() - entity.xo;
        double deltaZ = entity.getZ() - entity.zo;

        double motionX = 0;
        double motionZ = 0;

        for (double i = 1; i <= ticks; i = i + 0.5) {
            if (mc.level.noCollision(entity, entity.getBoundingBox().move(new Vec3(deltaX * i, 0, deltaZ * i)))) {
                motionX = deltaX * i;
                motionZ = deltaZ * i;
            } else {
                break;
            }
        }

        Vec3 vec3d = new Vec3(motionX, 0, motionZ);
        if (vec3d == null) return null;

        return entity.getBoundingBox().move(vec3d);
    }
}
