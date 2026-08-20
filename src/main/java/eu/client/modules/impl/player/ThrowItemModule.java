package eu.client.modules.impl.player;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.TickEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.NumberSetting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

@RegisterModule(name = "ThrowItem", description = "Throw out items that arent belong to your loaded kit. Useful when you need to loot gears during combat", category = Module.Category.PLAYER)
public class ThrowItemModule extends Module {
    public NumberSetting delay = new NumberSetting("Delay", "Tick delay between throw actions.", 1, 0, 20);
    public NumberSetting actionsPerTick = new NumberSetting("ActionsPerTick", "Maximum items to drop per pass.", 3, 1, 20);
    public BooleanSetting ignoreHotbar = new BooleanSetting("IgnoreHotbar", "Do not throw items from the hotbar (slots 0-8).", false);
    public BooleanSetting onlyInventory = new BooleanSetting("OnlyInventory", "Only throw items when in regular gameplay or InventoryScreen (not in Chests/Shulkers).", true);

    private int ticks = 0;

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (mc.player.isCreative() || mc.player.isSpectator()) return;

        // Do not throw while cursor is holding an item
        if (!mc.player.containerMenu.getCarried().isEmpty()) return;

        RekitModule rekit = EUClient.MODULE_MANAGER.getModule(RekitModule.class);
        if (rekit == null) return;

        Map<Integer, RekitModule.KitItem> activeKit = rekit.getActiveKit();
        // If no kit is loaded, do not drop anything
        if (activeKit == null || activeKit.isEmpty()) return;

        // If Rekit is actively moving items in/out of shulkers or containers, defer
        if (rekit.isAutoActive() || System.currentTimeMillis() - RekitModule.lastContainerActionMs < 200) return;

        boolean foreignGui = mc.screen instanceof AbstractContainerScreen && !(mc.screen instanceof InventoryScreen);
        if (onlyInventory.getValue() && foreignGui) return;

        if (ticks < delay.getValue().intValue()) {
            ticks++;
            return;
        }
        ticks = 0;

        int containerId = mc.player.containerMenu.containerId;
        int actions = 0;
        int maxActions = actionsPerTick.getValue().intValue();

        for (int invSlot = 0; invSlot < 36 && actions < maxActions; invSlot++) {
            if (ignoreHotbar.getValue() && invSlot <= 8) continue;

            ItemStack stack = mc.player.getInventory().getItem(invSlot);
            if (stack.isEmpty()) continue;

            RekitModule.KitItem kitItem = activeKit.get(invSlot);
            boolean shouldThrow = false;

            if (kitItem == null) {
                // The kit has NO item in this slot (it should be empty)
                shouldThrow = true;
            } else {
                String stackId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (!stackId.equalsIgnoreCase(kitItem.id)) {
                    // Item type does not match what the kit expects in this slot
                    shouldThrow = true;
                }
            }

            if (shouldThrow) {
                int handlerSlot = invToHandlerSlot(invSlot);
                if (handlerSlot >= 0) {
                    throwSlot(containerId, handlerSlot);
                    actions++;
                }
            }
        }
    }

    private void throwSlot(int containerId, int slot) {
        mc.gameMode.handleContainerInput(containerId, slot, 1, ContainerInput.THROW, mc.player);
    }

    private int invToHandlerSlot(int invSlot) {
        net.minecraft.world.Container playerInv = mc.player.getInventory();
        for (Slot slot : mc.player.containerMenu.slots) {
            if (slot.container == playerInv && slot.getContainerSlot() == invSlot) return slot.index;
        }
        return -1;
    }
}