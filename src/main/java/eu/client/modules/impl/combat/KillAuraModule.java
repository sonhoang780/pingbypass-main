package eu.client.modules.impl.combat;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PacketReceiveEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.events.impl.RenderWorldEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.CategorySetting;
import eu.client.settings.impl.ColorSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.graphics.Renderer3D;
import eu.client.utils.minecraft.EnchantmentUtils;
import eu.client.utils.minecraft.EntityUtils;
import eu.client.utils.minecraft.GrimUtils;
import eu.client.utils.minecraft.InventoryUtils;
import eu.client.utils.rotations.RotationUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@RegisterModule(name = "KillAura", description = "Automatically attacks optimal targets using Nami's combat engine.", category = Module.Category.COMBAT, proxyEnhanced = true)
public class KillAuraModule extends Module {
    public NumberSetting range = new NumberSetting("Range", "The reach distance to entities.", 3.0, 1.0, 6.0);
    public BooleanSetting stanceAbuse = new BooleanSetting("StanceAbuse", "Abuses Grim stance eye positions to optimize reach.", false);
    public NumberSetting delay = new NumberSetting("Delay", "Attack cooldown delay multiplier.", 0.92, 0.0, 1.0);
    public ModeSetting swap = new ModeSetting("Swap", "Weapon switch mode.", "Require", new String[]{"None", "Require", "Normal", "Silent"});
    public ModeSetting tpsMode = new ModeSetting("TPS", "TPS synchronization mode for attack cooldown.", "Average", new String[]{"None", "Average"});
    public BooleanSetting multiTask = new BooleanSetting("Multitask", "Allows attacking while eating or using items.", true);
    public ModeSetting stopSprinting = new ModeSetting("Sprinting", "Sprint management during attacks.", "None", new String[]{"None", "Packet"});
    public ModeSetting rotate = new ModeSetting("Rotate", "Rotation mode.", "Normal", new String[]{"None", "Normal", "Hold", "Silent"});
    public BooleanSetting swing = new BooleanSetting("Swing", "Whether to swing your hand when attacking.", true);
    public BooleanSetting render = new BooleanSetting("Render", "Renders a box around the current target.", true);

    public CategorySetting entitiesCategory = new CategorySetting("Entities", "Target entity filter settings.");
    public BooleanSetting players = new BooleanSetting("Players", "Target player entities.", new CategorySetting.Visibility(entitiesCategory), true);
    public BooleanSetting friends = new BooleanSetting("Friends", "Target friends.", new CategorySetting.Visibility(entitiesCategory), false);
    public BooleanSetting hostiles = new BooleanSetting("Hostiles", "Target hostile mobs.", new CategorySetting.Visibility(entitiesCategory), false);
    public BooleanSetting animals = new BooleanSetting("Animals", "Target passive animal mobs.", new CategorySetting.Visibility(entitiesCategory), false);
    public BooleanSetting projectiles = new BooleanSetting("Projectiles", "Target shulker bullets & fireballs.", new CategorySetting.Visibility(entitiesCategory), true);

    public ColorSetting boxColor = new ColorSetting("TargetColor", "Color of the target render box.", new BooleanSetting.Visibility(render, true), ColorUtils.getDefaultFillColor());

    public Entity target = null;
    private float attackCooldownTicks = 0.0f;
    private int originalSlot = -1;

    public Entity getTarget() {
        return target;
    }

    @Override
    public void onEnable() {
        target = null;
        originalSlot = -1;
        attackCooldownTicks = 0.0f;
    }

    @Override
    public void onDisable() {
        if (originalSlot != -1 && mc.player != null) {
            InventoryUtils.switchSlot("Normal", originalSlot, mc.player.getInventory().getSelectedSlot());
            originalSlot = -1;
        }
        target = null;
    }

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (shouldRunOnProxy()) return;
        if (mc.player == null || mc.level == null || mc.player.isDeadOrDying()) return;

        // AutoCrystal priority check (matches Nami)
        var ac = EUClient.MODULE_MANAGER.getModule(AutoCrystalModule.class);
        if (ac != null && ac.isToggled() && ac.getTarget() != null) {
            return;
        }

        float tps = getTps();
        attackCooldownTicks -= 1.0f * (tps / 20.0f);
        if (attackCooldownTicks < 0.0f) attackCooldownTicks = 0.0f;

