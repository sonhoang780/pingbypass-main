package eu.client.modules.impl.movement;

import eu.client.EUClient;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.modules.impl.player.MultiTaskModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.utils.minecraft.InventoryUtils;
import eu.client.utils.minecraft.NetworkUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

@RegisterModule(name = "Phase", description = "Throws an ender pearl to phase/clip into the block under your feet.", category = Module.Category.MOVEMENT)
public class PhaseModule extends Module {
    public ModeSetting swap = new ModeSetting("Swap", "The mode that will be used for switching to the pearl before throwing it.", "Silent", InventoryUtils.SWITCH_MODES);
    public BooleanSetting crawl = new BooleanSetting("Crawl", "Aims at the exact block underfoot instead of playerY-0.5 -- needed to phase while in the crawling pose, where the lower eye height throws a standing-pose target off.", false);
    public BooleanSetting debug = new BooleanSetting("Debug", "Chat-prints pos/yaw/pitch/target for every throw, so a failed (popped-up) attempt can be matched back to its exact numbers.", false);

    public BooleanSetting fireCharge = new BooleanSetting("FireCharge", "Uses fire to bypass pearl distance checks.", false);
    public BooleanSetting alternative = new BooleanSetting("Alternative", "Uses a flint and steel rather than a fire charge to perform the phase bypass.", false);
    public ModeSetting fireSwitch = new ModeSetting("FireSwitch", "The mode that will be used for switching to a fire charge.", new BooleanSetting.Visibility(fireCharge, true), "Silent", new String[]{"Normal", "Silent", "AltPickup", "AltSwap"});
    public BooleanSetting autoRemove = new BooleanSetting("AutoRemove", "Automatically removes the fire once you have phased.", new BooleanSetting.Visibility(fireCharge, true), true);

    private static final double CORNER_THRESHOLD = 0.5;
    private static final double CORNER_OFFSET = 0.5;

    private int attempt = 0;

    // [THÊM MỚI] Hàm quét Hitbox kiểm tra Cobweb cực kỳ chắc chắn
    private boolean isInWeb(net.minecraft.world.entity.Entity entity) {
        // Nhanh: block ngay tại vị trí
        if (entity.getInBlockState().is(Blocks.COBWEB)) return true;
        
        // Kỹ hơn: quét mọi block giao với hitbox để bắt cả trường hợp đứng ở mép
        for (BlockPos pos : BlockPos.betweenClosed(
                BlockPos.containing(entity.getBoundingBox().minX, entity.getBoundingBox().minY, entity.getBoundingBox().minZ),
                BlockPos.containing(entity.getBoundingBox().maxX, entity.getBoundingBox().maxY, entity.getBoundingBox().maxZ))) {
            if (entity.level().getBlockState(pos).is(Blocks.COBWEB)) return true;
        }
        return false;
    }

