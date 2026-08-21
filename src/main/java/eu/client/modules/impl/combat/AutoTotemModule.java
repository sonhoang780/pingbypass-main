package eu.client.modules.impl.combat;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerPopEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.events.impl.TickEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.modules.impl.player.SpeedMineModule;
import eu.client.modules.impl.player.MultiTaskModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.InventoryUtils;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;

@RegisterModule(name = "AutoTotem", description = "Automatically puts a specified item in your offhand slot.", category = Module.Category.COMBAT)
public class AutoTotemModule extends Module {
    public ModeSetting item = new ModeSetting("Item", "The item that will be placed in your offhand slot when safety conditions are met.", "Totem", new String[]{"Totem", "Crystal", "Gapple"});
    public NumberSetting health = new NumberSetting("Health", "The health at which a totem will be prioritized.", new ModeSetting.Visibility(item, "Crystal", "Gapple"), 16, 0, 36);
    public BooleanSetting elytraCheck = new BooleanSetting("ElytraCheck", "Prioritizes a totem whenever you're wearing an elytra.", true);
    public NumberSetting fallDistance = new NumberSetting("FallDistance", "The fall distance at which the module will prioritize a totem.", 20.0f, 0.0f, 80.0f);
    public BooleanSetting useGapple = new BooleanSetting("UseGapple", "Switches to a golden apple in your offhand when holding right click and holding a sword.", true);
    public BooleanSetting lethalOverride = new BooleanSetting("LethalOverride", "Overrides any necessity for a totem when right-click gappling.", new BooleanSetting.Visibility(useGapple, true), false);

    public BooleanSetting noTotemGap = new BooleanSetting("NoTotemGap", "Swap your gap into offhand when no totems left", false);
    public BooleanSetting alternative = new BooleanSetting("Alternative", "Uses single-packet SWAP action (button 40) instead of 3-click PICKUP into offhand.", true);

    public BooleanSetting smartMine = new BooleanSetting("SmartMine", "Switches to a crystal whenever you start mining and a totem when you aren't mining.", new ModeSetting.Visibility(item, "Crystal"), false);
    public BooleanSetting antiMace = new BooleanSetting("AntiMace", "Switches to a totem if a player near you is trying to smash attack you with a mace.", false);
    public NumberSetting maceRange = new NumberSetting("MaceRange", "The distance at which an enemy has to be in with a mace in order to swap to a totem.", new BooleanSetting.Visibility(antiMace, true), 12.0f, 0.0f, 24.0f);

    // Debug: logs death cause + totem state at death, totem pops, and every reason a totem swap
    // is skipped/failed (deduped so it doesn't spam each tick).
    public BooleanSetting debug = new BooleanSetting("Debug", "Logs why you died and why the totem swap failed at that moment.", false);

    private int totemCount = 0;

    // Debug state
    private boolean wasAlive = true;
    private String lastReason = "";

    @SubscribeEvent(priority = Integer.MAX_VALUE)
    public void onPlayerPop(PlayerPopEvent event) {
        if (eu.client.pingbypass.PingBypassFlags.isPingBypassActive()) return;
        if (event.getPlayer() == mc.player && !EUClient.MODULE_MANAGER.getModule(SuicideModule.class).isToggled()) {
            if (debug.getValue()) {
                log("TOTEM POPPED (survived). health+absorb=" + (mc.player.getHealth() + mc.player.getAbsorptionAmount())
                        + ", totemsLeft=" + totemCount + ", offhandNow=" + itemName(mc.player.getOffhandItem().getItem()));
            }
            updateTotem();
        }
    }

