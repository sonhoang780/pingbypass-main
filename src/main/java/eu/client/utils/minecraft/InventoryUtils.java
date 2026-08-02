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
                syncSlotToClientIfProxy(slot);
            }
            case "Silent" -> mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
            case "AltPickup" -> swap("Pickup", slot, previousSlot);
            case "AltSwap" -> swap("Swap", slot, previousSlot);
        }
    }

    // switchBack()'s "Normal" case is intentionally a no-op (mirrors what a real player switching
    // slots does -- most callers, e.g. SpeedMine, want to stay on the tool after a Normal-mode
    // switch). AutoCrystal's SwapBack setting opts into actually switching back for that one caller,
    // so this stays a separate opt-in helper rather than changing switchBack's shared behavior.
    public static void switchBackNormal(int previousSlot) {
        if (previousSlot == -1 || previousSlot == EUClient.POSITION_MANAGER.getServerSlot()) return;

        mc.player.getInventory().setSelectedSlot(previousSlot);
        ((ClientPlayerInteractionManagerAccessor) mc.gameMode).invokeSyncSelectedSlot();
        syncSlotToClientIfProxy(previousSlot);
    }

    /**
     * "Normal" mode switches only change the proxy ghost player's own selected
     * slot -- the real client's hotbar is drawn from its own local selection
     * and is never told about it otherwise, so visually nothing switches.
     * Mirror it to the connected client when running on the proxy.
     */
    private static void syncSlotToClientIfProxy(int slot) {
        if (EUClient.PINGBYPASS_CONFIG == null || !EUClient.PINGBYPASS_CONFIG.isServer()) return;
        if (!eu.client.pingbypass.PingBypassFlags.proxyForwardingActive || EUClient.PROXY_SERVER == null) return;

        var packet = new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                eu.client.pingbypass.protocol.PbCustomPayload.fromPacket(
                        new eu.client.pingbypass.protocol.packets.S2CSlotSyncPacket(slot)));
        for (net.minecraft.network.Connection conn : EUClient.PROXY_SERVER.getConnections()) {
            if (conn.isConnected()) conn.send(packet);
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

        // Unlike "Normal" mode's setSelectedSlot (mirrored to the client instantly via
        // syncSlotToClientIfProxy), handleContainerInput above only applies the swap to the
        // PROXY's own local menu prediction. The real client's own inventory only learns about
        // it once the real server's ClientboundContainerSetContentPacket/SetSlotPacket confirms
        // and gets forwarded back -- a full real-network round trip. If another module (e.g.
        // AutoCrystal's Normal switch) reselects a slot on the client's side inside that window,
        // the client is acting on stale slot contents and desyncs from what the real server
        // actually has, showing up as "can't crystal" / "block doesn't break" / rubberband when
        // combined with other proxy-side inventory switches. Broadcast the proxy's own
        // just-applied prediction to the client immediately instead of waiting on that RTT.
        syncInventoryToClientIfProxy();
    }

    private static void syncInventoryToClientIfProxy() {
        if (EUClient.PINGBYPASS_CONFIG == null || !EUClient.PINGBYPASS_CONFIG.isServer()) return;
        if (!eu.client.pingbypass.PingBypassFlags.proxyForwardingActive || EUClient.PROXY_SERVER == null) return;

        var packet = new net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket(
                mc.player.containerMenu.containerId,
                mc.player.containerMenu.incrementStateId(),
                mc.player.containerMenu.getItems(),
                mc.player.containerMenu.getCarried());
        for (net.minecraft.network.Connection conn : EUClient.PROXY_SERVER.getConnections()) {
            if (conn.isConnected()) conn.send(packet);
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
