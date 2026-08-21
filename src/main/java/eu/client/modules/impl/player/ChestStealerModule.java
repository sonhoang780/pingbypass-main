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
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

@RegisterModule(name = "ChestStealer", description = "Automatically steals, dumps, or drops items to and from an open container.", category = Module.Category.PLAYER)
public class ChestStealerModule extends Module {
    public BooleanSetting steal = new BooleanSetting("Steal", "Move matching items FROM the open container INTO your inventory.", true);
    public ModeSetting stealMode = new ModeSetting("Mode", "WhiteList = steal items IN the list. BlackList = steal items NOT in the list. All = steal everything.", new BooleanSetting.Visibility(steal, true), "All", new String[]{"WhiteList", "BlackList", "All"});
    public WhitelistSetting stealWhitelist = new WhitelistSetting("List", "Items to compare against.", new BooleanSetting.Visibility(steal, true), WhitelistSetting.Type.ITEMS);

    public BooleanSetting dump = new BooleanSetting("Dump", "Move matching items FROM your inventory INTO the open container.", false);
    public ModeSetting dumpMode = new ModeSetting("Mode", "WhiteList = dump items IN the list. BlackList = dump items NOT in the list. All = dump everything.", new BooleanSetting.Visibility(dump, true), "WhiteList", new String[]{"WhiteList", "BlackList", "All"});
    public WhitelistSetting dumpWhitelist = new WhitelistSetting("List", "Items to compare against.", new BooleanSetting.Visibility(dump, true), WhitelistSetting.Type.ITEMS);

    public BooleanSetting drop = new BooleanSetting("Drop", "Throw matching items OUT of your inventory (Q / THROW).", false);
    
    public ModeSetting interact = new ModeSetting("Interact", "Which of YOUR slots Dump/Drop act on: Hotbar (0-8), Inventory (9-35), or Both.", "Both", new String[]{"Hotbar", "Inventory", "Both"});
    public NumberSetting delay = new NumberSetting("Delay", "Tick delay between action passes.", 1, 0, 20);
    public NumberSetting actionsPerTick = new NumberSetting("ActionsPerTick", "Max slot actions per pass.", 5, 1, 20);
    public BooleanSetting buttonMode = new BooleanSetting("ButtonMode", "Adds vanilla Steal/Dump/Drop buttons at the top-right of the container/inventory GUI.", false);
    public BooleanSetting ignoreCustomName = new BooleanSetting("IgnoreCustomName", "Skip any container with a custom (non-vanilla) title such as a shop GUI, except shulker boxes.", false);
    
    private int ticks = 0;

    public static volatile long lastContainerActionMs = 0;

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (mc.player.isCreative()) return;
        if (buttonMode.getValue()) return;
        if (!mc.player.containerMenu.getCarried().isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - RekitModule.lastContainerActionMs < 200 || now - InventorySorterModule.lastContainerActionMs < 200) return;

        if (ticks < delay.getValue().intValue()) { ticks++; return; }
        ticks = 0;

