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

        // RESTORED 2026-08-14 -- bản gốc 1.21.4 offsets yaw AND pitch by (Math.random() - 0.5) * 4,
        // i.e. +-2 degrees of uniform noise, on EVERY rotation it computes. The port had deleted it.
        // This is not cosmetic for Rotate=Normal: without it calculateRotations() returns the SAME
        // yaw every tick a target holds still, so LocalPlayer.sendPosition()'s own
        // `dYaw = getYRot() - yRotLast` comes out 0 and vanilla sends a Pos-ONLY packet -- the
        // reported rotation stops being refreshed on the wire for the entire hold, while local
        // physics keeps running at the real yaw. With the noise every tick differs, so PosRot goes
        // out every tick exactly as it does in bản gốc. Restored verbatim.
        //
        // Known trade-off, kept as a note rather than a divergence: fresh noise per call also
        // defeats RotationManager.packetRotate's `if (serverYaw == yaw && serverPitch == pitch)
        // return;` dedup, which measurably capped Rotate=Packet/MovementSync at 3-7 CPS on a NATIVE
        // 26.1.2 connection (the same 1.20.x ViaFabricPlus connection was unaffected in every mode).
        // bản gốc has that same property and is the reference being matched.
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