        if (mc.player.isUsingItem() && mc.player.getUseItem() == mc.player.getInventory().getSelectedItem()) {
            target = null;
            return;
        }

        if (!multiTask.getValue() && mc.player.getUseItemRemainingTicks() > 0) {
            target = null;
            return;
        }

        ItemStack stack = mc.player.getMainHandItem();
        Entity optimal = findOptimalTarget();

        // Require+non-weapon used to still set target = optimal here (only meant for the
        // optimal == null case) -- onRenderWorld only checks target != null, so the aura box kept
        // rendering around entities it could never actually attack. Null it out for both bail
        // conditions so render tracks "will actually attack", not "found a candidate".
        if (optimal == null || (swap.getValue().equalsIgnoreCase("Require") && !isItemAWeapon(stack))) {
            target = null;
            return;
        }

        target = optimal;

        boolean skipCooldown = false;
        if (target instanceof ShulkerBullet || target instanceof LargeFireball) {
            skipCooldown = true;
        } else {
            float attackDamage = calculatePlayerAttackDamage();
            if (target instanceof LivingEntity living && living.getMaxHealth() <= attackDamage) {
                skipCooldown = true;
            }
        }

        double preRotate = switch (rotate.getValue()) {
            case "Hold" -> 1.00;
            case "Normal" -> 0.10;
            default -> 0.00;
        };

        if ((skipCooldown || attackCooldownTicks <= preRotate * tps)) {
            Vec3 eyePos = mc.player.getEyePosition(1.0f);

            if (stanceAbuse.getValue()) {
                double foundDist = Double.MAX_VALUE;
                for (Vec3 v : GrimUtils.getPossibleEyePositions(mc.player)) {
                    Vec3 closest = RotationUtils.getClampClosestPoint(v, target.getBoundingBox());
                    double dist = v.distanceTo(closest);
                    if (dist < foundDist) {
                        foundDist = dist;
                        eyePos = v;
                    }
                }
            }

            Vec3 closestPoint = RotationUtils.getClosestPointToEye(eyePos, target.getBoundingBox());
            float pYRot = RotationUtils.getYRotToVec(eyePos, closestPoint);
            float pXRot = RotationUtils.getXRotToVec(eyePos, closestPoint);
            boolean insideBox = target.getBoundingBox().contains(eyePos);

            if (eyePos.distanceTo(RotationUtils.getClampClosestPoint(eyePos, target.getBoundingBox())) > range.getValue().doubleValue()) {
                target = null;
                return;
            }

            boolean canAttack = rotate.getValue().equalsIgnoreCase("None") || insideBox;

            if (!rotate.getValue().equalsIgnoreCase("None")) {
                if (rotate.getValue().equalsIgnoreCase("Silent")) {
                    EUClient.ROTATION_MANAGER.silentRotate(pYRot, pXRot);
                } else {
                    EUClient.ROTATION_MANAGER.packetRotate(pYRot, pXRot);
                }

                var serverCheck = RotationUtils.raycastTarget(eyePos, target, range.getValue().doubleValue(),
                        EUClient.ROTATION_MANAGER.getServerYaw(), EUClient.ROTATION_MANAGER.getServerPitch());
                canAttack = serverCheck != null || insideBox;
            }

            if (!canAttack) {
                return;
            }
        }

        if (!skipCooldown && attackCooldownTicks > 0.0f) {
            return;
        }

