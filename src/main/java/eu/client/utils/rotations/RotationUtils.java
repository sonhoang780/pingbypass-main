package eu.client.utils.rotations;

import eu.client.utils.IMinecraft;
import eu.client.utils.system.MathUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public class RotationUtils implements IMinecraft {
    public static float[] getRotations(Entity entity) {
        return getRotations(entity.getX(), entity.getY() + entity.getEyeHeight(entity.getPose()) / 2.0, entity.getZ());
    }

    public static float[] getRotations(Vec3 vec3d) {
        return getRotations(vec3d.x, vec3d.y, vec3d.z);
    }

    public static float[] getRotations(double x, double y, double z) {
        return getRotations(mc.player, x, y, z);
    }

    public static float[] getRotations(Entity entity, double x, double y, double z) {
        Vec3 vec3d = entity.position().add(0, entity.getEyeHeight(entity.getPose()), 0);

        double deltaX = x - vec3d.x;
        double deltaY = (y - vec3d.y) * -1.0;
        double deltaZ = z - vec3d.z;

        double distance = Mth.sqrt((float) (deltaX * deltaX + deltaZ * deltaZ));

        float yaw = (float) Mth.wrapDegrees(Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0);
        float pitch = (float) Mth.clamp(Mth.wrapDegrees(Math.toDegrees(Math.atan2(deltaY, distance))), -90f, 90f);

        return new float[]{yaw + (((float) Math.random() - 0.5f) * 4), pitch + (((float) Math.random() - 0.5f) * 4)};
    }

    public static float[] getRotations(Direction direction) {
        return switch (direction) {
            case DOWN -> new float[]{mc.player.getYRot(), 90.0f};
            case UP -> new float[]{mc.player.getYRot(), -90.0f};
            case NORTH -> new float[]{180.0f, mc.player.getXRot()};
            case SOUTH -> new float[]{0.0f, mc.player.getXRot()};
            case WEST -> new float[]{90.0f, mc.player.getXRot()};
            case EAST -> new float[]{-90.0f, mc.player.getXRot()};
        };
    }
}
