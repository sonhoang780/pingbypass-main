package eu.client.commands.impl;

import eu.client.EUClient;
import eu.client.commands.Command;
import eu.client.commands.RegisterCommand;
import eu.client.gui.PeekMenu;
import eu.client.gui.PeekScreen;
import eu.client.utils.minecraft.EChestMemory;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.List;

// Ported in spirit from Meteor's PeekCommand. Opens a client-side, read-only chest GUI:
//   - If you hold a shulker box (or any CONTAINER item), it shows that item's contents.
//   - Otherwise it shows the last ender chest you opened this session (EChestMemory).
// Nothing is sent to the server; the GUI is a local read-only PeekMenu over a SimpleContainer.
@RegisterCommand(name = "peek", tag = "Peek", description = "Peeks a shulker box held in hand, or your last-opened ender chest, without opening it.", syntax = "")
public class PeekCommand extends Command {
    @Override
    public List<String> getSuggestions(String[] args) {
        return List.of();
    }

    @Override
    public void execute(String[] args) {
        if (mc.player == null) return;

        // Make sure the ender chest recorder is running (lazy, one-time).
        EChestMemory.init();

        // 1) Prefer a container item (shulker box, etc.) held in hand -- main hand first, then offhand.
        ItemStack stack = mc.player.getMainHandItem();
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) {
            stack = mc.player.getOffhandItem();
            contents = stack.get(DataComponents.CONTAINER);
        }

        if (contents != null) {
            NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
            contents.copyInto(items);
            openPreview(items, stack.getHoverName());
            return;
        }

        // 2) Nothing in hand -> fall back to the last-opened ender chest, if remembered.
        if (EChestMemory.hasItems()) {
            openPreview(EChestMemory.getItems(), Component.literal("Ender Chest (remembered)"));
            return;
        }

        // 3) Neither available.
        EUClient.CHAT_MANAGER.tagged("Hold a shulker box to peek it, or open your ender chest once so it can be remembered.", getTag(), getName());
    }

    private void openPreview(NonNullList<ItemStack> items, Component title) {
        SimpleContainer preview = new SimpleContainer(27);
        for (int i = 0; i < 27 && i < items.size(); i++) {
            preview.setItem(i, items.get(i).copy());
        }
        // Defer to next tick so chat closing (setScreen(null)) doesn't instantly close ours.
        mc.execute(() -> {
            PeekMenu menu = new PeekMenu(0, mc.player.getInventory(), preview);
            // Route client-side clicks to our menu; the MultiPlayerGameMode mixin blocks the click
            // packet entirely while a PeekScreen is open, so nothing ever reaches the server.
            mc.player.containerMenu = menu;
            mc.gui.setScreen(new PeekScreen(menu, mc.player.getInventory(), title));
        });
    }
}