package eu.client.modules.impl.miscellaneous;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.WhitelistSetting;
import eu.client.utils.minecraft.InventoryUtils;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.world.item.Item;
import net.minecraft.world.inventory.ContainerInput;

@RegisterModule(name = "InventoryCleaner", description = "Drops unwanted items from inventory.", category = Module.Category.MISCELLANEOUS)
public class InventoryCleanerModule extends Module {
    public BooleanSetting hotbar = new BooleanSetting("Hotbar", "Include the hotbar when cleaning the inventory.", false);
    public BooleanSetting require = new BooleanSetting("Require", "Only clean inventory when you are in the inventory screen.", false);
    public WhitelistSetting whitelist = new WhitelistSetting("Whitelist", "The list of whitelisted items.", WhitelistSetting.Type.ITEMS);

    private int ticks = 0;

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (mc.player == null || mc.level == null) return;

        if (ticks <= 0) {
            if (mc.screen instanceof CreativeModeInventoryScreen || mc.screen instanceof ContainerScreen || mc.screen instanceof ShulkerBoxScreen) return;
            if (!(mc.screen instanceof InventoryScreen) && require.getValue() || mc.screen instanceof InventoryScreen && !require.getValue())
                return;

            for (int i = hotbar.getValue() ? 0 : 9; i < 36; i++) {
                if (mc.player.getInventory().getItem(i).isEmpty()) continue;

                Item item = mc.player.getInventory().getItem(i).getItem();
                // Was "keep listed items, drop everything else" -- inverted to match the setting's
                // name: Whitelist now means "only these get dropped", everything not listed stays
                // (an empty whitelist means nothing is targeted, so nothing gets dropped).
                if (!whitelist.isWhitelistContains(item)) continue;

                mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, InventoryUtils.indexToSlot(i), 0, ContainerInput.PICKUP, mc.player);
                mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, -999, 0, ContainerInput.PICKUP, mc.player);
                ticks = 2 + EUClient.SERVER_MANAGER.getPingDelay();
            }
        }

        ticks--;
    }

    @Override
    public String getMetaData() {
        return String.valueOf(whitelist.getWhitelist().size());
    }
}
