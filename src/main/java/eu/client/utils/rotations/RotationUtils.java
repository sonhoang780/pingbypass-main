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

        // Was: yaw/pitch each offset by (Math.random() - 0.5) * 4, i.e. +-2 degrees of uniform noise
        // on EVERY rotation this project computes. Inherited from eu-client with no setting, no
        // comment and no mechanism behind it; Shoreline's equivalent (RotationUtil.getRotationTo)
        // returns the exact angles. Deleted, for two concrete reasons:
        //
        //  1. It defeated RotationManager.packetRotate's own dedup outright. That method opens with
        //     `if (serverYaw == yaw && serverPitch == pitch) return;` -- with fresh noise on every
        //     call that comparison is never true, so a standing-still player attacking the same
        //     crystal position fired a brand new ServerboundMovePlayerPacket.Rot on every single
        //     attack/place instead of none at all. On 1.21.2+ the client also delimits its ticks
        //     with ServerboundClientTickEndPacket, so those extra rotation packets land inside a
        //     tick the server can actually see is already accounted for -- on a 1.20.x connection
        //     (ViaFabricPlus strips that marker) they are indistinguishable from normal aim.
        //  2. The reported yaw stopped landing on the client's own sensitivity grid. Vanilla yaw
        //     only ever moves in mouse-sensitivity-derived increments; uniform noise does not, and
        //     that is exactly the signal rotation checks key off.
        //
        // Matches the observation this came from: every Rotate mode (Normal/Packet/MovementSync)
        // routes through here and was capped at 3-7 CPS on native protocol, Rotate=None -- the only
        // mode that never calls this -- was faster, and the same 1.20.x connection was unaffected
        // in every mode.
        return new float[]{yaw, pitch};
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