    @SubscribeEvent(priority = Integer.MAX_VALUE)
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        updateTotem();
    }

    private void updateTotem() {
        if (mc.player == null || mc.level == null) return;
        if (eu.client.pingbypass.PingBypassFlags.isPingBypassActive()) { reason("skip: PingBypass active"); return; }

        if (!(mc.gui.screen() instanceof InventoryScreen) && mc.gui.screen() instanceof AbstractContainerScreen<?>) {
            reason("skip: an external container GUI is open");
            return;
        }

        if (!mc.player.containerMenu.getCarried().isEmpty()) {
            reason("skip: cursor is holding an item (mid-move)");
            return;
        }

        Item targetItem = getItem();
        if (targetItem == null) { reason("skip: getItem() returned null (no valid target item)"); return; }

        int slot;

        if (targetItem == Items.TOTEM_OF_UNDYING && EUClient.MODULE_MANAGER.getModule(SuicideModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(SuicideModule.class).offhandOverride.getValue()) {
            if (mc.player.getOffhandItem().isEmpty()) { reason("skip: Suicide offhandOverride, offhand already empty"); return; }
            slot = InventoryUtils.findEmptySlot(InventoryUtils.HOTBAR_START, InventoryUtils.INVENTORY_END);
            if (slot == -1) reason("Suicide override: no empty slot to move offhand item into");
        } else {
            if (mc.player.getOffhandItem().getItem() == targetItem) { reason("ok: offhand already holds target (" + itemName(targetItem) + ")"); return; }

            slot = InventoryUtils.findInventory(targetItem);
            if (slot == -1) slot = InventoryUtils.find(targetItem);

            if (slot == -1) {
                if (targetItem == Items.TOTEM_OF_UNDYING && hasItem(Items.TOTEM_OF_UNDYING)) {
                    slot = InventoryUtils.findEmptySlot(InventoryUtils.HOTBAR_START, InventoryUtils.INVENTORY_END);
                    if (slot == -1) reason("FAIL: have a totem but no empty slot to stage the swap");
                } else {
                    reason("FAIL: target " + itemName(targetItem) + " NOT FOUND in inventory (none left)");
                    return;
                }
            }
        }

        if (slot == -1) { reason("FAIL: no usable slot resolved (see previous reason)"); return; }

        // MultiTask (AutoTotem option): don't interrupt the player's own eating just to swap.
        MultiTaskModule multiTask = EUClient.MODULE_MANAGER.getModule(MultiTaskModule.class);
        boolean keepEating = multiTask != null && multiTask.isToggled() && multiTask.autoTotem.getValue();
        if (!keepEating && mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);

        if (alternative.getValue()) {
            InventoryUtils.swap("Swap", slot, 40);
        } else {
            InventoryUtils.swap("Pickup", slot, 45);
        }

        if (debug.getValue()) {
            log("SWAP -> offhand: " + itemName(targetItem) + " (from slot " + slot + ") [mode=" + (alternative.getValue() ? "Swap" : "Pickup") + "]");
        }
        lastReason = ""; // allow the next distinct reason to print
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (eu.client.pingbypass.PingBypassFlags.isPingBypassActive()) return;
        if (mc.player == null || mc.level == null) return;
        totemCount = mc.player.getInventory().countItem(Items.TOTEM_OF_UNDYING);

        // Death detection: log the moment the player transitions from alive -> dead.
        boolean alive = mc.player.getHealth() > 0.0f && !mc.player.isDeadOrDying();
        if (debug.getValue() && wasAlive && !alive) logDeath();
        wasAlive = alive;
    }

    private void logDeath() {
        String cause;
        try {
            cause = mc.player.getCombatTracker().getDeathMessage().getString();
        } catch (Throwable t) {
            cause = "(unknown)";
        }
        var offhand = mc.player.getOffhandItem();
        boolean hadTotemOffhand = offhand.getItem() == Items.TOTEM_OF_UNDYING;

        log("================ DEATH ================");
        log("Cause: " + cause);
        log("Offhand at death: " + (offhand.isEmpty() ? "EMPTY" : itemName(offhand.getItem())) + (hadTotemOffhand ? " (totem WAS present!)" : ""));
        log("Totems in inventory at death: " + totemCount);
        log("Health+Absorb at death: " + (mc.player.getHealth() + mc.player.getAbsorptionAmount()));
        log("needsTotem()=" + needsTotem());
        log("Item mode=" + item.getValue() + ", target now=" + itemName(getItem()));
        log("Last swap reason: " + (lastReason.isEmpty() ? "(none)" : lastReason));

        // Concrete diagnosis
        if (hadTotemOffhand) {
            log("=> A totem WAS in offhand but you still died (one-shot exceeding totem heal, or totem pop not registered before death).");
        } else if (totemCount == 0) {
            log("=> No totems left in inventory -- nothing to place.");
        } else {
            log("=> Totems available but offhand didn't have one -- see 'Last swap reason' above for why the swap didn't happen.");
        }
        log("=======================================");
    }

    private Item getItem() {
        boolean hasTotem = hasItem(Items.TOTEM_OF_UNDYING);
        boolean hasGapple = hasItem(Items.ENCHANTED_GOLDEN_APPLE);
        boolean holdingWeapon = mc.player.getMainHandItem().is(ItemTags.SWORDS) || mc.player.getMainHandItem().getItem() instanceof AxeItem;

        if (useGapple.getValue() && mc.options.keyUse.isDown() && holdingWeapon && hasGapple) {
            if (lethalOverride.getValue() || !needsTotem() || !hasTotem) {
                return Items.ENCHANTED_GOLDEN_APPLE;
            }
        }

        if (needsTotem()) {
            if (hasTotem) {
                return Items.TOTEM_OF_UNDYING;
            } else if (noTotemGap.getValue() && hasGapple) {
                return Items.ENCHANTED_GOLDEN_APPLE;
            }
        }

        if (hasTotem && item.getValue().equalsIgnoreCase("Crystal") && smartMine.getValue()) {
            SpeedMineModule module = EUClient.MODULE_MANAGER.getModule(SpeedMineModule.class);
            if ((module.getPrimary() == null || !module.getPrimary().isMining()) && (module.getSecondary() == null || !module.getSecondary().isMining())) {
                return Items.TOTEM_OF_UNDYING;
            }
        }

        switch (item.getValue()) {
            case "Crystal" -> {
                if (hasItem(Items.END_CRYSTAL)) return Items.END_CRYSTAL;
            }
            case "Gapple" -> {
                if (hasGapple) return Items.ENCHANTED_GOLDEN_APPLE;
            }
            default -> {
                if (hasTotem) return Items.TOTEM_OF_UNDYING;
            }
        }

        if (hasTotem) return Items.TOTEM_OF_UNDYING;
        if (noTotemGap.getValue() && hasGapple) return Items.ENCHANTED_GOLDEN_APPLE;

        return mc.player.getOffhandItem().getItem();
    }

    private boolean needsTotem() {
        if (EUClient.MODULE_MANAGER.getModule(SuicideModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(SuicideModule.class).offhandOverride.getValue()) return false;

        if (mc.player.getHealth() + mc.player.getAbsorptionAmount() <= health.getValue().floatValue()) return true;
        if (mc.player.fallDistance > fallDistance.getValue().floatValue()) return true;
        if (elytraCheck.getValue() && mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA) return true;

        return antiMace.getValue() && (mc.level.players().stream().anyMatch(entity -> entity != mc.player && !EUClient.FRIEND_MANAGER.contains(entity.getName().getString()) && mc.player.distanceToSqr(entity) <= Mth.square(maceRange.getValue().floatValue()) && entity.fallDistance >= 1.5 && entity.getMainHandItem().getItem().equals(Items.MACE)));
    }

    private boolean hasItem(Item item) {
        return InventoryUtils.find(item) != -1 || mc.player.getOffhandItem().getItem() == item;
    }

    // ---- Debug helpers ----
    private void reason(String msg) {
        if (!debug.getValue()) return;
        if (msg.equals(lastReason)) return; // dedupe consecutive identical reasons
        lastReason = msg;
        EUClient.CHAT_MANAGER.tagged(msg, getName());
    }

    private void log(String msg) {
        EUClient.CHAT_MANAGER.tagged(msg, getName());
    }

    private String itemName(Item item) {
        if (item == null || item == Items.AIR) return "none";
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    @Override
    public String getMetaData() {
        return String.valueOf(totemCount);
    }
}