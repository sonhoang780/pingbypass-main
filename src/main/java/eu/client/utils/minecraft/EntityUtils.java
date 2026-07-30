package eu.client.utils.minecraft;

import eu.client.EUClient;
import eu.client.modules.impl.miscellaneous.FakePlayerModule;
import eu.client.utils.IMinecraft;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.SplashPotionItem;
import net.minecraft.world.item.LingeringPotionItem;
import net.minecraft.world.item.Item;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.GameType;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class EntityUtils implements IMinecraft {
    public static Map<MobEffect, Color> POTION_COLORS = new HashMap<>();

    static {
        POTION_COLORS.put(MobEffects.SPEED.value(), new Color(124, 175, 198));
        POTION_COLORS.put(MobEffects.SLOWNESS.value(), new Color(90, 108, 129));
        POTION_COLORS.put(MobEffects.HASTE.value(), new Color(217, 192, 67));
        POTION_COLORS.put(MobEffects.MINING_FATIGUE.value(), new Color(74, 66, 23));
        POTION_COLORS.put(MobEffects.STRENGTH.value(), new Color(147, 36, 35));
        POTION_COLORS.put(MobEffects.INSTANT_HEALTH.value(), new Color(67, 10, 9));
        POTION_COLORS.put(MobEffects.INSTANT_DAMAGE.value(), new Color(67, 10, 9));
        POTION_COLORS.put(MobEffects.JUMP_BOOST.value(), new Color(34, 255, 76));
        POTION_COLORS.put(MobEffects.NAUSEA.value(), new Color(85, 29, 74));
        POTION_COLORS.put(MobEffects.REGENERATION.value(), new Color(205, 92, 171));
        POTION_COLORS.put(MobEffects.RESISTANCE.value(), new Color(153, 69, 58));
        POTION_COLORS.put(MobEffects.FIRE_RESISTANCE.value(), new Color(228, 154, 58));
        POTION_COLORS.put(MobEffects.WATER_BREATHING.value(), new Color(46, 82, 153));
        POTION_COLORS.put(MobEffects.INVISIBILITY.value(), new Color(127, 131, 146));
        POTION_COLORS.put(MobEffects.BLINDNESS.value(), new Color(31, 31, 35));
        POTION_COLORS.put(MobEffects.NIGHT_VISION.value(), new Color(31, 31, 161));
        POTION_COLORS.put(MobEffects.HUNGER.value(), new Color(88, 118, 83));
        POTION_COLORS.put(MobEffects.WEAKNESS.value(), new Color(72, 77, 72));
        POTION_COLORS.put(MobEffects.POISON.value(), new Color(78, 147, 49));
        POTION_COLORS.put(MobEffects.WITHER.value(), new Color(53, 42, 39));
        POTION_COLORS.put(MobEffects.HEALTH_BOOST.value(), new Color(248, 125, 35));
        POTION_COLORS.put(MobEffects.ABSORPTION.value(), new Color(37, 82, 165));
        POTION_COLORS.put(MobEffects.SATURATION.value(), new Color(248, 36, 35));
        POTION_COLORS.put(MobEffects.GLOWING.value(), new Color(148, 160, 97));
        POTION_COLORS.put(MobEffects.LEVITATION.value(), new Color(206, 255, 255));
        POTION_COLORS.put(MobEffects.LUCK.value(), new Color(51, 153, 0));
        POTION_COLORS.put(MobEffects.UNLUCK.value(), new Color(192, 164, 77));
    }

    public static boolean isBot(Player player) {
        if (EUClient.MODULE_MANAGER.getModule(FakePlayerModule.class).isToggled() && player == EUClient.MODULE_MANAGER.getModule(FakePlayerModule.class).getPlayer()) {
            return false;
        }

        PlayerInfo entry = mc.getConnection().getPlayerInfo(player.getUUID());
        return entry == null || entry.getProfile() == null || player.getUUID().toString().startsWith(player.getName().getString()) || !player.getGameProfile().name().equals(player.getName().getString());
    }

    public static int getLatency(Player player) {
        PlayerInfo playerListEntry = mc.getConnection().getPlayerInfo(player.getUUID());
        return playerListEntry == null ? 0 : playerListEntry.getLatency();
    }

    public static GameType getGameMode(Player player) {
        PlayerInfo playerListEntry = mc.getConnection().getPlayerInfo(player.getUUID());
        return playerListEntry == null ? GameType.CREATIVE : playerListEntry.getGameMode();
    }

    public static String getGameModeName(GameType gameMode) {
        return switch (gameMode) {
            case CREATIVE -> "C";
            case ADVENTURE -> "A";
            case SPECTATOR -> "SP";
            default -> "S";
        };
    }

    public static double getSpeed(Entity entity, SpeedUnit unit) {
        double speed = Math.sqrt(Mth.square(Math.abs(entity.getX() - entity.xo)) + Mth.square(Math.abs(entity.getZ() - entity.zo)));

        if (unit == SpeedUnit.KILOMETERS) return (speed * 3.6 * EUClient.WORLD_MANAGER.getTimerMultiplier()) * 20;
        else return speed / 0.05 * EUClient.WORLD_MANAGER.getTimerMultiplier();
    }

    public static boolean hasNegativeEffects(Player player) {
        for (MobEffectInstance statusEffectInstance : new ArrayList<>(player.getActiveEffects())) {
            if (!statusEffectInstance.getEffect().value().isBeneficial()) return true;
        }

        return false;
    }

    public static Vec3 getRenderPos(Entity entity, float tickDelta) {
        double x = Mth.lerp(tickDelta, entity.xo, entity.getX());
        double y = Mth.lerp(tickDelta, entity.yo, entity.getY());
        double z = Mth.lerp(tickDelta, entity.zo, entity.getZ());
        return new Vec3(x, y, z);
    }

    public static LivingEntity getClosestEntity(Entity entity) {
        LivingEntity closestEntity = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity livingEntity)) continue;

            if (!(entity.distanceTo(livingEntity) <= 10.0f)) continue;
            if (livingEntity.getHealth() <= 0.0f || !livingEntity.isAlive()) continue;
            if (entity == livingEntity) continue;

            if (closestEntity == null) {
                closestEntity = livingEntity;
                continue;
            }

            if (entity.distanceTo(livingEntity) < entity.distanceTo(closestEntity)) {
                closestEntity = livingEntity;
            }
        }

        return closestEntity;
    }

    public static Direction getPearlDirection(ThrownEnderpearl pearl) {
        Direction direction = pearl.getDirection();

        if (direction.equals(Direction.WEST)) return Direction.EAST;
        else if (direction.equals(Direction.EAST)) return Direction.WEST;

        return direction;
    }

    public static boolean isThrowable(Item item) {
        return item instanceof EnderpearlItem || item instanceof TridentItem || item instanceof ExperienceBottleItem || item instanceof SnowballItem || item instanceof EggItem || item instanceof SplashPotionItem || item instanceof LingeringPotionItem;
    }

    public static boolean isInWeb(Entity entity) {
        for (float x : new float[]{0, 0.3F, -0.3f}) {
            for (float z : new float[]{0, 0.3F, -0.3f}) {
                for (int y : new int[]{-1, 0, 1, 2}) {
                    BlockPos pos = BlockPos.containing(entity.getX() + x, entity.getY(), entity.getZ() + z).above(y);
                    if (new AABB(pos).intersects(entity.getBoundingBox()) && mc.level.getBlockState(pos).getBlock() == Blocks.COBWEB) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public enum SpeedUnit {
        METERS, KILOMETERS
    }
}
