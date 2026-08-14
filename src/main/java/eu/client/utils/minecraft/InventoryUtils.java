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

    // What the SERVER's held slot was at the moment we silently switched away from it, keyed by the
    // slot we switched TO. Every caller computes previousSlot as mc.player.getInventory()
    // .getSelectedSlot() -- the CLIENT's slot, which a silent switch never touches -- and hands that
    // same value to switchBack(). That's only the right thing to restore to when this module is the
    // only one holding a silent switch open, and it routinely isn't: SpeedMine (Strict/DoubleMine)
    // deliberately defers its own switchBack by 100ms, and Surround/SelfTrap hold one across a whole
    // placement burst. AutoCrystal placing ~10 crystals/s in that window restored the server to the
    // CLIENT slot each time, yanking the server off the item the other module was still relying on;
    // that module's own (later) switchBack then restored to a slot that had since become stale, and
    // the two kept trading the server's held slot back and forth -- the reported "slot of 2 items
    // keeps swapping" / "3 items' slots jumble into each other" with Surround + AutoCrystal, and the
    // AutoCrystal + SpeedMine flicker. Restore to what the server was ACTUALLY on, per switch.
    private static final java.util.Map<Integer, Integer> SILENT_RESTORE = new java.util.concurrent.ConcurrentHashMap<>();

    // Non-empty exactly while ANY module (AutoCrystal/Surround/SelfTrap/SpeedMine/...) has a
    // Silent-mode switch open on the proxy's server-visible slot. PbPlayHandler.handleSetCarriedItem
    // uses this to hold off applying the REAL client's own SetCarriedItem packets to the local
    // inventory mirror while one of those is in flight -- without it, a stray SetCarriedItem from
    // the real client (scroll wheel, number key, or just a queued packet) lands on the netty thread
    // mid-switch and stomps the mirror back to whatever the real client is holding, which every
    // module currently mid-switch reads from -- visible as a slot flickering to the real client's
    // held item. Previously only SpeedMine had its own hand-rolled guard for this; every other
    // Silent-switch user (AutoCrystal, Surround, SelfTrap) had none.
    // Deliberately does NOT include the deferred restores below: those keep a hold open for up to
    // RESTORE_DWELL_MS AFTER the action is over, and AutoCrystal re-arms one every ~50ms, so
    // counting them here left this permanently true -- which made this guard swallow every
    // SetCarriedItem the real client sent for as long as AutoCrystal was on (the player couldn't
    // change slots at all: no eating, no placing). The guard is only about the switch/act/restore
    // window itself, which is exactly what SILENT_RESTORE spans.
    public static boolean hasActiveSilentSwitch() {
        return !SILENT_RESTORE.isEmpty();
    }

    /**
     * The slot the server rests on when nobody is borrowing the player's hand: the player's own
     * selection, full stop. NOT the server's current slot -- that is the thing being borrowed.
     * <p>
     * Reading getServerSlot() here (even with an "is it somebody's registered hold?" check against
     * the maps below) captured whatever OTHER module happened to be holding the hand: SpeedMine's
     * proxy paths, its DoubleMine/FarReach late-break latch (Secondary.hold/release) and
     * legacyStart() all send raw ServerboundSetCarriedItemPacket without going through this class
     * at all, so their holds are invisible to the maps but perfectly visible in getServerSlot().
     * AutoCrystal switching to the crystal slot during one of those recorded the PICKAXE as its
     * restore target and parked the server there -- the reported "crystal slot flickers into the
     * pickaxe". Every switcher here re-asserts its own slot before each action, so handing the hand
     * back to the player is always safe; the module that still wants it takes it again.
     */
    private static int restingSlot() {
        return mc.player == null ? EUClient.POSITION_MANAGER.getServerSlot() : mc.player.getInventory().getSelectedSlot();
    }

    // ---- deferred restore: hold a silent switch open across a placement BURST ----------------
    //
    // Every Silent caller used to round-trip the server's held slot back after EVERY single
    // action. AutoCrystal at its default place rate therefore emitted, once per tick, forever:
    //     SetCarriedItem slot=3 / UseItemOn MAIN_HAND seq=N / SetCarriedItem slot=2
    // (verbatim from the C2S dump in run/logs/latest.log, ~50ms apart with no gaps). No human hand
    // toggles the carried slot 20x/s. The server (kingmc.vn) answers that by re-asserting its own
    // view of the held slot: a ClientboundContainerSetSlotPacket writing the OTHER slot's item
    // (the golden apple the player is really holding) into the crystal slot, then a full
    // ClientboundContainerSetContentPacket putting the crystal back -- 2-3x/s, in lockstep with
    // this cycle. That is the reported "hotbar slot flickers into a different item". Both writers
    // are vanilla ClientPacketListener handlers applying genuine server packets, so there is
    // nothing client-side to guard: the only fix is to stop provoking it.
    //
    // So: switchBack arms a deferred restore instead of sending immediately, and the next
    // switchSlot to the SAME slot cancels it -- at which point the `slot == getServerSlot()`
    // early-out below sends nothing at all, because the server is still holding that slot. A burst
    // of N actions now costs 2 packets instead of 2N, and the restore still lands once the burst
    // stops, so no caller is left stranded on the wrong slot (the hazard SpeedMine documents:
    // a server that keeps thinking a pickaxe is in hand places nothing).
    //
    // Only the packet-only paths (Silent, and a Normal that switchSlot degraded to Silent
    // off-thread) defer. Alt*'s restore is a container click, a different animal with its own
    // ordering constraints -- left immediate.
    // Keyed by the slot being HELD, not one global pending -- with a single pendingSlot, AutoCrystal
    // and SpeedMine holding their own slots at overlapping times each flushed the other's hold to
    // take over the single field, and the server's carried slot ping-ponged between crystal, pickaxe
    // and the player's own slot. Per-slot, neither one can stomp the other's bookkeeping.
    // clientSlot is what the PLAYER had selected when this was armed, so the flush can tell "restore
    // what we borrowed" from "the player has since moved on, follow them instead".
    private record Pending(int target, int clientSlot, long time) {}
    private static final java.util.Map<Integer, Pending> PENDING = new java.util.concurrent.ConcurrentHashMap<>();

    // A player switching back a quarter second after their last action is unremarkable; at
    // AutoCrystal's ~50ms place interval it collapses a whole burst into one switch pair. It is
    // only ever a CEILING now -- anything the player themselves does flushes early (see below).
    // ponytail: fixed dwell, not a setting -- raise it if a server still reacts to the rate.
    private static final long RESTORE_DWELL_MS = 250L;

    /** Called once per tick (WorldManager.onTick) so a burst that simply stops still restores. */
    public static void tickPendingRestore() {
        if (mc.player == null) { PENDING.clear(); return; }

        // The player's own click is resolved LATER in this same Minecraft.tick() (WorldManager posts
        // TickEvent at Minecraft.tick() HEAD, vanilla's handleKeybinds/startUseItem runs after it),
        // and the server resolves the resulting UseItemOn/UseItem against ITS carried slot -- so a
        // hold we're sitting on has to go back FIRST or their block placement / golden apple runs
        // with our crystal (or SpeedMine's pickaxe) in hand. The dwell alone can never do this:
        // AutoCrystal re-arms it every ~50ms, so it never expires while the module is on.
        boolean using = mc.options.keyUse.isDown() || mc.player.isUsingItem();
        if (using || mc.options.keyAttack.isDown()) flushPendingRestores();

        // Not just our own PENDING holds: SpeedMine parks the server on the pickaxe with raw
        // packets of its own (Secondary.hold, legacyStart, the proxy fireBreakBurst dance) that
        // nothing here tracks or ever gives back on a timer. The player's hand is theirs whenever
        // they are actually trying to use it -- unconditionally reassert it, unless a switch is
        // open right now (mid switch/act/restore sequence, which must not be cut in half).
        if (using && SILENT_RESTORE.isEmpty() && mc.getConnection() != null) {
            int client = mc.player.getInventory().getSelectedSlot();
            if (client != EUClient.POSITION_MANAGER.getServerSlot()) {
                mc.getConnection().send(new ServerboundSetCarriedItemPacket(client));
            }
        }

        if (PENDING.isEmpty()) return;

        long now = System.currentTimeMillis();
        for (java.util.Map.Entry<Integer, Pending> entry : PENDING.entrySet()) {
            if (now - entry.getValue().time() >= RESTORE_DWELL_MS) flushPendingRestore(entry.getKey());
        }
    }

    private static void armPendingRestore(int slot, int restoreTarget) {
        // Somebody else already moved the server off our slot (SpeedMine's raw switches, another
        // module's switchSlot). The hand is no longer ours to give back, and doing it anyway yanked
        // THEM off it -- they re-switch, we restore again, and the carried slot ping-pongs every
        // tick with both modules running. Whoever holds it now is responsible for restoring it.
        if (slot != EUClient.POSITION_MANAGER.getServerSlot()) return;

        PENDING.put(slot, new Pending(restoreTarget,
                mc.player == null ? -1 : mc.player.getInventory().getSelectedSlot(),
                System.currentTimeMillis()));
    }

    /**
     * Restore everything we're holding, now. Called whenever something that is NOT a same-slot
     * re-switch needs the server's real carried slot: a module toggling off (or the server would
     * stay parked on the crystal slot and the player couldn't eat until they cycled slots by hand),
     * and the real client's own interaction arriving on the proxy.
     */
    public static void flushPendingRestores() {
        // Oldest hold first, so the most recent one has the last word on where the server ends up.
        PENDING.entrySet().stream()
                .sorted(java.util.Comparator.comparingLong(entry -> entry.getValue().time()))
                .map(java.util.Map.Entry::getKey)
                .toList()
                .forEach(InventoryUtils::flushPendingRestore);
    }

    private static void flushPendingRestore(int slot) {
        Pending pending = PENDING.remove(slot);
        if (pending == null || mc.getConnection() == null) return;
        // Same ownership rule as armPendingRestore: the hand moved on without us, leave it alone.
        if (slot != EUClient.POSITION_MANAGER.getServerSlot()) return;

        int clientSlot = mc.player == null ? -1 : mc.player.getInventory().getSelectedSlot();
        // The player changed their own selection while we were holding: follow them instead of
        // yanking the server back to a target captured before they did. Locally their vanilla
        // SetCarriedItem already went out so this early-outs below; on the proxy it's what lands it.
        int target = clientSlot != -1 && clientSlot != pending.clientSlot() ? clientSlot : pending.target();

        if (target == -1 || target == EUClient.POSITION_MANAGER.getServerSlot()) return;

        mc.getConnection().send(new ServerboundSetCarriedItemPacket(target));
    }

    /**
     * @return true when the server is now (or already was) holding {@code slot} -- false when the
     *         switch was refused and the caller must not go ahead with whatever it wanted the item
     *         for. "None" counts as success: those callers deliberately use whatever's in hand.
     */
    public static boolean switchSlot(String mode, int slot, int previousSlot) {
        if (mode.equalsIgnoreCase("None")) return true;
        if (slot == -1 || previousSlot == -1) return false;

        // Before the serverSlot early-out, not after: re-entering a hold we already own has to
        // disarm its pending restore, or that restore fires mid-burst and we're back to the
        // per-action oscillation this exists to kill. Switching to a DIFFERENT slot restores the
        // previous hold first (unchanged from the immediate-restore behaviour).
        // Only our OWN hold is cancelled here. Flushing everyone else's first (what the single
        // global pendingSlot did) just emits a restore packet that the switch below immediately
        // overrides -- their own dwell lands them on the resting slot afterwards anyway.
        PENDING.remove(slot);

        if (slot == EUClient.POSITION_MANAGER.getServerSlot()) return true;

        // Surround/SelfTrap react to a crystal spawn straight off the netty IO thread (our
        // packet-receive mixin posts PacketReceiveEvent from Connection.channelRead0, before
        // vanilla hands off to the main thread -- see WorldUtils.destroyCrystals' own note about
        // the CME that same fact used to cause). "Normal" writes mc.player's selected slot and
        // Alt* replays a container click through mc.gameMode: both mutate client-side inventory
        // state the main thread is concurrently ticking and rendering, which is where the visible
        // item duplication/jumbling comes from. Only the packet-only path is safe off-thread, so
        // degrade to it -- server-side the effect is identical, and it costs no latency (the whole
        // point of reacting on this thread).
        if (!mc.isSameThread() && !mode.equalsIgnoreCase("Silent")) {
            // Alt* deliberately searches the FULL inventory, and a slot outside the hotbar can't be
            // expressed as a held-slot packet at all -- refuse rather than place with the wrong item.
            if (slot > HOTBAR_END) return false;
            mode = "Silent";
        }

        switch (mode) {
            case "Normal" -> {
                mc.player.getInventory().setSelectedSlot(slot);
                ((ClientPlayerInteractionManagerAccessor) mc.gameMode).invokeSyncSelectedSlot();
                syncSlotToClientIfProxy(slot);
            }
            case "Silent" -> {
                SILENT_RESTORE.put(slot, restingSlot());
                mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
            }
            // Chained the same way "Silent" is: record what should be restored TO before doing
            // the swap, keyed by target slot, so switchBack picks up the freshest value instead
            // of whatever stale previousSlot the caller captured before some other module's
            // switch/restore already moved things around in between.
            case "AltPickup" -> {
                SILENT_RESTORE.put(slot, previousSlot);
                swap("Pickup", slot, previousSlot);
            }
            case "AltSwap" -> {
                SILENT_RESTORE.put(slot, previousSlot);
                swap("Swap", slot, previousSlot);
            }
        }

        return true;
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

        // Keyed off `slot`, so this also picks up a switch that switchSlot() degraded to Silent
        // off-thread -- without it a degraded "Normal" (whose switchBack case is a deliberate
        // no-op) would strand the server on the block slot forever. AltPickup/AltSwap also chain
        // through this now (see switchSlot's own note on those cases) -- `remembered` there means
        // "the slot index to swap back into", not a carried-item slot, so which packet/action it
        // resolves to still has to route through `mode` below rather than assuming a carried-item
        // packet like the "Silent"/degraded-Normal case does.
        Integer remembered = SILENT_RESTORE.remove(slot);
        int restoreTarget = remembered != null ? remembered : previousSlot;

        switch (mode) {
            case "Silent" -> {
                if (restoreTarget == EUClient.POSITION_MANAGER.getServerSlot()) return;
                armPendingRestore(slot, restoreTarget);
            }
            // Off-thread these mutate the container menu concurrently with the main thread -- see
            // switchSlot's own note. switchSlot already refused to make the switch in that case.
            case "AltPickup" -> { if (mc.isSameThread()) swap("Pickup", slot, restoreTarget); }
            case "AltSwap" -> { if (mc.isSameThread()) swap("Swap", slot, restoreTarget); }
            default -> {
                // A switch that degraded to Silent off-thread (e.g. a "Normal" caller) only ever
                // shows up here via `remembered` -- restore it the same way Silent does, since
                // that's what was actually sent, regardless of the mode string the caller passes.
                if (remembered != null) {
                    if (restoreTarget == EUClient.POSITION_MANAGER.getServerSlot()) return;
                    armPendingRestore(slot, restoreTarget);
                }
            }
        }
    }

    /**
     * Swaps an inventory item with an EQUIPMENT slot (armor 5..8 head->feet, offhand 45).
     * <p>
     * swap() can't express this: it runs both of its arguments through indexToSlot(), which maps
     * 0..8 to the hotbar (6 -> 42), so the chest armor slot's own container number is unreachable
     * through it. Equipment slots are already container numbers and must be passed through raw.
     * The three-click pickup shape is deliberate -- it leaves the displaced item (e.g. the elytra
     * this took off) in exactly {@code invIndex}, so the caller can put it back with the same call.
     */
    public static void swapEquipment(int invIndex, int equipmentSlot) {
        click(indexToSlot(invIndex), 0, ContainerInput.PICKUP);
        click(equipmentSlot, 0, ContainerInput.PICKUP);
        click(indexToSlot(invIndex), 0, ContainerInput.PICKUP);
    }

    public static void swap(String mode, int slot, int targetSlot) {
        switch (mode) {
            case "Pickup" -> {
                click(indexToSlot(slot), 0, ContainerInput.PICKUP);
                click(indexToSlot(targetSlot), 0, ContainerInput.PICKUP);
                click(indexToSlot(slot), 0, ContainerInput.PICKUP);
            }
            case "Swap" -> click(indexToSlot(slot), targetSlot, ContainerInput.SWAP);
        }
    }

    // Was: apply the click to the proxy's own menu, then separately broadcast the proxy's
    // resulting FULL container snapshot (ClientboundContainerSetContentPacket) to the client.
    // That snapshot only reflects the proxy's own prediction -- any slot the CLIENT had already
    // mispredicted on its own, that this exact click didn't happen to also touch, was never
    // corrected by it, and the real server's own broadcastChanges() only ever diffs against the
    // slots ITS shadow (which tracks the PROXY, not the client) saw change -- so that
    // mispredicted slot (and whatever enchantments/NBT it carries) stayed wrong indefinitely.
    // Matches earthhack's real PbWindowClickService/S2CWindowClick instead: replay the EXACT
    // SAME click on the client (S2CWindowClickPacket), not a snapshot of the outcome -- the
    // client's own AbstractContainerMenu.clicked() then resolves it exactly like the proxy's did.
    private static void click(int slotNum, int buttonNum, ContainerInput containerInput) {
        mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, slotNum, buttonNum, containerInput, mc.player);
        broadcastClickToClientIfProxy(slotNum, buttonNum, containerInput);
    }

    private static void broadcastClickToClientIfProxy(int slotNum, int buttonNum, ContainerInput containerInput) {
        if (EUClient.PINGBYPASS_CONFIG == null || !EUClient.PINGBYPASS_CONFIG.isServer()) return;
        if (!eu.client.pingbypass.PingBypassFlags.proxyForwardingActive || EUClient.PROXY_SERVER == null) return;

        var packet = new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                eu.client.pingbypass.protocol.PbCustomPayload.fromPacket(
                        new eu.client.pingbypass.protocol.packets.S2CWindowClickPacket(
                                mc.player.containerMenu.containerId, slotNum, buttonNum, containerInput)));
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
