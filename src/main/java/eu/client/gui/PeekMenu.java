package eu.client.gui;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

// Read-only 3-row chest menu for .peek. clicked() + quickMoveStack() are neutered so no click,
// shift-click, hotbar-swap or drop can move any item or reach the server.
public class PeekMenu extends ChestMenu {
    public PeekMenu(int containerId, Inventory playerInventory, Container container) {
        super(MenuType.GENERIC_9x3, containerId, playerInventory, container, 3);
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput type, Player player) {
        // no-op: read-only
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY; // read-only: block shift-click transfers
    }
}