package eu.client.utils.minecraft;

import eu.client.utils.IMinecraft;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.Holder;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;

import java.util.HashMap;
import java.util.Map;

public class DamageUtils implements IMinecraft {
    private static final Map<String, Integer> PROTECTION_MAP = new HashMap<>() {{
        put("protection", 1);
        put("blast_protection", 2);
        put("projectile_protection", 1);
        put("feather_falling", 1);
        put("fire_protection", 1);
    }};

    public static float getCrystalDamage(Entity entity, AABB box, EndCrystal crystal, boolean ignoreTerrain) {
        return getDamage(entity, box, Vec3.atCenterOf(crystal.blockPosition()), 6.0f, null, ignoreTerrain);
    }

    public static float getCrystalDamage(Entity entity, AABB box, BlockPos position, BlockPos exception, boolean ignoreTerrain) {
        // Vec3d.ofCenter(pos, 1) in 1.21.4 = (x+0.5, y+1, z+0.5) -- ported as
        // atCenterOf(pos).add(0,1,0) = (x+0.5, y+1.5, z+0.5), 0.5 block too high (double-counted
        // the center's own +0.5 Y). Wrong explosion origin height skewed exposure/distance calc,
        // causing bogus point-blank "lethal" candidates (e.g. placing behind self).
        return getDamage(entity, box, new Vec3(position.getX() + 0.5, position.getY() + 1, position.getZ() + 0.5), 6.0f, exception, ignoreTerrain);
    }

    public static float getDamage(Entity entity, AABB box, Vec3 vec3d, float power, BlockPos exception, boolean ignoreTerrain) {
        if (mc.level.getDifficulty() == Difficulty.PEACEFUL) return 0.0f;
        if (!(entity instanceof RemotePlayer) && entity instanceof Player player && EntityUtils.getGameMode(player) == GameType.CREATIVE) return 0.0f;

        float diameter = power * 2.0f;

        double distance = Math.sqrt(box != null ? box.getCenter().add(0, -0.9, 0).distanceToSqr(vec3d) : entity.distanceToSqr(vec3d)) / diameter;
        if (distance > 1.0) return 0.0f;

        double exposure = (1.0 - distance) * getExposure(vec3d, entity.getBoundingBox(), exception, ignoreTerrain);
        float damage = (int) ((exposure * exposure + exposure) / 2.0 * 7.0 * diameter + 1.0);

        if (damage <= 0.0f) return 0.0f;

        if (entity instanceof LivingEntity livingEntity) {
            damage = mc.level.getDifficulty() == Difficulty.EASY ? Math.min(damage / 2 + 1, damage) : mc.level.getDifficulty() == Difficulty.HARD ? damage * 3 / 2 : damage;
            damage = CombatRules.getDamageAfterAbsorb(livingEntity, damage, mc.level.damageSources().explosion(null), (float) livingEntity.getArmorValue(), (float) livingEntity.getAttribute(Attributes.ARMOR_TOUGHNESS).getValue());
            damage *= livingEntity.hasEffect(MobEffects.RESISTANCE) ? 1 - ((livingEntity.getEffect(MobEffects.RESISTANCE).getAmplifier() + 1) * 0.2) : 1;
            damage = CombatRules.getDamageAfterMagicAbsorb(damage, getProtectionAmount(java.util.List.of(
                    livingEntity.getItemBySlot(EquipmentSlot.FEET),
                    livingEntity.getItemBySlot(EquipmentSlot.LEGS),
                    livingEntity.getItemBySlot(EquipmentSlot.CHEST),
                    livingEntity.getItemBySlot(EquipmentSlot.HEAD))));
        }

        return Math.max(damage, 0.0f);
    }

    public static int getProtectionAmount(Iterable<ItemStack> armor) {
        int x = 0;
        for (ItemStack stack : armor) {
            x += getProtectionAmount(stack);
        }

        return x;
    }

    public static int getProtectionAmount(ItemStack armor) {
        int x = 0;
        ItemEnchantments enchantments = armor.getEnchantments();
        for (Holder<Enchantment> enchantment : enchantments.keySet()) {
            String id = enchantment.getRegisteredName().replace("minecraft:", "");
            if (PROTECTION_MAP.containsKey(id)) {
                x += enchantments.getLevel(enchantment) * PROTECTION_MAP.get(id);
                break;
            }
        }

        return x;
    }

    private static float getExposure(Vec3 source, AABB box, BlockPos exception, boolean ignoreTerrain) {
        int hitCount = 0;
        int count = 0;

        for (double k = 0.0; k <= 1.0; k += 0.4545454446934474) {
            for (double l = 0.0; l <= 1.0; l += 0.21739130885479366) {
                for (double m = 0.0; m <= 1.0; m += 0.4545454446934474) {
                    Vec3 vec3d = new Vec3(Mth.lerp(k, box.minX, box.maxX) + 0.045454555306552624, Mth.lerp(l, box.minY, box.maxY), Mth.lerp(m, box.minZ, box.maxZ) + 0.045454555306552624);
                    if (raycast(vec3d, source, exception, ignoreTerrain) == HitResult.Type.MISS) ++hitCount;
                    ++count;
                }
            }
        }

        return (float) hitCount / (float) count;
    }

    private static HitResult.Type raycast(Vec3 start, Vec3 end, BlockPos exception, boolean ignoreTerrain) {
        return BlockGetter.traverseBlocks(start, end, null, (innerContext, blockPos) -> {
            BlockState blockState;
            if (blockPos.equals(exception)) {
                blockState = Blocks.AIR.defaultBlockState();
            } else {
                blockState = mc.level.getBlockState(blockPos);
                if (blockState.getBlock().getExplosionResistance() < 600 && ignoreTerrain) blockState = Blocks.AIR.defaultBlockState();
            }

            BlockHitResult hitResult = blockState.getCollisionShape(mc.level, blockPos).clip(start, end, blockPos);
            return hitResult == null ? null : hitResult.getType();
        }, (innerContext) -> HitResult.Type.MISS);
    }
}
