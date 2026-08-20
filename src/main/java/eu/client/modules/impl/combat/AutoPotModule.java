package eu.client.modules.impl.combat;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.settings.impl.WhitelistSetting;
import eu.client.utils.minecraft.InventoryUtils;
import eu.client.utils.system.Timer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

// Port of nami's AutoPotFeature (namidevelopment.kiriyaga.nami.impl.feature.combat.AutoPotFeature)
// -- verified against the real source, not eu-client-shaped guesswork. Nami tracks ONE effect via
// a single enum setting; requested extension here is a WhitelistSetting (Type.POTIONS, same
// Mode/Whitelist naming as every other whitelist in this project) so any number of REAL potions
// (not raw effect names -- e.g. "turtle_master" is SLOWNESS+RESISTANCE, a named combo, see
// WhitelistSetting's own doc) can be kept topped up. Everything else (throw timing, rotate-then-
// wait, swap modes, NoTarget/OnlyPhased/SelfToggle gates) is a straight 1:1 port.
@RegisterModule(name = "AutoPot", description = "Throws a splash potion under/above you to keep whitelisted potions' effects topped up.", category = Module.Category.COMBAT)
public class AutoPotModule extends Module {

    public ModeSetting mode = new ModeSetting("Mode", "WhiteList = keep these potions' effects topped up. BlackList = keep every potion's effects topped up except these.", "WhiteList", new String[]{"WhiteList", "BlackList"});
    public WhitelistSetting whitelist = new WhitelistSetting("Whitelist", "Potions this mode's WhiteList/BlackList compares against.", WhitelistSetting.Type.POTIONS);
    public NumberSetting amplifier = new NumberSetting("Amplifier", "Minimum amplifier level required before an effect counts as \"already have it\".", 1, 0, 4);

    public BooleanSetting rotate = new BooleanSetting("Rotate", "Rotates to look straight up/down before throwing (nami: real rotation, waits for it to land before throwing).", false);
    public ModeSetting throwMode = new ModeSetting("Throw", "Where the potion lands relative to you.", new BooleanSetting.Visibility(rotate, true), "Under", new String[]{"Above", "Under"});
    public ModeSetting swapMode = new ModeSetting("Swap", "How the potion slot gets selected before throwing.", "Silent", new String[]{"Normal", "Silent"});

    public BooleanSetting whenNoTarget = new BooleanSetting("NoTarget", "Only throws while no enemy is nearby.", false);
    public BooleanSetting onlyPhased = new BooleanSetting("OnlyPhased", "Only throws while phased into a block.", false);
    public BooleanSetting selfToggle = new BooleanSetting("SelfToggle", "Turns the module off once every whitelisted effect is already topped up.", true);
    public NumberSetting range = new NumberSetting("Range", "How close an enemy has to be for NoTarget to see them.", new BooleanSetting.Visibility(whenNoTarget, true), 32, 4, 64);

    private final Timer throwTimer = new Timer();
    private static final long THROW_DELAY_MS = 5000L; // nami's own hardcoded 5s, no setting there either

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (mc.player == null || mc.level == null) return;

        Potion missing = findMissingPotion();
        if (missing == null) {
            if (selfToggle.getValue()) setToggled(false);
            return;
        }

        if (whenNoTarget.getValue() && hasNearbyEnemy()) {
            if (selfToggle.getValue()) setToggled(false);
            return;
        }

        if (onlyPhased.getValue() && !isPhased()) {
            if (selfToggle.getValue()) setToggled(false);
            return;
        }

        int potInvSlot = findPot(missing);
        if (potInvSlot == -1) {
            if (selfToggle.getValue()) setToggled(false);
            return;
        }

        if (!throwTimer.hasTimeElapsed(THROW_DELAY_MS)) return;

        int potSlot = potInvSlot < 9 ? potInvSlot : -1;
        int previousSlot = mc.player.getInventory().getSelectedSlot();

        if (potSlot == -1) {
            // Not in the hotbar -- pull it there first, same 3-click pickup shape nami's move() uses.
            // swap("Pickup", slot, targetSlot): slot = source (the pot's inventory slot), targetSlot
            // = destination (hotbar slot 0) -- matches nami's move(invSlot, hotbarSlot) verbatim.
            InventoryUtils.swap("Pickup", potInvSlot, InventoryUtils.HOTBAR_START);
            potSlot = InventoryUtils.HOTBAR_START;
        }

        float pitch = throwMode.getValue().equalsIgnoreCase("Above") ? -90.0f : 90.0f;
        if (rotate.getValue()) {
            float[] target = new float[]{mc.player.getYRot(), pitch};
            EUClient.ROTATION_MANAGER.legacyRotate(target, this, EUClient.ROTATION_MANAGER.getLegacyModulePriority(this));
            if (!EUClient.ROTATION_MANAGER.isRotationReached(target)) return;
        }

        throwTimer.reset();
        InventoryUtils.switchSlot(swapMode.getValue(), potSlot, previousSlot);
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        InventoryUtils.switchBack(swapMode.getValue(), potSlot, previousSlot);
    }

    // WhiteList only for now -- BlackList's whitelist is an exclusion set, meaning "every OTHER
    // real potion in the registry" would need checking, which nami never does either (single-
    // target only). BlackList currently behaves like WhiteList; flag if that's not what's wanted.
    /** First potion in the whitelist whose effects aren't all currently active at `amplifier`, null if fully topped up. */
    private Potion findMissingPotion() {
        int requiredAmp = amplifier.getValue().intValue();
        for (Potion potionType : whitelist.getWhitelistedPotions()) {
            boolean missingAny = false;
            for (MobEffectInstance want : potionType.getEffects()) {
                boolean have = false;
                for (MobEffectInstance active : mc.player.getActiveEffects()) {
                    if (active.getEffect() == want.getEffect() && active.getAmplifier() >= requiredAmp) {
                        have = true;
                        break;
                    }
                }
                if (!have) {
                    missingAny = true;
                    break;
                }
            }
            if (missingAny) return potionType;
        }
        return null;
    }

    private int findPot(Potion targetPotion) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty() || stack.getItem() != Items.SPLASH_POTION) continue;
            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            if (contents == null || contents.potion().isEmpty()) continue;
            if (contents.potion().get().value() == targetPotion) return i;
        }
        return -1;
    }

    private boolean hasNearbyEnemy() {
        double rangeSq = Mth.square(range.getValue().doubleValue());
        for (Player player : mc.level.players()) {
            if (player == mc.player) continue;
            if (EUClient.FRIEND_MANAGER.contains(player.getName().getString())) continue;
            if (mc.player.distanceToSqr(player) <= rangeSq) return true;
        }
        return false;
    }

    /** Port of nami's AutoPotFeature.isPhased() -- verified against the real source. */
    private boolean isPhased() {
        AABB box = mc.player.getBoundingBox();
        int minX = Mth.floor(box.minX), maxX = Mth.ceil(box.maxX);
        int minY = Mth.floor(box.minY), maxY = Mth.ceil(box.maxY);
        int minZ = Mth.floor(box.minZ), maxZ = Mth.ceil(box.maxZ);

        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    VoxelShape shape = mc.level.getBlockState(pos).getCollisionShape(mc.level, pos);
                    if (!shape.isEmpty() && shape.bounds().move(pos).intersects(box)) return true;
                }
            }
        }
        return false;
    }
}