        int budget = actionsPerTick.getValue().intValue();
        boolean externalGui = mc.gui.screen() instanceof AbstractContainerScreen && !(mc.gui.screen() instanceof InventoryScreen);
        if (externalGui && ignoreCustomName.getValue() && hasCustomContainerTitle() && !(mc.player.containerMenu instanceof net.minecraft.world.inventory.ShulkerBoxMenu)) return;
        if (steal.getValue() && externalGui && budget > 0) budget -= runSteal(stealMode.getValue(), stealWhitelist, budget);
        if (dump.getValue() && externalGui && budget > 0) budget -= runDump(dumpMode.getValue(), dumpWhitelist, budget);
        if (drop.getValue() && budget > 0) budget -= runDrop(budget);
    }

    public void triggerSteal() { if (canAct(true)) runSteal(stealMode.getValue(), stealWhitelist, actionsPerTick.getValue().intValue()); }
    public void triggerDump()  { if (canAct(true)) runDump(dumpMode.getValue(), dumpWhitelist, actionsPerTick.getValue().intValue()); }
    public void triggerDrop()  { if (canAct(false)) runDrop(actionsPerTick.getValue().intValue()); }

    private boolean hasCustomContainerTitle() {
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) return false;
    net.minecraft.network.chat.Component title = screen.getTitle();
    if (title == null) return false;
    return !(title.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents);
    }

    private boolean canAct(boolean requireContainer) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return false;
        if (mc.player.isCreative()) return false;
        if (!mc.player.containerMenu.getCarried().isEmpty()) return false;
        if (requireContainer) {
            boolean isContainer = mc.gui.screen() instanceof AbstractContainerScreen && !(mc.gui.screen() instanceof InventoryScreen);
            boolean isInventory = mc.gui.screen() instanceof InventoryScreen;
            if (!isContainer && !isInventory) return false;
        }
        return true;
    }

    private int runSteal(String modeVal, WhitelistSetting wl, int budget) {
        if (mc.gui.screen() instanceof InventoryScreen) return 0;
        int containerId = mc.player.containerMenu.containerId;
        int containerSlotCount = mc.player.containerMenu.slots.size() - 36;
        int actions = 0;
        for (int slot = 0; slot < containerSlotCount && actions < budget; slot++) {
            ItemStack stack = mc.player.containerMenu.slots.get(slot).getItem();
            if (stack.isEmpty()) continue;
            if (matches(stack, modeVal, wl)) { quickMove(containerId, slot); actions++; }
        }
        return actions;
    }

    private int runDump(String modeVal, WhitelistSetting wl, int budget) {
        if (mc.gui.screen() instanceof InventoryScreen) return 0;
        int containerId = mc.player.containerMenu.containerId;
        int actions = 0;
        for (int invSlot = 0; invSlot < 36 && actions < budget; invSlot++) {
            if (!inInteractRange(invSlot)) continue;
            ItemStack stack = mc.player.getInventory().getItem(invSlot);
            if (stack.isEmpty()) continue;
            if (matches(stack, modeVal, wl)) {
                int handlerSlot = invToHandlerSlot(invSlot);
                if (handlerSlot < 0) continue;
                quickMove(containerId, handlerSlot);
                actions++;
            }
        }
        return actions;
    }

    private int runDrop(int budget) {
        int containerId = mc.player.containerMenu.containerId;
        int actions = 0;
        for (int invSlot = 0; invSlot < 36 && actions < budget; invSlot++) {
            if (!inInteractRange(invSlot)) continue;
            ItemStack stack = mc.player.getInventory().getItem(invSlot);
            if (stack.isEmpty()) continue;
            int handlerSlot = invToHandlerSlot(invSlot);
            if (handlerSlot < 0) continue;
            throwSlot(containerId, handlerSlot);
            actions++;
        }
        return actions;
    }

    private void quickMove(int containerId, int slot) {
        mc.gameMode.handleContainerInput(containerId, slot, 0, ContainerInput.QUICK_MOVE, mc.player);
        lastContainerActionMs = System.currentTimeMillis();
    }

    private void throwSlot(int containerId, int slot) {
        mc.gameMode.handleContainerInput(containerId, slot, 1, ContainerInput.THROW, mc.player);
        lastContainerActionMs = System.currentTimeMillis();
    }

    private boolean matches(ItemStack stack, String modeVal, WhitelistSetting wl) {
        boolean listed = wl.isWhitelistContains(stack.getItem());
        return switch (modeVal) {
            case "All" -> true;
            case "BlackList" -> !listed;
            default -> listed;
        };
    }

    private boolean inInteractRange(int invSlot) {
        return switch (interact.getValue()) {
            case "Hotbar" -> invSlot <= 8;
            case "Inventory" -> invSlot >= 9;
            default -> true;
        };
    }

    private int invToHandlerSlot(int invSlot) {
        net.minecraft.world.Container playerInv = mc.player.getInventory();
        for (net.minecraft.world.inventory.Slot slot : mc.player.containerMenu.slots) {
            if (slot.container == playerInv && slot.getContainerSlot() == invSlot) return slot.index;
        }
        return -1;
    }

    @Override
    public String getMetaData() {
        StringBuilder sb = new StringBuilder();
        if (steal.getValue()) sb.append("Steal");
        if (dump.getValue()) sb.append(sb.length() > 0 ? "/Dump" : "Dump");
        if (drop.getValue()) sb.append(sb.length() > 0 ? "/Drop" : "Drop");
        return sb.toString();
    }
}