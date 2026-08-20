package eu.client.modules.impl.combat;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.AttackEntityEvent;
import eu.client.events.impl.PacketSendEvent;
import eu.client.mixins.accessors.ClientPlayerEntityAccessor;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

@RegisterModule(name = "Criticals", description = "Changes player movement for always landing critical hits.", category = Module.Category.COMBAT, proxyEnhanced = true)
public class CriticalsModule extends Module {
    public ModeSetting mode = new ModeSetting("Mode", "The method that will be used to achieve critical hits.", "Packet", new String[]{"Packet", "Grim", "GrimNew"});
    public BooleanSetting onlyPhased = new BooleanSetting("OnlyPhased", "Only crits when you are phased into blocks.", new ModeSetting.Visibility(mode, "Grim", "GrimNew"), true);
    public BooleanSetting onlyStandingStill = new BooleanSetting("OnlyStandingStill", "Only crits when you are standing still.", new ModeSetting.Visibility(mode, "Grim", "GrimNew"), true);
    public BooleanSetting onlyWhenHeadCovered = new BooleanSetting("HeadCovered", "Only crits when there is a block covering your head.", new ModeSetting.Visibility(mode, "Grim", "GrimNew"), true);

    private long lastCritTime = 0L;

    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (shouldRunOnProxy()) return;
        if (mc.player == null || mc.level == null) return;
        if (mc.player.isFallFlying() || mc.player.isInWater() || mc.player.isInLava() || mc.player.isSuppressingSlidingDownLadder() || mc.player.hasEffect(MobEffects.BLINDNESS)) {
            return;
        }

        Entity target = event.getTarget();
        if (!(target instanceof LivingEntity living) || !living.isAlive() || target instanceof EndCrystal || target instanceof ItemFrame) return;

        if (mc.player.isHandsBusy()) {
            ridingAttack(target);
            return;
        }

        onGroundAttack();
        mc.player.crit(target);
    }

    @SubscribeEvent
    public void onPacketSend(PacketSendEvent event) {
        if (shouldRunOnProxy()) return;
        if (event.getPacket() instanceof ServerboundAttackPacket) {
            long now = System.currentTimeMillis();
            if (now - lastCritTime < 50L) return; // Prevent duplicate trigger in same attack
            if (mc.player == null || mc.level == null) return;
            if (mc.player.isFallFlying() || mc.player.isInWater() || mc.player.isInLava() || mc.player.isSuppressingSlidingDownLadder() || mc.player.hasEffect(MobEffects.BLINDNESS)) {
                return;
            }

            onGroundAttack();
        }
    }

    private void ridingAttack(Entity target) {
        if (mode.getValue().equalsIgnoreCase("Packet")) {
            for (int i = 0; i < 5; i++) {
                mc.getConnection().send(new ServerboundAttackPacket(target.getId()));
                mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
            }
        }
    }

    private void onGroundAttack() {
        lastCritTime = System.currentTimeMillis();
        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();

        eu.client.pingbypass.server.ProxyServerTickListener.allowSend(() -> {
            switch (mode.getValue()) {
                case "Packet" -> packetCrit(x, y, z);
                case "Grim" -> grimCrit(x, y, z);
                case "GrimNew" -> grimNewCrit(x, y, z);
            }
        });

        ((ClientPlayerEntityAccessor) mc.player).setLastOnGround(false);
    }

    private void packetCrit(double x, double y, double z) {
        if (!mc.player.onGround()) return;

        mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(x, y + 0.0625, z, false, mc.player.horizontalCollision));
        mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(x, y, z, false, mc.player.horizontalCollision));
    }

    private void grimCrit(double x, double y, double z) {
        if (!mc.player.onGround()) return;

        if (onlyPhased.getValue() && !isPhased(mc.player)) return;

        if (onlyStandingStill.getValue() && (Math.abs(mc.player.getDeltaMovement().x) > 0.01 || Math.abs(mc.player.getDeltaMovement().y) > 0.01 || Math.abs(mc.player.getDeltaMovement().z) > 0.01)) {
            return;
        }

        if (onlyWhenHeadCovered.getValue()) {
            BlockPos pos = mc.player.blockPosition();
            BlockPos target = mc.player.isVisuallyCrawling() ? pos.above(1) : pos.above(2);
            if (mc.level.getBlockState(target).isAir()) return;
        }

        float yaw = EUClient.ROTATION_MANAGER.getServerYaw();
        float pitch = EUClient.ROTATION_MANAGER.getServerPitch();

        mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(x, y + 0.0625, z, yaw, pitch, false, mc.player.horizontalCollision));
        mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(x, y + 0.0625013579, z, yaw, pitch, false, mc.player.horizontalCollision));
        mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(x, y + 1.3579e-6, z, yaw, pitch, false, mc.player.horizontalCollision));
    }

    private void grimNewCrit(double x, double y, double z) {
        if (!mc.player.onGround()) return;

        if (onlyPhased.getValue() && (!isPhased(mc.player) || !eyesPhased(mc.player))) return;

        if (onlyStandingStill.getValue() && (Math.abs(mc.player.getDeltaMovement().x) > 0.01 || Math.abs(mc.player.getDeltaMovement().y) > 0.01 || Math.abs(mc.player.getDeltaMovement().z) > 0.01)) {
            return;
        }

        if (onlyWhenHeadCovered.getValue()) {
            BlockPos pos = mc.player.blockPosition();
            BlockPos target = mc.player.isVisuallyCrawling() ? pos.above(1) : pos.above(2);
            if (mc.level.getBlockState(target).isAir()) return;
        }

        float yaw = EUClient.ROTATION_MANAGER.getServerYaw();
        float pitch = EUClient.ROTATION_MANAGER.getServerPitch();

        float f = (float) ((Math.random() * 2.0 - 1.0) * 0.001f);
        float f2 = Mth.clamp(pitch + f, -90.0F, 90.0F);

        // Author: cattyngmd
        mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(x, y + 0.0626, z, yaw, f2, false, mc.player.horizontalCollision));
        mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(x, y + 0.0455, z, yaw, f2, false, mc.player.horizontalCollision));
    }

    private boolean isPhased(Entity e) {
        if (e == null || mc.level == null) return false;
        AABB box = e.getBoundingBox();
        int minX = Mth.floor(box.minX);
        int maxX = Mth.ceil(box.maxX);
        int minY = Mth.floor(box.minY);
        int maxY = Mth.ceil(box.maxY);
        int minZ = Mth.floor(box.minZ);
        int maxZ = Mth.ceil(box.maxZ);

        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    VoxelShape shape = mc.level.getBlockState(pos).getCollisionShape(mc.level, pos);
                    if (!shape.isEmpty() && shape.bounds().move(pos).intersects(box)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean eyesPhased(Player player) {
        Vec3 eyePos = player.getEyePosition();
        BlockPos pos = BlockPos.containing(eyePos);
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return false;
        VoxelShape shape = state.getCollisionShape(mc.level, pos);
        if (shape.isEmpty()) return false;

        for (AABB box : shape.toAabbs()) {
            if (box.move(pos).contains(eyePos)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getMetaData() {
        return mode.getValue();
    }
}
