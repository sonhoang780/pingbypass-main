package eu.client.modules.impl.player;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.InventoryUtils;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ContainerInput;

@RegisterModule(name = "Replenish", description = "Automatically replenishes stacks in your hotbar with new ones when they meet a specified threshold.", category = Module.Category.PLAYER)
public class ReplenishModule extends Module {
    public ModeSetting switchMode = new ModeSetting("Switch", "The mode that will be used for switching items.", "Swap", new String[]{"Pickup", "Swap", "Quick"});
    public NumberSetting threshold = new NumberSetting("Threshold", "The minimum amount of items in a stack before that stack is replaced.", 12, 1, 64);
    public NumberSetting minimumCount = new NumberSetting("MinimumCount", "The minimum amount of items that should be in the new stack.", 48, 1, 64);
    // Count-based threshold/minimumCount above are meaningless for maxStackSize=1 items (shulkers,
    // armor, minecarts, boats...) -- their only "used up" signal is the hotbar slot going empty
    // (placed/broken/consumed). When enabled, an empty hotbar slot that last held an unstackable
    // item gets refilled with another of the same item from the main inventory. Filled maps are
    // excluded on purpose -- each one carries unique map data, so "replenishing" with a different
    // map instance would silently swap in the wrong map.
    public BooleanSetting unstackable = new BooleanSetting("Unstackable", "Also replenishes unstackable items (shulkers, armor, minecarts...) when their hotbar slot goes empty. Filled maps are never replenished.", false);

    private int ticks;
    // Last known item per hotbar slot, so an unstackable item can still be identified once its
    // slot has already gone empty (there is nothing left in the slot itself to read the type from).
    private final ItemStack[] lastSeen = new ItemStack[9];

    // 2026-08-15 (reported): moving an item from the hotbar up into the main inventory (slot 9-35)
    // by hand, then closing the screen, made Replenish immediately pull another stack of the same
    // item straight back into the now-lighter/empty hotbar slot on the very next tick -- undoing
    // the manual move outright. Snapshot the hotbar on screen-open and diff it on screen-close: any
    // slot whose count dropped (or went empty) while the screen was open was touched by hand, so
    // skip refilling it for exactly one post-close tick. Real gameplay usage (mining/eating/placing)
    // only ever happens with the screen closed, so this never suppresses a legitimate refill.
    private boolean wasInScreen;
    private final ItemStack[] screenOpenSnapshot = new ItemStack[9];
    private final boolean[] manuallyEmptied = new boolean[9];

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (mc.player == null || mc.level == null) return;

        boolean inScreen = InventoryUtils.inInventoryScreen();
        if (inScreen) {
            if (!wasInScreen) {
                for (int i = 0; i <= 8; i++) screenOpenSnapshot[i] = mc.player.getInventory().getItem(i).copy();
            }
            wasInScreen = true;
            return;
        }
        if (wasInScreen) {
            for (int i = 0; i <= 8; i++) {
                ItemStack before = screenOpenSnapshot[i];
                ItemStack after = mc.player.getInventory().getItem(i);
                if (before == null || before.isEmpty()) continue;
                if (after.isEmpty() || (after.getItem() == before.getItem() && after.getCount() < before.getCount())) {
                    manuallyEmptied[i] = true;
                }
            }
            wasInScreen = false;
        }

        if (ticks <= 0) {
            for (int i = 0; i <= 8; i++) {
                if (manuallyEmptied[i]) {
                    manuallyEmptied[i] = false;
                    continue;
                }

                ItemStack stack = mc.player.getInventory().getItem(i);

                if (stack.isStackable()) {
                    if (stack.getCount() > (int) ((threshold.getValue().floatValue() / 64.0f) * stack.getMaxStackSize())) continue;
                    if (stack.isEmpty()) continue;

                    int slot = InventoryUtils.findInventory(stack.getItem(), (int) ((minimumCount.getValue().intValue() / 64.0f) * stack.getMaxStackSize()));
                    if (slot == -1) continue;

                    replenishSlot(slot, i);
                } else if (unstackable.getValue() && stack.isEmpty()) {
                    ItemStack previous = lastSeen[i];
                    if (previous == null || previous.isEmpty()) continue;
                    if (previous.getItem() == Items.FILLED_MAP) continue;

                    int slot = InventoryUtils.findInventory(previous.getItem());
                    if (slot == -1) continue;

                    replenishSlot(slot, i);
                }
            }
        }

        for (int i = 0; i <= 8; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty()) lastSeen[i] = stack.copy();
        }

        ticks--;
    }

    private void replenishSlot(int slot, int hotbarIndex) {
        if (switchMode.getValue().equalsIgnoreCase("Quick")) mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, InventoryUtils.indexToSlot(slot), 0, ContainerInput.QUICK_MOVE, mc.player);
        else InventoryUtils.swap(switchMode.getValue(), slot, hotbarIndex);

        ticks = 2 + EUClient.SERVER_MANAGER.getPingDelay();
    }

    @Override
    public String getMetaData() {
        return String.valueOf(threshold.getValue().intValue());
    }
}
