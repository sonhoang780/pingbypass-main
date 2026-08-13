package eu.client.commands.impl;

import eu.client.commands.Command;
import eu.client.commands.RegisterCommand;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

// Ported from example-addon-master's ItemDropCommand -- drops items from your own inventory
// without needing to open it (".itemdrop all" / ".itemdrop <item>").
@RegisterCommand(name = "itemdrop", tag = "ItemDrop", description = "Drops items from your inventory.", syntax = "<all|item name>")
public class ItemDropCommand extends Command {
    @Override
    public List<String> getSuggestions(String[] args) {
        if (args.length == 0) {
            List<String> options = new ArrayList<>(List.of("all"));
            for (Item item : BuiltInRegistries.ITEM) options.add(BuiltInRegistries.ITEM.getKey(item).getPath());
            return options;
        }
        return List.of();
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) { messageSyntax(); return; }

        if (args[0].equalsIgnoreCase("all")) {
            dropAllItems();
            return;
        }

        String itemName = String.join(" ", args);
        dropSpecificItem(itemName);
    }

    private void dropAllItems() {
        if (mc.player == null || mc.gameMode == null) return;
        int containerId = mc.player.containerMenu.containerId;

        for (int invSlot = 0; invSlot < 36; invSlot++) {
            ItemStack stack = mc.player.getInventory().getItem(invSlot);
            if (!stack.isEmpty()) {
                mc.gameMode.handleContainerInput(containerId, invToHandlerSlot(invSlot), 1, ContainerInput.THROW, mc.player);
            }
        }
    }

    private void dropSpecificItem(String itemName) {
        if (mc.player == null || mc.gameMode == null) return;
        int containerId = mc.player.containerMenu.containerId;

        for (int invSlot = 0; invSlot < 36; invSlot++) {
            ItemStack stack = mc.player.getInventory().getItem(invSlot);
            if (stack.isEmpty()) continue;

            String key = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            String customName = stack.getHoverName().getString();

            // Matches "minecraft:stone", "stone", or a custom (anvil-renamed) name.
            if (key.equalsIgnoreCase(itemName) || key.substring(key.indexOf(':') + 1).equalsIgnoreCase(itemName) || customName.equalsIgnoreCase(itemName)) {
                mc.gameMode.handleContainerInput(containerId, invToHandlerSlot(invSlot), 1, ContainerInput.THROW, mc.player);
            }
        }
    }

    private static int invToHandlerSlot(int invSlot) {
        return invSlot <= 8 ? 36 + invSlot : invSlot;
    }
}
