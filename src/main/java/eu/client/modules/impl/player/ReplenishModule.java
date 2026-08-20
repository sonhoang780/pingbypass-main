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
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

@RegisterModule(name = "Replenish", description = "Automatically replenishes stacks in your hotbar with new ones when they meet a specified threshold.", category = Module.Category.PLAYER)
public class ReplenishModule extends Module {
    public ModeSetting switchMode = new ModeSetting("Switch", "The mode that will be used for switching items.", "Swap", new String[]{"Pickup", "Swap", "Quick"});
    public NumberSetting threshold = new NumberSetting("Threshold", "The minimum amount of items in a stack before that stack is replaced.", 12, 1, 64);
    public NumberSetting minimumCount = new NumberSetting("MinimumCount", "The minimum amount of items that should be in the new stack.", 48, 1, 64);
    
    public BooleanSetting unstackable = new BooleanSetting("Unstackable", "Replenishes items that cannot be stacked (like Totems).", false);

    private int ticks;
    
    private final Item[] hotbarItems = new Item[9];
    private final int[] hotbarCounts = new int[9];

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (mc.player == null || mc.level == null) return;

        if (InventoryUtils.inInventoryScreen()) {
            for (int i = 0; i <= 8; i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                hotbarItems[i] = stack.getItem();
                hotbarCounts[i] = stack.getCount();
            }
            return;
        }

        if (ticks <= 0) {
            for (int i = 0; i <= 8; i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                Item currentItem = stack.getItem();
                int currentCount = stack.getCount();

                Item previousItem = hotbarItems[i];
                int previousCount = hotbarCounts[i];

                hotbarItems[i] = currentItem;
                hotbarCounts[i] = currentCount;

                if (previousItem == null || previousItem == Items.AIR) continue;
                if (currentItem == previousItem && currentCount >= previousCount) continue;

                Item targetItem = currentItem == Items.AIR ? previousItem : currentItem;
                ItemStack targetStack = currentItem == Items.AIR ? new ItemStack(previousItem) : stack;

                if (!targetStack.isStackable() && !unstackable.getValue()) continue;

                if (targetStack.isStackable()) {
                    if (currentCount > (int) ((threshold.getValue().floatValue() / 64.0f) * targetStack.getMaxStackSize())) continue;
                } else {
                    if (currentCount > 0) continue;
                }

                int slot = InventoryUtils.findInventory(targetItem, (int) ((minimumCount.getValue().intValue() / 64.0f) * targetStack.getMaxStackSize()));
                if (slot == -1) continue;

                if (switchMode.getValue().equalsIgnoreCase("Quick")) mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, InventoryUtils.indexToSlot(slot), 0, ContainerInput.QUICK_MOVE, mc.player);
                else InventoryUtils.swap(switchMode.getValue(), slot, i);

                ticks = 2 + EUClient.SERVER_MANAGER.getPingDelay();
            }
        }

        ticks--;
    }

    @Override
    public String getMetaData() {
        return String.valueOf(threshold.getValue().intValue());
    }
}