        boolean stoppedSprint = false;
        if (stopSprinting.getValue().equalsIgnoreCase("Packet") && mc.player.isSprinting() && !mc.player.isShiftKeyDown()) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
            stoppedSprint = true;
        }

        int weaponSlot = getBestWeaponSlot();
        int prevSlot = mc.player.getInventory().getSelectedSlot();
        boolean switchedSilent = false;

        if (swap.getValue().equalsIgnoreCase("Normal") && weaponSlot != -1) {
            if (originalSlot == -1 && prevSlot != weaponSlot) originalSlot = prevSlot;
            InventoryUtils.switchSlot("Normal", weaponSlot, prevSlot);
        } else if (swap.getValue().equalsIgnoreCase("Silent") && weaponSlot != -1) {
            InventoryUtils.switchSlot("Silent", weaponSlot, prevSlot);
            switchedSilent = true;
        }

        mc.gameMode.attack(mc.player, target);

        if (swing.getValue()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }

        if (switchedSilent) {
            InventoryUtils.switchBack("Silent", weaponSlot, prevSlot);
        }

        if (stoppedSprint) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_SPRINTING));
        }

        if (!skipCooldown) {
            attackCooldownTicks = getBaseCooldownTicks(mc.player.getMainHandItem(), tps);
        }
    }

    @SubscribeEvent
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPacket() instanceof ServerboundSetCarriedItemPacket)) return;
        if (mc.player == null || mc.level == null) return;

        mc.execute(() -> {
            ItemStack stack = mc.player.getMainHandItem();
            if (stack.isEmpty()) return;
            attackCooldownTicks = getBaseCooldownTicks(stack, getTps());
        });
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        if (!render.getValue() || target == null || mc.player == null || mc.player.isDeadOrDying()) return;

        Vec3 vec3d = EntityUtils.getRenderPos(target, event.getTickDelta());
        AABB box = target.getBoundingBox().move(vec3d.x - target.getX(), vec3d.y - target.getY(), vec3d.z - target.getZ());

        Renderer3D.renderBox(event.getMatrices(), box, boxColor.getColor());
        Renderer3D.renderBoxOutline(event.getMatrices(), box, boxColor.getColor());
    }

    private Entity findOptimalTarget() {
        if (mc.player == null || mc.level == null) return null;

        // 2026-08-20 FIX (reported: "KillAura vẫn render cho logoutSpot"). LogoutSpotModule adds
        // its ghost markers as real RemotePlayer entities via mc.level.addEntity() (see its own
        // :181), so they show up in entitiesForRendering() indistinguishable from a live player to
        // a plain `e instanceof Player` check -- they're not attackable, just a logout position
        // marker, and must be excluded here (AutoCrystal/AutoTrap deliberately ADD ghosts as
        // targets for their own reasons; KillAura is not one of those cases).
        eu.client.modules.impl.visuals.LogoutSpotModule logoutSpot = EUClient.MODULE_MANAGER != null ? EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.visuals.LogoutSpotModule.class) : null;
        java.util.Collection<net.minecraft.client.player.RemotePlayer> ghosts = logoutSpot != null ? logoutSpot.getGhosts() : java.util.Collections.emptyList();

        List<Entity> candidates = new ArrayList<>();
        double reachSq = (range.getValue().doubleValue() + 1.0) * (range.getValue().doubleValue() + 1.0);

        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player || !e.isAlive()) continue;
            if (e.distanceToSqr(mc.player) > reachSq) continue;
            if (ghosts.contains(e)) continue;

            if (e instanceof Player p) {
                if (!players.getValue()) continue;
                if (!friends.getValue() && EUClient.FRIEND_MANAGER.contains(p.getName().getString())) continue;
                candidates.add(e);
            } else if (projectiles.getValue() && (e instanceof ShulkerBullet || e instanceof LargeFireball)) {
                candidates.add(e);
            } else if (hostiles.getValue() && e.getType().getCategory() == MobCategory.MONSTER) {
                candidates.add(e);
            } else if (animals.getValue() && (e.getType().getCategory() == MobCategory.CREATURE || e.getType().getCategory() == MobCategory.WATER_CREATURE || e.getType().getCategory() == MobCategory.AXOLOTLS)) {
                candidates.add(e);
            }
        }

        // Smart priority: Players first -> Creepers close -> Projectiles -> Others
        List<Entity> playerCandidates = candidates.stream().filter(e -> e instanceof Player).sorted(Comparator.comparingDouble(e -> e.distanceToSqr(mc.player))).toList();
        if (!playerCandidates.isEmpty()) return playerCandidates.get(0);

        List<Entity> creeperCandidates = candidates.stream().filter(e -> e instanceof Creeper && e.distanceToSqr(mc.player) <= 9.0).sorted(Comparator.comparingDouble(e -> e.distanceToSqr(mc.player))).toList();
        if (!creeperCandidates.isEmpty()) return creeperCandidates.get(0);

        List<Entity> projCandidates = candidates.stream().filter(e -> e instanceof ShulkerBullet || e instanceof LargeFireball).sorted(Comparator.comparingDouble(e -> e.distanceToSqr(mc.player))).toList();
        if (!projCandidates.isEmpty()) return projCandidates.get(0);

        return candidates.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(mc.player))).orElse(null);
    }

    private float getTps() {
        if (tpsMode.getValue().equalsIgnoreCase("None")) return 20.0f;
        return EUClient.SERVER_MANAGER.getTickRate();
    }

    private float getBaseCooldownTicks(ItemStack stack, float tps) {
        float baseTicks;
        int weaponSlot = getBestWeaponSlot();
        ItemStack currentStack = (swap.getValue().equalsIgnoreCase("Silent") || swap.getValue().equalsIgnoreCase("Normal")) && weaponSlot != -1
                ? mc.player.getInventory().getItem(weaponSlot) : stack;

        if (currentStack.is(ItemTags.SWORDS)) baseTicks = 13.0f;
        else if (currentStack.is(ItemTags.AXES)) baseTicks = 21.0f;
        else if (currentStack.getItem() instanceof TridentItem) baseTicks = 19.0f;
        else if (currentStack.getItem() instanceof MaceItem) baseTicks = 34.0f;
        else {
            float attackSpeed = 6.0f;
            baseTicks = 20.0f / attackSpeed;
        }

        return (baseTicks * (20.0f / Math.max(tps, 1.0f))) * delay.getValue().floatValue();
    }

    public int getBestWeaponSlot() {
        if (mc.player == null) return -1;
        int bestSlot = -1;
        float bestDamage = -1.0f;
        boolean prioritizeMace = !mc.player.getAbilities().flying && !mc.player.onGround();

        for (int slot = 0; slot < 9; slot++) {
            ItemStack held = mc.player.getInventory().getItem(slot);
            if (held.isEmpty()) continue;

            boolean isSword = held.is(ItemTags.SWORDS);
            boolean isAxe = held.is(ItemTags.AXES);
            boolean isTrident = held.getItem() instanceof TridentItem;
            boolean isMace = held.getItem() instanceof MaceItem;

            if (!isSword && !isAxe && !isTrident && !isMace) continue;
            if (isMace && prioritizeMace) return slot;

            float attackDamage = 0.0f;
            if (held.has(DataComponents.ATTRIBUTE_MODIFIERS)) {
                ItemAttributeModifiers modifiers = held.get(DataComponents.ATTRIBUTE_MODIFIERS);
                if (modifiers != null) {
                    for (var entry : modifiers.modifiers()) {
                        if (entry.attribute().is(Attributes.ATTACK_DAMAGE)) {
                            attackDamage += (float) entry.modifier().amount();
                        }
                    }
                }
            }

            if (isSword) attackDamage += 5.0f;

            int sharpness = EnchantmentUtils.getEnchantmentLevel(held, Enchantments.SHARPNESS);
            int smite = EnchantmentUtils.getEnchantmentLevel(held, Enchantments.SMITE);
            int bane = EnchantmentUtils.getEnchantmentLevel(held, Enchantments.BANE_OF_ARTHROPODS);

            attackDamage += sharpness * 1.25f + smite * 2.5f + bane * 2.5f;

            if (attackDamage > bestDamage) {
                bestDamage = attackDamage;
                bestSlot = slot;
            }
        }

        return bestSlot;
    }

    private float calculatePlayerAttackDamage() {
        if (mc.player == null) return 1.0f;
        float attackDamage = 1.0f;
        if (mc.player.hasEffect(MobEffects.STRENGTH)) {
            var strength = mc.player.getEffect(MobEffects.STRENGTH);
            if (strength != null) attackDamage += 3.0f * (strength.getAmplifier() + 1);
        }
        if (mc.player.hasEffect(MobEffects.WEAKNESS)) {
            var weakness = mc.player.getEffect(MobEffects.WEAKNESS);
            if (weakness != null) attackDamage -= 4.0f * (weakness.getAmplifier() + 1);
        }
        return Math.max(attackDamage, 0.0f);
    }

    public static boolean isItemAWeapon(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.getItem() instanceof TridentItem || stack.getItem() instanceof MaceItem;
    }

    @Override
    public String getMetaData() {
        return target == null ? "None" : target.getName().getString();
    }
}