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

    public static double getYRotToVec(Entity entity, Vec3 vec) {
        double dx = vec.x - entity.getX();
        double dz = vec.z - entity.getZ();
        return Mth.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    public static double getXRotToVec(Entity entity, Vec3 vec) {
        double dx = vec.x - entity.getX();
        double dy = vec.y - (entity.getY() + entity.getEyeHeight());
        double dz = vec.z - entity.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        return Mth.clamp(Mth.wrapDegrees(-Math.toDegrees(Math.atan2(dy, dist))), -90.0, 90.0);
    }

    public static float[] getExactRotations(Entity entity, Vec3 target) {
        return new float[]{(float) getYRotToVec(entity, target), (float) getXRotToVec(entity, target)};
    }

    public static float getYRotToVec(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return (float) Mth.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    public static float getXRotToVec(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        return (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
    }

    public static Vec3 getClosestPointToEye(Vec3 eyePos, net.minecraft.world.phys.AABB box) {
        double x = eyePos.x;
        double y = eyePos.y;
        double z = eyePos.z;

        final double VEC = 1.0 / 16.0;
        final double EPS = 1e-9;

        if (eyePos.x < box.minX) x = box.minX;
        else if (eyePos.x > box.maxX) x = box.maxX;

        if (eyePos.y < box.minY) y = box.minY;
        else if (eyePos.y > box.maxY) y = box.maxY;

        if (eyePos.z < box.minZ) z = box.minZ;
        else if (eyePos.z > box.maxZ) z = box.maxZ;

        if (Math.abs(x - box.minX) < EPS) {
            x = Math.min(box.minX + VEC, box.maxX - EPS);
        } else if (Math.abs(x - box.maxX) < EPS) {
            x = Math.max(box.maxX - VEC, box.minX + EPS);
        }

        if (Math.abs(z - box.minZ) < EPS) {
            z = Math.min(box.minZ + VEC, box.maxZ - EPS);
        } else if (Math.abs(z - box.maxZ) < EPS) {
            z = Math.max(box.maxZ - VEC, box.minZ + EPS);
        }

        return new Vec3(x, y, z);
    }

    public static Vec3 getClampClosestPoint(Vec3 eyePos, net.minecraft.world.phys.AABB box) {
        double x = Mth.clamp(eyePos.x, box.minX, box.maxX);
        double y = Mth.clamp(eyePos.y, box.minY, box.maxY);
        double z = Mth.clamp(eyePos.z, box.minZ, box.maxZ);
        return new Vec3(x, y, z);
    }

    public static Vec3 getLookVectorFromYRotXRot(float yRot, float xRot) {
        float f = xRot * ((float) Math.PI / 180F);
        float f1 = -yRot * ((float) Math.PI / 180F);
        float f2 = Mth.cos(f1);
        float f3 = Mth.sin(f1);
        float f4 = Mth.cos(f);
        float f5 = Mth.sin(f);
        return new Vec3(f3 * f4, -f5, f2 * f4);
    }

    public static net.minecraft.world.phys.EntityHitResult raycastTarget(Vec3 eyePos, Entity target, double reach, float yRot, float xRot) {
        Vec3 look = getLookVectorFromYRotXRot(yRot, xRot);
        Vec3 reachEnd = eyePos.add(look.scale(reach));
        net.minecraft.world.phys.AABB targetBox = target.getBoundingBox();
        if (targetBox.clip(eyePos, reachEnd).isPresent()) {
            return new net.minecraft.world.phys.EntityHitResult(target);
        }
        return null;
    }
}