    @Override
    public void onEnable() {
        if (mc.player == null || mc.level == null) {
            setToggled(false);
            return;
        }

        for (BlockPos scaffoldPosition : new BlockPos[]{mc.player.blockPosition(), mc.player.blockPosition().below()}) {
            if (!mc.level.getBlockState(scaffoldPosition).is(Blocks.SCAFFOLDING)) continue;

            NetworkUtils.sendSequencedPacket(sequence -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, scaffoldPosition, Direction.UP, sequence));
            NetworkUtils.sendSequencedPacket(sequence -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, scaffoldPosition, Direction.UP, sequence));
            mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
            mc.level.removeBlock(scaffoldPosition, false);
        }

        boolean swimPose = mc.player.getPose() == Pose.SWIMMING;
        if (!crawl.getValue() && swimPose) {
            setToggled(false);
            return;
        }

        boolean crawlSwimStuck = crawl.getValue() && swimPose;
        
        // [ĐÃ SỬA] Sử dụng hàm quét Hitbox thay vì blockPosition() tĩnh
        boolean inCobweb = isInWeb(mc.player);
        if (!crawlSwimStuck && !inCobweb && !mc.level.getBlockState(mc.player.blockPosition()).canBeReplaced()) {
            setToggled(false);
            return;
        }

        if (mc.player.isCrouching()) {
            setToggled(false);
            return;
        }

        if (mc.player.getCooldowns().isOnCooldown(new ItemStack(Items.ENDER_PEARL))) {
            setToggled(false);
            return;
        }

        MultiTaskModule multiTask = EUClient.MODULE_MANAGER.getModule(MultiTaskModule.class);
        boolean keepEating = multiTask != null && multiTask.isToggled() && multiTask.pearl.getValue() && mc.player.isUsingItem();
        String pearlMode = keepEating ? "AltSwap" : swap.getValue();

        int slot = InventoryUtils.find(Items.ENDER_PEARL, 0,
        (pearlMode.equalsIgnoreCase("AltSwap") || pearlMode.equalsIgnoreCase("AltPickup")) ? 35 : 8);
        int previousSlot = mc.player.getInventory().getSelectedSlot();
        boolean didFireCharge = false;

        if (slot == -1) {
            EUClient.CHAT_MANAGER.tagged("No pearls could be found in your hotbar.", getName());
            setToggled(false);
            return;
        }

        float prevYaw = mc.player.getYRot();
        float prevPitch = mc.player.getXRot();

        BlockPos downPosition = mc.player.blockPosition().below();

        float rawYaw = Mth.wrapDegrees(mc.player.getYRot());
        float mod90 = ((rawYaw % 90f) + 90f) % 90f;
        boolean nearCardinal = Math.min(mod90, 90f - mod90) < 22.5f;

        Vec3 target = nearCardinal ? boundaryTarget(rawYaw) : calculateTargetPos();

        float yaw = calcYaw(target);
        float pitch = solvePitch(target);

        if (debug.getValue()) {
            attempt++;
            EUClient.CHAT_MANAGER.tagged(String.format(
                    "#%d pos=(%.4f, %.4f, %.4f) yaw=%.2f pitch=%.2f target=(%.4f, %.4f, %.4f) sector=%s",
                    attempt, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    yaw, pitch, target.x, target.y, target.z,
                    nearCardinal ? "straight" : "corner"), getName());
        }

        if (fireCharge.getValue() && (mc.level.getBlockState(mc.player.blockPosition()).isAir() || inCobweb) && !(mc.level.getBlockState(downPosition).canBeReplaced())) {
            int chargeSlot = InventoryUtils.find(alternative.getValue() ? Items.FLINT_AND_STEEL : Items.FIRE_CHARGE, InventoryUtils.HOTBAR_START, fireSwitch.getValue().equalsIgnoreCase("AltSwap") || fireSwitch.getValue().equalsIgnoreCase("AltPickup") ? 35 : 8);

            if (chargeSlot != -1) {
                EUClient.ROTATION_MANAGER.packetRotate(yaw, 90);

                InventoryUtils.switchSlot(fireSwitch.getValue(), chargeSlot, previousSlot);
                NetworkUtils.sendSequencedPacket(sequence -> new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(downPosition).add(0, 1, 0), Direction.UP, downPosition, false), sequence));
                mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                InventoryUtils.switchBack(fireSwitch.getValue(), chargeSlot, previousSlot);

                didFireCharge = true;
            }
        }

        EUClient.ROTATION_MANAGER.packetRotate(yaw, pitch);

        InventoryUtils.switchSlot(pearlMode, slot, previousSlot);
        NetworkUtils.sendSequencedPacket(sequence -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, sequence, yaw, pitch));
        mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        InventoryUtils.switchBack(pearlMode, slot, previousSlot);
        
        if (didFireCharge && autoRemove.getValue()) {
            NetworkUtils.sendSequencedPacket(sequence -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, mc.player.blockPosition(), Direction.UP, sequence));
            NetworkUtils.sendSequencedPacket(sequence -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, mc.player.blockPosition(), Direction.UP, sequence));
            mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        }

        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(prevYaw, prevPitch, mc.player.onGround(), mc.player.horizontalCollision));

        setToggled(false);
    }

    private Vec3 calculateTargetPos() {
        double playerX = mc.player.getX();
        double playerZ = mc.player.getZ();
        double y = crawl.getValue() ? mc.player.blockPosition().below().getY() : mc.player.getY() - 0.5;

        double nearestIntX = Math.round(playerX);
        double nearestIntZ = Math.round(playerZ);
        double dxCorner = nearestIntX - playerX;
        double dzCorner = nearestIntZ - playerZ;

        if (Math.abs(dxCorner) <= CORNER_THRESHOLD && Math.abs(dzCorner) <= CORNER_THRESHOLD) {
            return new Vec3(
                    playerX + Mth.clamp(dxCorner, -CORNER_OFFSET, CORNER_OFFSET),
                    y,
                    playerZ + Mth.clamp(dzCorner, -CORNER_OFFSET, CORNER_OFFSET)
            );
        }

        final double A = Math.PI / 13;
        final double B = Math.PI / 4;

        double x = playerX + Mth.clamp(toClosest(playerX, Math.floor(playerX) + A, Math.floor(playerX) + B) - playerX, -0.2, 0.2);
        double z = playerZ + Mth.clamp(toClosest(playerZ, Math.floor(playerZ) + A, Math.floor(playerZ) + B) - playerZ, -0.2, 0.2);

        return new Vec3(x, y, z);
    }

    private double toClosest(double num, double min, double max) {
        return (num - min) > (max - num) ? max : min;
    }

    private Vec3 boundaryTarget(float yawDeg) {
        double px = mc.player.getX(), pz = mc.player.getZ();
        double y = crawl.getValue() ? mc.player.blockPosition().below().getY() : mc.player.getY() - 0.5;

        double rad = Math.toRadians(yawDeg);
        boolean zAxis = Math.abs(Math.cos(rad)) > Math.abs(Math.sin(rad));
        return zAxis ? new Vec3(px, y, Math.round(pz)) : new Vec3(Math.round(px), y, pz);
    }

    private float calcYaw(Vec3 target) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 diff = target.subtract(eye);
        return (float) Math.toDegrees(Math.atan2(-diff.x, diff.z));
    }

    private float solvePitch(Vec3 target) {
        Vec3 eye = mc.player.getEyePosition();
        double d = Math.hypot(target.x - eye.x, target.z - eye.z);
        double drop = eye.y - target.y;
        if (d < 1e-4 || drop <= 1e-4) return mc.player.getBlockY() > 4 ? 85f : 75f;

        double lo = 1.0, hi = 89.9;
        for (int i = 0; i < 30; i++) {
            double mid = (lo + hi) * 0.5;
            if (reach(mid, drop) > d) lo = mid; else hi = mid;
        }
        return (float) ((lo + hi) * 0.5);
    }

    private static double reach(double pitchDeg, double drop) {
        double p = Math.toRadians(pitchDeg);
        double vh = 1.5 * Math.cos(p), vy = -1.5 * Math.sin(p);
        double x = 0, y = 0;
        for (int i = 0; i < 200; i++) {
            double prevX = x, prevY = y;
            x += vh;
            y += vy;
            if (y <= -drop) {
                double f = (-drop - prevY) / (y - prevY); 
                return prevX + (x - prevX) * f;
            }
            vh *= 0.99;
            vy = vy * 0.99 - 0.03;
        }
        return x;
    }
}