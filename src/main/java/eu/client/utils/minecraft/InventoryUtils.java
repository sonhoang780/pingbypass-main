package eu.client.utils.minecraft;

import eu.client.EUClient;
import eu.client.mixins.accessors.ClientPlayerInteractionManagerAccessor;
import eu.client.utils.IMinecraft;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.inventory.ContainerInput;

public class InventoryUtils implements IMinecraft {
    public static String[] SWITCH_MODES = new String[]{"None", "Normal", "Silent", "AltPickup", "AltSwap"};
    public static String[] SWAP_MODES = new String[]{"Pickup", "Swap"};

    public static int HOTBAR_START = 0;
    public static int HOTBAR_END = 8;

    public static int INVENTORY_START = 9;
    public static int INVENTORY_END = 35;

    public static void switchSlot(String mode, int slot, int previousSlot) {
        if (mode.equalsIgnoreCase("None")) return;
        if (slot == -1 || previousSlot == -1 || slot == EUClient.POSITION_MANAGER.getServerSlot()) return;

        switch (mode) {
            case "Normal" -> {
                mc.player.getInventory().setSelectedSlot(slot);
                ((ClientPlayerInteractionManagerAccessor) mc.gameMode).invokeSyncSelectedSlot();
            }
            case "Silent" -> mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
            case "AltPickup" -> swap("Pickup", slot, previousSlot);
            case "AltSwap" -> swap("Swap", slot, previousSlot);
        }
    }

    public static void switchBack(String mode, int slot, int previousSlot) {
        if (mode.equalsIgnoreCase("None")) return;
        if (previousSlot == -1) return;

        switch (mode) {
            case "Silent" -> {
                if (previousSlot == EUClient.POSITION_MANAGER.getServerSlot()) return;
                mc.getConnection().send(new ServerboundSetCarriedItemPacket(previousSlot));
            }
            case "AltPickup" -> swap("Pickup", slot, previousSlot);
            case "AltSwap" -> swap("Swap", slot, previousSlot);
        }
    }

    public static void swap(String mode, int slot, int targetSlot) {
        switch (mode) {
            case "Pickup" -> {
                mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, indexToSlot(slot), 0, ContainerInput.PICKUP, mc.player);
                mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, indexToSlot(targetSlot), 0, ContainerInput.PICKUP, mc.player);
                mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, indexToSlot(slot), 0, ContainerInput.PICKUP, mc.player);
            }
            case "Swap" -> {
                mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, indexToSlot(slot), targetSlot, ContainerInput.SWAP, mc.player);
            }
        }
    }

    public static int indexToSlot(int index) {
        if (index >= 0 && index <= 8) return 36 + index;
        return index;
    }

    public static int find(Item item) { return find(item, HOTBAR_START, INVENTORY_END); }
    public static int findHotbar(Item item) { return find(item, HOTBAR_START, HOTBAR_END); }
    public static int findInventory(Item item) { return find(item, INVENTORY_START, INVENTORY_END); }
    public static int find(Item item, int start, int end) {
        for (int i = end; i >= start; i--) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() != item) continue;

            return i;
        }

        return -1;
    }

    public static int find(Class<? extends Item> item) { return find(item, HOTBAR_START, INVENTORY_END); }
    public static int findHotbar(Class<? extends Item> item) { return find(item, HOTBAR_START, HOTBAR_END); }
    public static int findInventory(Class<? extends Item> item) { return find(item, INVENTORY_START, INVENTORY_END); }
    public static int find(Class<? extends Item> item, int start, int end) {
        for (int i = end; i >= start; i--) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.getItem().getClass().isAssignableFrom(item)) continue;

            return i;
        }

        return -1;
    }

    public static int findInventory(Item item, int count) {
        for (int i = INVENTORY_END; i >= INVENTORY_START; i--) {
            ItemStack stack = mc.player.getInventory().getItem(i);

            if (stack.getItem() != item) continue;
            if (mc.player.getInventory().getItem(i).getCount() < count) continue;

            return i;
        }

        return -1;
    }

    public static int findHardestBlock(int start, int end) {
        float bestHardness = -1;
        int bestSlot = -1;

        for (int i = start; i <= end; i++) {
            if (!(mc.player.getInventory().getItem(i).getItem() instanceof BlockItem item)) continue;

            float hardness = item.getBlock().defaultDestroyTime();
            if (hardness == -1) return i;
            if (hardness > bestHardness) {
                bestHardness = hardness;
                bestSlot = i;
            }
        }

        return bestSlot;
    }

    public static int findFastestItem(BlockState blockState, int start, int end) {
        double bestScore = -1;
        int bestSlot = -1;

        for (int i = start; i <= end; i++) {
            double score = mc.player.getInventory().getItem(i).getItem().getDestroySpeed(mc.player.getInventory().getItem(i), blockState);

            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }

        return bestSlot;
    }

    public static int findBestSword(int start, int end) {
        int netheriteSlot = -1;
        int diamondSlot = -1;
        int ironSlot = -1;
        int goldenSlot = -1;
        int stoneSlot = -1;
        int woodenSlot = -1;

        for (int i = end; i >= start; i--) {
            ItemStack stack = mc.player.getInventory().getItem(i);

            if (stack.getItem() == Items.NETHERITE_SWORD) netheriteSlot = i;
            if (stack.getItem() == Items.DIAMOND_SWORD) diamondSlot = i;
            if (stack.getItem() == Items.IRON_SWORD) ironSlot = i;
            if (stack.getItem() == Items.GOLDEN_SWORD) goldenSlot = i;
            if (stack.getItem() == Items.STONE_SWORD) stoneSlot = i;
            if (stack.getItem() == Items.WOODEN_SWORD) woodenSlot = i;
        }

        if (netheriteSlot != -1) return netheriteSlot;
        if (diamondSlot != -1) return diamondSlot;
        if (ironSlot != -1) return ironSlot;
        if (goldenSlot != -1) return goldenSlot;
        if (stoneSlot != -1) return stoneSlot;

        return woodenSlot;
    }

    public static int findEmptySlot(int start, int end) {
        for (int i = end; i >= start; i--) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty()) continue;

            return i;
        }

        return -1;
    }

    public static boolean inInventoryScreen() {
        return mc.screen instanceof InventoryScreen || mc.screen instanceof CreativeModeInventoryScreen || mc.screen instanceof ContainerScreen || mc.screen instanceof ShulkerBoxScreen;
    }
}
