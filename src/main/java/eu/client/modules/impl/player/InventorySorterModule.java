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
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Ported from example-addon-master's InventorySorter. Sorts your own inventory into the active
// Rekit kit's layout: merges duplicate stacks of the same item, then moves each item toward the
// kit slot that wants it. Does nothing without an active kit (see RekitModule).
@RegisterModule(name = "InventorySorter", description = "Auto-sorts your inventory into the active Rekit kit's layout.", category = Module.Category.PLAYER)
public class InventorySorterModule extends Module {
    public NumberSetting delay = new NumberSetting("Delay", "Tick delay.", 1, 0, 10);
    public NumberSetting actionsPerTick = new NumberSetting("ActionsPerTick", "Max actions per tick.", 1, 1, 5);
    public BooleanSetting ignoreHotbar = new BooleanSetting("IgnoreHotbar", "Skip hotbar slots (0-8) when sorting.", false);

    private int ticks = 0;
    // Read by InventoryCleaner (RekitModule.lastContainerActionMs's sibling) to defer its own
    // dropping while the sorter is actively moving items, so the cleaner never throws a slot the
    // sorter is mid-swapping.
    public static volatile long lastContainerActionMs = 0;
    // Only dump the cursor to an empty slot after many ticks (server desync guard); otherwise
    // wait so the player can place items freely.
    private int cursorWaitTicks = 0;
    private static final int CURSOR_DUMP_AFTER_TICKS = 20;

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.player == null) return;
        RekitModule rekit = EUClient.MODULE_MANAGER.getModule(RekitModule.class);
        if (rekit.getActiveKit().isEmpty()) return;
        if (mc.player.isCreative()) return;

        if (mc.gui.screen() instanceof AbstractContainerScreen && !(mc.gui.screen() instanceof InventoryScreen)) return;

        if (ticks < delay.getValue().intValue()) { ticks++; return; }
        ticks = 0;

        int executed = 0;
        while (executed < actionsPerTick.getValue().intValue()) {
            if (!sortTick(rekit)) break;
            executed++;
        }
    }

    private boolean sortTick(RekitModule rekit) {
        AbstractContainerMenu handler = mc.player.containerMenu;

        if (!handler.getCarried().isEmpty()) {
            cursorWaitTicks++;
            if (cursorWaitTicks >= CURSOR_DUMP_AFTER_TICKS) {
                cursorWaitTicks = 0;
                int emptySlot = findEmptySlot(handler);
                if (emptySlot != -1) click(emptySlot, 0, ContainerInput.PICKUP);
            }
            return false;
        }
        cursorWaitTicks = 0;

        // Only collect items NOT at their correct kit position -- merging a correctly-placed item
        // with a misplaced one causes oscillation (merge moves it, kit sort moves it back).
        Map<String, List<Integer>> itemGroups = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            if (ignoreHotbar.getValue() && i <= 8) continue;
            int slotI = getHandlerSlot(i);
            ItemStack stackI = handler.getSlot(slotI).getItem();
            if (stackI.isEmpty() || isShulkerBox(stackI) || stackI.getCount() >= stackI.getItem().getDefaultMaxStackSize()) continue;

            RekitModule.KitItem kitI = rekit.getActiveKit().get(i);
            if (isCorrectItem(stackI, kitI)) continue;

            String key = BuiltInRegistries.ITEM.getKey(stackI.getItem()).toString();
            itemGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(slotI);
        }

        for (List<Integer> slots : itemGroups.values()) {
            if (slots.size() <= 1) continue;
            for (int i = 0; i < slots.size(); i++) {
                int slot1 = slots.get(i);
                ItemStack s1 = handler.getSlot(slot1).getItem();
                for (int j = i + 1; j < slots.size(); j++) {
                    int slot2 = slots.get(j);
                    ItemStack s2 = handler.getSlot(slot2).getItem();
                    if (ItemStack.isSameItemSameComponents(s1, s2)) {
                        atomicSwap(slot2, slot1);
                        return true;
                    }
                }
            }
        }

        for (int i = 0; i < 36; i++) {
            if (ignoreHotbar.getValue() && i <= 8) continue;

            int targetSlot = getHandlerSlot(i);
            ItemStack currentStack = handler.getSlot(targetSlot).getItem();
            if (isShulkerBox(currentStack)) continue;

            RekitModule.KitItem kit = rekit.getActiveKit().get(i);
            if (isCorrectItem(currentStack, kit)) continue;

            int sourceInvSlot = findItemForKit(handler, rekit, kit);
            if (sourceInvSlot != -1) {
                int sourceHandlerSlot = getHandlerSlot(sourceInvSlot);

                if (i <= 8 && sourceInvSlot >= 9) { click(sourceHandlerSlot, i, ContainerInput.SWAP); return true; }
                if (sourceInvSlot <= 8 && i >= 9) { click(targetSlot, sourceInvSlot, ContainerInput.SWAP); return true; }

                atomicSwap(sourceHandlerSlot, targetSlot);
                return true;
            }
        }
        return false;
    }

    private void atomicSwap(int slot1, int slot2) {
        click(slot1, 0, ContainerInput.PICKUP);
        click(slot2, 0, ContainerInput.PICKUP);
        click(slot1, 0, ContainerInput.PICKUP);
    }

    private void click(int slotId, int button, ContainerInput type) {
        lastContainerActionMs = System.currentTimeMillis();
        mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, slotId, button, type, mc.player);
    }

    private boolean isCorrectItem(ItemStack stack, RekitModule.KitItem kit) {
        if (kit == null) return true;
        if (stack.isEmpty()) return false;
        Item expected = BuiltInRegistries.ITEM.getValue(Identifier.parse(kit.id));
        return stack.getItem() == expected;
    }

    private int findItemForKit(AbstractContainerMenu handler, RekitModule rekit, RekitModule.KitItem targetKit) {
        if (targetKit == null) return -1;
        Item expected = BuiltInRegistries.ITEM.getValue(Identifier.parse(targetKit.id));

        for (int i = 0; i < 36; i++) {
            if (ignoreHotbar.getValue() && i <= 8) continue;
            int slotI = getHandlerSlot(i);
            ItemStack stack = handler.getSlot(slotI).getItem();
            if (stack.isEmpty() || isShulkerBox(stack)) continue;

            if (stack.getItem() == expected) {
                RekitModule.KitItem itsOwnKit = rekit.getActiveKit().get(i);
                if (isCorrectItem(stack, itsOwnKit)) continue;
                return i;
            }
        }
        return -1;
    }

    private int findEmptySlot(AbstractContainerMenu handler) {
        RekitModule rekit = EUClient.MODULE_MANAGER.getModule(RekitModule.class);
        for (int i = 0; i < 36; i++) {
            if (ignoreHotbar.getValue() && i <= 8) continue;
            int slotId = getHandlerSlot(i);
            if (handler.getSlot(slotId).getItem().isEmpty() && rekit.getActiveKit().get(i) == null) return slotId;
        }
        for (int i = 0; i < 36; i++) {
            if (ignoreHotbar.getValue() && i <= 8) continue;
            int slotId = getHandlerSlot(i);
            if (handler.getSlot(slotId).getItem().isEmpty()) return slotId;
        }
        return -1;
    }

    private int getHandlerSlot(int invSlot) {
        if (invSlot >= 0 && invSlot <= 8) return 36 + invSlot;
        if (invSlot >= 9 && invSlot <= 35) return invSlot;
        return -1;
    }

    private boolean isShulkerBox(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }
}
