package eu.client.gui;

import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

// Tag subclass of the vanilla chest screen so a mixin can recognize a .peek preview and block its
// container clicks (fully read-only). No overrides needed.
public class PeekScreen extends ContainerScreen {
    public PeekScreen(PeekMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }
}