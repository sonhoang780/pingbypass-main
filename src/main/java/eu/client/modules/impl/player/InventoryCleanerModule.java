package eu.client.modules.impl.player;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.TickEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.settings.impl.WhitelistSetting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

// Ported from example-addon-master's InventoryCleaner. Auto-drops unwanted items each tick.
// WhiteList = drop items NOT on the list (keep only what's listed); BlackList = drop items ON the
// list; All = drop everything. The original had a custom compose-UI whitelist editor screen --
// this project already has WhitelistSetting (same one SpeedMine's own whitelist uses, with its
// own ClickGui editor), reused here instead of porting a whole separate screen.
@RegisterModule(name = "InventoryCleaner", description = "Auto-drops unwanted items from your inventory each tick.", category = Module.Category.PLAYER)
public class InventoryCleanerModule extends Module {
    public ModeSetting mode = new ModeSetting("Mode", "WhiteList = drop items NOT in list. BlackList = drop items IN list. All = drop everything.", "WhiteList", new String[]{"WhiteList", "BlackList", "All"});
    public WhitelistSetting whitelist = new WhitelistSetting("List", "Items this mode's WhiteList/BlackList compares against.", WhitelistSetting.Type.ITEMS);
    public BooleanSetting ignoreHotbar = new BooleanSetting("IgnoreHotbar", "Skip hotbar slots (0-8) when cleaning.", true);
    // Compares base tier via max durability, not remaining HP, so a damaged netherite item still
    // outranks a pristine diamond one of the same kind.
    public BooleanSetting throwWorse = new BooleanSetting("ThrowWorse", "Drop lower-tier duplicate tools/armor of the same type (e.g. diamond+iron pickaxe -> drop iron).", true);
    public BooleanSetting others = new BooleanSetting("Others", "Also drop from other open GUIs like Chest, Shulker, EnderChest.", false);
    // Per-item CUSTOM_NAME skip protects labeled gear. With Others on, also skips acting on any
    // container whose title isn't a vanilla TranslatableContents (a server-set literal like a
    // shop's "Xác nhận mua"), except a ShulkerBoxMenu -- a renamed shulker opened by hand is still
    // cleaned.
    public BooleanSetting ignoreCustomName = new BooleanSetting("IgnoreCustomName", "Skip custom-named items, and (with Others on) skip acting on custom-titled containers such as a shop GUI, except shulker boxes.", false);
    public NumberSetting delay = new NumberSetting("Delay", "Tick delay between drop passes.", 1, 0, 20);
    public NumberSetting actionsPerTick = new NumberSetting("ActionsPerTick", "Max items to drop per pass.", 5, 1, 20);

    private int ticks = 0;

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (mc.player.isCreative()) return;

        // Defer while an item is mid-move -- THROW only fires on an empty cursor anyway, and
        // evaluating slots whose contents are about to shift is pointless. Also defer while
        // Rekit/InventorySorter are actively clicking (last 200ms), so the cleaner never throws
        // a slot they're mid-swapping.
        if (!mc.player.containerMenu.getCarried().isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - RekitModule.lastContainerActionMs < 200 || now - InventorySorterModule.lastContainerActionMs < 200) return;

        boolean externalGui = mc.screen instanceof AbstractContainerScreen && !(mc.screen instanceof InventoryScreen);

        if (ticks < delay.getValue().intValue()) { ticks++; return; }
        ticks = 0;

        int containerId = mc.player.containerMenu.containerId;
        int actions = 0;
        int maxActions = actionsPerTick.getValue().intValue();

        for (int invSlot = 0; invSlot < 36 && actions < maxActions; invSlot++) {
            if (ignoreHotbar.getValue() && invSlot <= 8) continue;
            ItemStack stack = mc.player.getInventory().getItem(invSlot);
            if (stack.isEmpty()) continue;

            if (shouldDrop(stack)) {
                int handlerSlot = invToHandlerSlot(invSlot);
                if (handlerSlot < 0) continue;
                throwSlot(containerId, handlerSlot);
                actions++;
            }
        }

        if (throwWorse.getValue() && actions < maxActions) {
            actions += runThrowWorsePass(containerId, maxActions - actions);
        }

        boolean customShopGui = ignoreCustomName.getValue() && hasCustomContainerTitle() && !(mc.player.containerMenu instanceof ShulkerBoxMenu);
        if (externalGui && others.getValue() && !customShopGui && actions < maxActions) {
            int totalSlots = mc.player.containerMenu.slots.size();
            int containerSlotCount = totalSlots - 36;
            for (int slot = 0; slot < containerSlotCount && actions < maxActions; slot++) {
                ItemStack stack = mc.player.containerMenu.slots.get(slot).getItem();
                if (stack.isEmpty()) continue;
                if (shouldDrop(stack)) { throwSlot(containerId, slot); actions++; }
            }
        }
    }

    private void throwSlot(int containerId, int slot) {
        mc.gameMode.handleContainerInput(containerId, slot, 1, ContainerInput.THROW, mc.player);
    }

    private boolean hasCustomContainerTitle() {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return false;
        Component title = screen.getTitle();
        if (title == null) return false;
        return !(title.getContents() instanceof TranslatableContents);
    }

    private boolean shouldDrop(ItemStack stack) {
        if (ignoreCustomName.getValue() && stack.has(DataComponents.CUSTOM_NAME)) return false;
        boolean listed = whitelist.isWhitelistContains(stack.getItem());
        return switch (mode.getValue()) {
            case "All" -> true;
            case "BlackList" -> listed;
            default -> !listed; // WhiteList
        };
    }

    private int runThrowWorsePass(int containerId, int budget) {
        String[] suffixes = { "_pickaxe", "_axe", "_shovel", "_hoe", "_sword", "_helmet", "_chestplate", "_leggings", "_boots" };
        int remaining = budget;

        for (String suffix : suffixes) {
            if (remaining <= 0) break;
            List<int[]> group = new ArrayList<>(); // [invSlot, maxDamage]
            for (int invSlot = 0; invSlot < 36; invSlot++) {
                if (ignoreHotbar.getValue() && invSlot <= 8) continue;
                ItemStack stack = mc.player.getInventory().getItem(invSlot);
                if (stack.isEmpty()) continue;
                String key = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (key.endsWith(suffix)) group.add(new int[]{ invSlot, stack.getMaxDamage() });
            }
            if (group.size() < 2) continue;

            int maxDur = group.stream().mapToInt(e -> e[1]).max().orElse(0);
            for (int[] entry : group) {
                if (remaining <= 0) break;
                if (entry[1] < maxDur) {
                    int handlerSlot = invToHandlerSlot(entry[0]);
                    if (handlerSlot < 0) continue;
                    throwSlot(containerId, handlerSlot);
                    remaining--;
                }
            }
        }
        return budget - remaining;
    }

    // mc.player.containerMenu isn't always the player's own 36-slot InventoryMenu -- with any
    // other menu open (chest, shulker...) the slot layout differs entirely. Look up the actual
    // Slot whose backing Container is the player's own Inventory at the given index, and use ITS
    // real index within the current menu -- correct regardless of which menu is open.
    private int invToHandlerSlot(int invSlot) {
        net.minecraft.world.Container playerInv = mc.player.getInventory();
        for (net.minecraft.world.inventory.Slot slot : mc.player.containerMenu.slots) {
            if (slot.container == playerInv && slot.getContainerSlot() == invSlot) return slot.index;
        }
        return -1;
    }
}
