package eu.client.modules.impl.player;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.RenderWorldEvent;
import eu.client.events.impl.TickEvent;
import eu.client.mixins.accessors.ClientPlayerInteractionManagerAccessor;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BindSetting;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.graphics.Renderer3D;
import eu.client.utils.input.KeyboardUtils;
import eu.client.utils.minecraft.InventoryUtils;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Ported from example-addon-master's EvilRekit (renamed Rekit per request). Auto-regear: pulls a
// shulker from an ender chest, places it, opens it, fills the active kit's slots from its
// contents, breaks it, and puts it back -- an 8-state machine (see AutoState) with the same
// edge-case handling as the original (screen-close-before-transition races, hotbar-full
// displacement, kit-slot grace windows for manually-cleared slots, etc). Kit persistence is JSON
// (Gson), one file per kit under Rekit/Kits/.
@RegisterModule(name = "Rekit", description = "Automatically regears from an ender chest shulker using a saved kit.", category = Module.Category.PLAYER)
public class RekitModule extends Module {
    public NumberSetting delay = new NumberSetting("Delay", "Tick delay between pull actions.", 1, 0, 10);
    public NumberSetting actionsPerTick = new NumberSetting("Frequency", "Max pull actions per tick.", 1, 1, 5);
    public BooleanSetting auto = new BooleanSetting("Auto", "Auto pull shulkers from the ender chest.", false);
    public BooleanSetting silentContainer = new BooleanSetting("SilentContainer", "Pull items without the container GUI showing on screen.", false);
    public ModeSetting swapMode = new ModeSetting("SwapMode", "Swap type used when placing shulkers and swapping to a breaking tool in Auto mode.", "AltSwap", InventoryUtils.SWITCH_MODES);
    // findShulkerInContainer normally ranks candidates purely by matching CONTENTS -- two
    // shulkers of the same color/kind can score identically (or the wrong one can even score
    // higher, e.g. holding spare kit items) even though only one is the labeled kit shulker. When
    // on, a shulker whose custom name matches activeKitName gets an overriding bonus regardless
    // of content score.
    public BooleanSetting considerShulkerName = new BooleanSetting("ConsiderShulkerName", "Prefer the shulker whose custom name matches the active kit's name, overriding content-based scoring.", false);
    // Manual mode pulls from ANY open container, not just the ender chest -- a server shop/search
    // GUI whose only "custom" signal is its title (not per-item CUSTOM_NAME) still matched on
    // item type alone. When on, bail entirely on any container whose title isn't a vanilla
    // TranslatableContents, except a hand-opened ShulkerBoxMenu (a renamed kit shulker still
    // needs to work; Auto's real regear never routes through manualPullTick anyway).
    public BooleanSetting ignoreCustomName = new BooleanSetting("IgnoreCustomName", "Skip any container with a custom (non-vanilla) title such as a shop GUI, except shulker boxes.", false);
    // Keybind version of place->open->pull->close->break, WITHOUT the ender chest fetch/return --
    // operates on whatever shulker is already in the inventory. Reuses the same AutoState machine,
    // entered directly at PLACE_SHULKER.
    public BindSetting autoPlaceBind = new BindSetting("AutoPlace", "Keybind: places the shulker in your inventory, opens it, pulls kit items, closes, then breaks it. No ender chest -- one-shot version of Auto.", 0);

    public enum PickaxePref { Efficiency, SilkTouch }
    public enum ArmorPref { Blast, Prot }
    public ModeSetting pickaxePref = new ModeSetting("Pickaxe", "Preferred pickaxe enchant when choosing between candidates.", "Efficiency", new String[]{"Efficiency", "SilkTouch"});
    public ModeSetting helmetPref = new ModeSetting("Helmet", "Preferred helmet enchant when choosing between candidates.", "Prot", new String[]{"Blast", "Prot"});
    public ModeSetting chestplatePref = new ModeSetting("Chestplate", "Preferred chestplate enchant when choosing between candidates.", "Prot", new String[]{"Blast", "Prot"});
    public ModeSetting leggingsPref = new ModeSetting("Leggings", "Preferred leggings enchant when choosing between candidates.", "Prot", new String[]{"Blast", "Prot"});
    public ModeSetting bootsPref = new ModeSetting("Boots", "Preferred boots enchant when choosing between candidates.", "Prot", new String[]{"Blast", "Prot"});

    @Getter public Map<Integer, KitItem> activeKit = new HashMap<>();
    @Getter public String activeKitName = "";
    private int ticks = 0;
    private static final String FOLDER = EUClient.MOD_NAME + "/Kits/";
    private static final String LAST_KIT_FILE = EUClient.MOD_NAME + "/last_kit_save.txt";
    // Wall-clock of the last container-slot click this module sent. InventoryCleaner reads this
    // to defer its own dropping while Rekit is actively moving items, so it never throws a slot
    // Rekit is mid-swapping.
    public static volatile long lastContainerActionMs = 0;

    private enum AutoState { IDLE, FIND_SHULKER, GRAB_SHULKER, PLACE_SHULKER, OPEN_SHULKER, PULL_ITEMS, BREAK_SHULKER, RETURN_SHULKER }
    private AutoState autoState = AutoState.IDLE;

    public boolean isAutoActive() {
        return autoState != AutoState.IDLE;
    }

    private int autoTicks = 0;
    private int emptyTicks = 0;
    private int shulkerEnderSlot = -1;
    private BlockPos placedShulkerPos = null;
    private long placedShulkerRenderMs = 0;
    private BlockPos enderChestPos = null;
    private boolean hotbarSwapSettled = false;
    private int hotbarSwapAttempts = 0;
    private static final int MAX_HOTBAR_SWAP_ATTEMPTS = 3;
    private int placeFailStreak = 0;
    private static final int MAX_PLACE_FAIL_STREAK = 3;
    private int cursorWaitTicks = 0;
    private static final int CURSOR_DUMP_AFTER_TICKS = 20;
    // Hotbar-full displacement: when GRAB_SHULKER finds the hotbar completely full, the shulker
    // gets SWAPPED straight into a hotbar slot, shoving whatever was there into the ender chest at
    // the shulker's old slot instead. These remember the pairing so RETURN_SHULKER can undo it
    // exactly. -1 means not in a displacement cycle.
    private int displacedHotbarSlot = -1;
    private int displacedEnderSlot = -1;

    // A kit slot going correct -> incorrect can only be the player manually taking the item out
    // (our own logic only ever adds/replaces, never removes). Give that slot a grace window where
    // the fill loop leaves it alone instead of instantly re-yanking a replacement back into the
    // exact slot the player just cleared -- goes to a different empty slot instead if the kit
    // still needs topping up during the window.
    private final Map<Integer, Boolean> kitSlotWasCorrect = new HashMap<>();
    private final Map<Integer, Long> kitSlotClearedAtMs = new HashMap<>();
    private static final long KIT_SLOT_CLEAR_GRACE_MS = 3000L;

    private boolean autoPlaceActive = false;
    private boolean autoPlaceBindWasDown = false;
    // Restore point for a real (Normal) slot swap -- switchBack's Normal case is deliberately a
    // no-op elsewhere in this codebase (most callers want to stay on the tool), so this module
    // does its own restore around placing/breaking the shulker.
    private int savedSlot = -1;

    public RekitModule() {
        restoreLastKit();
    }

    private void restoreLastKit() {
        try {
            if (!eu.client.utils.system.FileUtils.fileExists(LAST_KIT_FILE)) return;
            String name = Files.readString(Paths.get(LAST_KIT_FILE)).trim();
            if (name.isEmpty()) return;
            File kitFile = new File(FOLDER, name + ".json");
            if (!kitFile.exists()) return;
            Gson gson = new Gson();
            try (FileReader reader = new FileReader(kitFile)) {
                Type type = new TypeToken<Map<Integer, KitItem>>() {}.getType();
                activeKit = gson.fromJson(reader, type);
                activeKitName = name;
            }
        } catch (Exception ignored) {}
    }

    public static class KitItem {
        public String id;
        public String name;
        public int maxCount;
    }

    private void info(String msg) { EUClient.CHAT_MANAGER.tagged(msg, getName()); }
    private void error(String msg) { EUClient.CHAT_MANAGER.tagged("§c" + msg, getName()); }

    public List<String> getKitNames() {
        List<String> names = new ArrayList<>();
        File[] files = new File(FOLDER).listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().endsWith(".json")) names.add(f.getName().replace(".json", ""));
            }
        }
        return names;
    }

    public void saveKit(String name) {
        if (mc.player == null) return;
        if (mc.player.isCreative() || mc.player.isSpectator()) return;
        Map<Integer, KitItem> kitData = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            int slot = getHandlerSlotPlayerOnly(i);
            ItemStack stack = mc.player.inventoryMenu.getSlot(slot).getItem();
            if (!stack.isEmpty()) {
                KitItem k = new KitItem();
                k.id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                k.maxCount = stack.getMaxStackSize();
                if (stack.has(DataComponents.CUSTOM_NAME)) k.name = stack.getHoverName().getString();
                kitData.put(i, k);
            }
        }
        try {
            eu.client.utils.system.FileUtils.createDirectory(FOLDER);
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try (FileWriter writer = new FileWriter(new File(FOLDER, name + ".json"))) {
                gson.toJson(kitData, writer);
            }
            activeKit = kitData;
            activeKitName = name;
            Files.writeString(Paths.get(LAST_KIT_FILE), name);
            info("Kit saved and activated: " + name);
        } catch (Exception e) {
            error("Error saving kit!");
        }
    }

    public void loadKit(String name) {
        try {
            File file = new File(FOLDER, name + ".json");
            if (!file.exists()) { error("Kit not found: " + name); return; }
            Gson gson = new Gson();
            try (FileReader reader = new FileReader(file)) {
                Type type = new TypeToken<Map<Integer, KitItem>>() {}.getType();
                activeKit = gson.fromJson(reader, type);
                activeKitName = name;
            }
            Files.writeString(Paths.get(LAST_KIT_FILE), name);
            info("Kit loaded: " + name);
        } catch (Exception e) {
            error("Error occurred while reading kit!");
        }
    }

    public void listKits() {
        File[] files = new File(FOLDER).listFiles();
        if (files == null || files.length == 0) { info("You don't have any kits."); return; }
        info("Available kits:");
        for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            String name = f.getName().replace(".json", "");
            if (name.equals(activeKitName)) EUClient.CHAT_MANAGER.tagged("§9- " + name + " [active]", getName());
            else EUClient.CHAT_MANAGER.tagged("§7- " + name, getName());
        }
    }

    public void showActiveKit() {
        if (activeKitName == null || activeKitName.isEmpty()) error("No kit is currently active.");
        else EUClient.CHAT_MANAGER.tagged("§aActive kit: §9" + activeKitName, getName());
    }

    public void deleteKit(String name) {
        File file = new File(FOLDER, name + ".json");
        if (file.exists() && file.delete()) {
            if (name.equals(activeKitName)) { activeKit.clear(); activeKitName = ""; }
            info("Kit deleted: " + name);
        } else {
            error("Failed to delete kit.");
        }
    }

    @Override
    public void onEnable() {
        autoState = AutoState.IDLE;
        autoTicks = 0;
        emptyTicks = 0;
        shulkerEnderSlot = -1;
        placedShulkerPos = null;
        enderChestPos = null;
        hotbarSwapSettled = false;
        cursorWaitTicks = 0;
        displacedHotbarSlot = -1;
        displacedEnderSlot = -1;
        autoPlaceActive = false;
        autoPlaceBindWasDown = false;
        kitSlotWasCorrect.clear();
        kitSlotClearedAtMs.clear();
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.player == null) return;

        // Bind check runs even while the module itself might be off elsewhere in the tick
        // handler chain -- but the module has to be toggled for the bind to mean anything.
        if (mc.screen == null) {
            boolean down = KeyboardUtils.isBindDown(autoPlaceBind.getValue());
            if (down && !autoPlaceBindWasDown && autoState == AutoState.IDLE
                    && !mc.player.isCreative() && !mc.player.isSpectator()
                    && findShulkerInInventory() != -1) {
                autoPlaceActive = true;
                autoState = AutoState.PLACE_SHULKER;
                autoTicks = 0;
                hotbarSwapSettled = false;
                hotbarSwapAttempts = 0;
            }
            autoPlaceBindWasDown = down;
        } else {
            autoPlaceBindWasDown = false;
        }

        if (mc.player.isCreative() || mc.player.isSpectator()) return;

        if (auto.getValue() || autoPlaceActive) {
            handleAutoTick();
            return;
        }

        if (!(mc.screen instanceof AbstractContainerScreen)) return;
        if (mc.screen instanceof InventoryScreen) return;
        manualPullTick();
    }

    private static final long PLACE_ANIM_MS = 400;

    @SubscribeEvent
    public void onWorldRender(RenderWorldEvent event) {
        if (!auto.getValue() || placedShulkerPos == null) return;
        long elapsed = System.currentTimeMillis() - placedShulkerRenderMs;
        float t = Math.min(1.0f, elapsed / (float) PLACE_ANIM_MS);
        float scale = 0.15f + 0.85f * t;
        double cx = placedShulkerPos.getX() + 0.5, cy = placedShulkerPos.getY() + 0.5, cz = placedShulkerPos.getZ() + 0.5;
        double half = 0.5 * scale;
        AABB box = new AABB(cx - half, cy - half, cz - half, cx + half, cy + half, cz + half);
        Renderer3D.renderBoxOutline(event.getMatrices(), box, new Color(255, 220, 0, 230));
    }

    private boolean hasCustomContainerTitle() {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return false;
        Component title = screen.getTitle();
        if (title == null) return false;
        return !(title.getContents() instanceof TranslatableContents);
    }

    private void manualPullTick() {
        if (activeKit.isEmpty()) return;
        if (ignoreCustomName.getValue() && hasCustomContainerTitle() && !(mc.player.containerMenu instanceof ShulkerBoxMenu)) return;
        if (ticks < delay.getValue().intValue()) { ticks++; return; }
        ticks = 0;

        int executed = 0;
        while (executed < actionsPerTick.getValue().intValue()) {
            if (!pullFromContainerTick()) break;
            executed++;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // AUTO MODE STATE MACHINE
    // ═══════════════════════════════════════════════════════════

    private void handleAutoTick() {
        boolean screenOpen = mc.screen instanceof AbstractContainerScreen && !(mc.screen instanceof InventoryScreen);

        if (autoState == AutoState.IDLE) {
            autoPlaceActive = false;
            if (screenOpen) {
                if (mc.player.containerMenu instanceof ShulkerBoxMenu) {
                    manualPullTick();
                    return;
                }
                detectEnderChestPos();
                if (enderChestPos == null) {
                    manualPullTick();
                    return;
                }
                if (activeKit.isEmpty()) {
                    setToggled(false);
                    return;
                }
                autoState = AutoState.FIND_SHULKER;
                autoTicks = 0;
                emptyTicks = 0;
            }
            return;
        }

        if (autoState == AutoState.FIND_SHULKER) {
            if (!screenOpen) return;
            if (isKitComplete()) {
                mc.player.closeContainer();
                autoState = AutoState.IDLE;
                return;
            }
            int containerSize = mc.player.containerMenu.slots.size() - 36;
            if (containerSize <= 0) return;
            shulkerEnderSlot = findShulkerInContainer(containerSize);
            if (shulkerEnderSlot == -1) {
                error("Auto stopped: no shulker has the items this kit still needs.");
                mc.player.closeContainer();
                autoState = AutoState.IDLE;
                setToggled(false);
                return;
            }
            autoState = AutoState.GRAB_SHULKER;
            autoTicks = 0;
            return;
        }

        if (autoState == AutoState.GRAB_SHULKER) {
            if (!screenOpen) return;
            int containerSize = mc.player.containerMenu.slots.size() - 36;
            if (containerSize <= 0) return;
            AbstractContainerMenu handler = mc.player.containerMenu;

            ItemStack slotStack = handler.getSlot(shulkerEnderSlot).getItem();
            if (!isShulkerBox(slotStack)) {
                autoState = AutoState.FIND_SHULKER;
                return;
            }

            int emptyHotbar = firstEmptyHotbarSlot();
            if (emptyHotbar != -1) {
                mc.gameMode.handleContainerInput(handler.containerId, shulkerEnderSlot, emptyHotbar, ContainerInput.SWAP, mc.player);
            } else {
                int hotbarSlot = mc.player.getInventory().getSelectedSlot() == 0 ? 1 : 0;
                mc.gameMode.handleContainerInput(handler.containerId, shulkerEnderSlot, hotbarSlot, ContainerInput.SWAP, mc.player);
                displacedHotbarSlot = hotbarSlot;
                displacedEnderSlot = shulkerEnderSlot;
            }
            autoState = AutoState.PLACE_SHULKER;
            autoTicks = 0;
            hotbarSwapSettled = false;
            hotbarSwapAttempts = 0;
            return;
        }

        if (autoState == AutoState.PLACE_SHULKER) {
            if (screenOpen) {
                mc.player.closeContainer();
                return;
            }
            if (autoTicks < 1) { autoTicks++; return; }

            int shulkerInvSlot = findShulkerInInventory();
            if (shulkerInvSlot == -1) {
                autoState = AutoState.IDLE;
                return;
            }

            if (shulkerInvSlot > 8 && !hotbarSwapSettled) {
                ensureHotbar(shulkerInvSlot);
                hotbarSwapSettled = true;
                return;
            }

            if (shulkerInvSlot > 8) {
                hotbarSwapAttempts++;
                if (hotbarSwapAttempts > MAX_HOTBAR_SWAP_ATTEMPTS) {
                    placeFailStreak++;
                    autoState = AutoState.RETURN_SHULKER;
                    return;
                }
                hotbarSwapSettled = false;
                return;
            }

            BlockPos placePos = findPlaceableSpot();
            if (placePos == null) {
                placeFailStreak++;
                autoState = AutoState.RETURN_SHULKER;
                return;
            }

            BlockHitResult hit = getHitResultForPlace(placePos);
            if (hit == null) {
                placeFailStreak++;
                autoState = AutoState.RETURN_SHULKER;
                return;
            }

            // Placement always uses a real (Normal) slot select, regardless of the configured
            // SwapMode -- Silent/Alt swaps don't reliably change what the local client actually
            // holds, which is what matters for a REAL block placement.
            shulkerInvSlot = findShulkerInInventory();
            if (shulkerInvSlot < 0 || shulkerInvSlot > 8) {
                placeFailStreak++;
                autoState = AutoState.RETURN_SHULKER;
                return;
            }
            swapToSlotNormal(shulkerInvSlot);
            boolean placed = isPositionClear(placePos) && mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit) != net.minecraft.world.InteractionResult.FAIL
                    && !mc.level.getBlockState(placePos).canBeReplaced();
            if (!placed) {
                swapBackNormal();
                placeFailStreak++;
                autoState = AutoState.RETURN_SHULKER;
                return;
            }

            placedShulkerPos = placePos;
            placedShulkerRenderMs = System.currentTimeMillis();
            autoState = AutoState.OPEN_SHULKER;
            autoTicks = 0;
            return;
        }

        if (autoState == AutoState.OPEN_SHULKER) {
            if (screenOpen) {
                swapBackNormal();
                autoState = AutoState.PULL_ITEMS;
                autoTicks = 0;
                emptyTicks = 0;
                placeFailStreak = 0;
                return;
            }

            if (autoTicks == 0 || autoTicks % 4 == 0) {
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(placedShulkerPos), Direction.UP, placedShulkerPos, false);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            }

            autoTicks++;
            if (autoTicks > 60) {
                swapBackNormal();
                autoState = AutoState.BREAK_SHULKER;
                autoTicks = 0;
            }
            return;
        }

        if (autoState == AutoState.PULL_ITEMS) {
            if (!screenOpen) {
                autoState = AutoState.BREAK_SHULKER;
                autoTicks = 0;
                return;
            }
            if (activeKit.isEmpty()) {
                autoState = AutoState.BREAK_SHULKER;
                autoTicks = 0;
                return;
            }
            if (ticks < delay.getValue().intValue()) { ticks++; return; }
            ticks = 0;

            boolean didWork = false;
            int containerSize = mc.player.containerMenu.slots.size() - 36;
            if (containerSize > 0) {
                int executed = 0;
                while (executed < actionsPerTick.getValue().intValue()) {
                    if (!pullFromContainerTick()) break;
                    executed++;
                    didWork = true;
                }
            }
            if (didWork) emptyTicks = 0; else emptyTicks++;

            if (emptyTicks >= 5) {
                autoState = AutoState.BREAK_SHULKER;
                autoTicks = 0;
            }
            return;
        }

        if (autoState == AutoState.BREAK_SHULKER) {
            if (screenOpen) {
                mc.player.closeContainer();
                return;
            }
            if (autoTicks < 2) { autoTicks++; return; }

            if (placedShulkerPos == null || mc.level == null) {
                autoState = AutoState.IDLE;
                return;
            }

            if (mc.level.getBlockState(placedShulkerPos).canBeReplaced()) {
                if (findShulkerInInventory() == -1) {
                    if (autoTicks < 100) { autoTicks++; return; }
                }
                autoState = AutoState.RETURN_SHULKER;
                autoTicks = 0;
                return;
            }

            if (autoTicks > 200) {
                autoState = AutoState.RETURN_SHULKER;
                autoTicks = 0;
                return;
            }

            instantBreak(placedShulkerPos);
            autoTicks++;
            return;
        }

        if (autoState == AutoState.RETURN_SHULKER) {
            if (enderChestPos == null) {
                autoState = AutoState.IDLE;
                return;
            }

            if (!screenOpen) {
                if (autoTicks < 2) { autoTicks++; return; }
                if (autoTicks == 2) {
                    BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(enderChestPos), Direction.UP, enderChestPos, false);
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                }
                autoTicks++;
                if (autoTicks > 40) autoState = AutoState.IDLE;
                return;
            }
            autoTicks = 0;

            int shulkerInvSlot = findShulkerInInventory();
            if (shulkerInvSlot == -1) {
                if (placedShulkerPos != null && mc.level != null && !mc.level.getBlockState(placedShulkerPos).canBeReplaced()) {
                    autoState = AutoState.BREAK_SHULKER;
                    autoTicks = 0;
                    return;
                }
                autoState = AutoState.FIND_SHULKER;
                autoTicks = 0;
                return;
            }

            int containerSize = mc.player.containerMenu.slots.size() - 36;
            if (containerSize <= 0) return;
            AbstractContainerMenu handler = mc.player.containerMenu;

            if (displacedHotbarSlot != -1) {
                int shulkerHandlerSlot = getPlayerHandlerSlot(containerSize, shulkerInvSlot);
                click(handler.containerId, shulkerHandlerSlot, 0, ContainerInput.PICKUP);
                click(handler.containerId, displacedEnderSlot, 0, ContainerInput.PICKUP);
                int origHandlerSlot = getPlayerHandlerSlot(containerSize, displacedHotbarSlot);
                click(handler.containerId, origHandlerSlot, 0, ContainerInput.PICKUP);
                displacedHotbarSlot = -1;
                displacedEnderSlot = -1;
            } else {
                int emptyEnderSlot = -1;
                for (int i = 0; i < containerSize; i++) {
                    if (handler.getSlot(i).getItem().isEmpty()) { emptyEnderSlot = i; break; }
                }
                if (emptyEnderSlot == -1) {
                    autoState = AutoState.IDLE;
                    return;
                }
                int playerHandlerSlot = getPlayerHandlerSlot(containerSize, shulkerInvSlot);
                atomicSwap(handler.containerId, playerHandlerSlot, emptyEnderSlot);
            }

            placedShulkerPos = null;
            shulkerEnderSlot = -1;
            if (placeFailStreak >= MAX_PLACE_FAIL_STREAK) {
                error("Auto stopped: could not place/open a shulker after " + placeFailStreak + " attempts.");
                mc.player.closeContainer();
                autoState = AutoState.IDLE;
                setToggled(false);
                placeFailStreak = 0;
            } else if (isKitComplete()) {
                mc.player.closeContainer();
                autoState = AutoState.IDLE;
            } else {
                autoState = AutoState.FIND_SHULKER;
            }
            autoTicks = 0;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════

    private void detectEnderChestPos() {
        if (mc.hitResult instanceof BlockHitResult bhr && mc.level.getBlockState(bhr.getBlockPos()).is(Blocks.ENDER_CHEST)) {
            enderChestPos = bhr.getBlockPos();
            return;
        }
        BlockPos playerPos = mc.player.blockPosition();
        for (int dx = -3; dx <= 3; dx++)
            for (int dy = -1; dy <= 3; dy++)
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos pos = playerPos.offset(dx, dy, dz);
                    if (mc.level.getBlockState(pos).is(Blocks.ENDER_CHEST)) { enderChestPos = pos; return; }
                }
    }

    private int findShulkerInContainer(int containerSize) {
        AbstractContainerMenu handler = mc.player.containerMenu;
        Set<Item> kitItems = new HashSet<>();
        for (Map.Entry<Integer, KitItem> entry : activeKit.entrySet()) {
            ItemStack playerStack = mc.player.getInventory().getItem(entry.getKey());
            if (!isCorrectItem(playerStack, entry.getValue())) {
                kitItems.add(BuiltInRegistries.ITEM.getValue(Identifier.parse(entry.getValue().id)));
            }
        }

        int bestSlot = -1, bestScore = -1;
        for (int i = 0; i < containerSize; i++) {
            ItemStack stack = handler.getSlot(i).getItem();
            if (!isShulkerBox(stack)) continue;

            int score = 0;
            ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
            if (contents != null) {
                for (ItemStack inner : (Iterable<ItemStack>) contents.nonEmptyItemCopyStream()::iterator) {
                    if (kitItems.contains(inner.getItem())) score += inner.getMaxStackSize() == 1 ? 100 : 1;
                }
            }
            if (considerShulkerName.getValue() && !activeKitName.isEmpty() && stack.has(DataComponents.CUSTOM_NAME)
                    && normalizeName(stack.getHoverName().getString()).equalsIgnoreCase(normalizeName(activeKitName))) {
                score += 1_000_000;
            }
            if (score > bestScore) { bestScore = score; bestSlot = i; }
        }
        if (bestScore <= 0) return -1;
        return bestSlot;
    }

    private int ensureHotbar(int invSlot) {
        if (invSlot >= 0 && invSlot <= 8) return invSlot;
        int hotbar = mc.player.getInventory().getSelectedSlot();
        mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, getHandlerSlotPlayerOnly(invSlot), hotbar, ContainerInput.SWAP, mc.player);
        return hotbar;
    }

    private int findShulkerInInventory() {
        for (int i = 0; i < 36; i++) {
            if (isShulkerBox(mc.player.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    private int firstEmptyHotbarSlot() {
        for (int i = 0; i <= 8; i++) if (mc.player.getInventory().getItem(i).isEmpty()) return i;
        return -1;
    }

    private static final long RECENT_COMBAT_TICKS = 100L;

    private Player getActiveCombatOpponent() {
        LivingEntity last = mc.player.getLastHurtByMob();
        if (!(last instanceof Player opponent)) return null;
        long ticksSinceHit = mc.level.getGameTime() - mc.player.getLastHurtByMobTimestamp();
        return ticksSinceHit <= RECENT_COMBAT_TICKS ? opponent : null;
    }

    private boolean isPositionClear(BlockPos pos) {
        return mc.level.getEntities((Entity) null, new AABB(pos), Entity::isAlive).isEmpty();
    }

    private BlockPos findPlaceableSpot() {
        BlockPos base = mc.player.blockPosition();
        Player opponent = getActiveCombatOpponent();
        int r = (int) Math.ceil(mc.player.blockInteractionRange());

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                BlockPos candidate = base.offset(dx, 0, dz);
                if (candidate.distSqr(base) > r * (double) r) continue;
                if (candidate.below().equals(enderChestPos)) continue;
                if (!isPositionClear(candidate)) continue;
                if (mc.level.getBlockState(candidate).canBeReplaced()
                        && !mc.level.getBlockState(candidate.below()).canBeReplaced()
                        && !mc.level.getBlockState(candidate.below()).is(Blocks.AIR)) {
                    if (mc.level.getBlockState(candidate.above()).canBeReplaced()) {
                        double score = opponent != null ? -candidate.distSqr(opponent.blockPosition()) : dx * dx + dz * dz;
                        if (score < bestScore) { bestScore = score; best = candidate; }
                    }
                }
            }
        }
        return best;
    }

    private BlockHitResult getHitResultForPlace(BlockPos placePos) {
        BlockPos target = placePos.below();
        if (mc.level.getBlockState(target).canBeReplaced()) {
            for (Direction dir : Direction.values()) {
                if (dir == Direction.UP || dir == Direction.DOWN) continue;
                BlockPos neighbour = placePos.relative(dir.getOpposite());
                if (!mc.level.getBlockState(neighbour).canBeReplaced()) {
                    Vec3 hitVec = Vec3.atCenterOf(neighbour).add(dir.getStepX() * 0.5, dir.getStepY() * 0.5, dir.getStepZ() * 0.5);
                    return new BlockHitResult(hitVec, dir, neighbour, false);
                }
            }
            return null;
        }
        return new BlockHitResult(Vec3.atCenterOf(placePos).add(0, -0.5, 0), Direction.UP, target, false);
    }

    private int findEmptyUnassignedHandlerSlot(AbstractContainerMenu handler, int containerSize) {
        for (int i = 0; i < 36; i++) {
            int slot = getPlayerHandlerSlot(containerSize, i);
            if (handler.getSlot(slot).getItem().isEmpty()) return slot;
        }
        return -1;
    }

    private boolean isKitComplete() {
        if (activeKit.isEmpty()) return true;
        for (Map.Entry<Integer, KitItem> entry : activeKit.entrySet()) {
            if (!isCorrectItem(mc.player.getInventory().getItem(entry.getKey()), entry.getValue())) return false;
        }
        return true;
    }

    private boolean pullFromContainerTick() {
        AbstractContainerMenu handler = mc.player.containerMenu;
        int containerSize = handler.slots.size() - 36;
        if (containerSize <= 0) return false;

        if (!handler.getCarried().isEmpty()) {
            cursorWaitTicks++;
            if (cursorWaitTicks < CURSOR_DUMP_AFTER_TICKS) return false;
            cursorWaitTicks = 0;

            int clearSlot = -1;
            for (int i = 0; i < containerSize; i++) if (handler.getSlot(i).getItem().isEmpty()) { clearSlot = i; break; }
            if (clearSlot == -1) {
                for (int i = 0; i < 36; i++) {
                    int slot = getPlayerHandlerSlot(containerSize, i);
                    if (handler.getSlot(slot).getItem().isEmpty()) { clearSlot = slot; break; }
                }
            }
            if (clearSlot != -1) { click(handler.containerId, clearSlot, 0, ContainerInput.PICKUP); return true; }
            return false;
        }
        cursorWaitTicks = 0;

        for (int i = 0; i < 36; i++) {
            KitItem kit = activeKit.get(i);
            if (kit == null) continue;

            int playerSlot = getPlayerHandlerSlot(containerSize, i);
            ItemStack playerStack = handler.getSlot(playerSlot).getItem();

            boolean correctNow = isCorrectItem(playerStack, kit);
            if (Boolean.TRUE.equals(kitSlotWasCorrect.get(i)) && !correctNow) kitSlotClearedAtMs.put(i, System.currentTimeMillis());
            kitSlotWasCorrect.put(i, correctNow);

            if (!correctNow) {
                if (isShulkerBox(playerStack)) continue;
                boolean inGrace = System.currentTimeMillis() - kitSlotClearedAtMs.getOrDefault(i, 0L) < KIT_SLOT_CLEAR_GRACE_MS;
                int containerSlot = findBestItemInContainer(handler, containerSize, kit);
                if (containerSlot != -1) {
                    int emptySlot = inGrace ? findEmptyUnassignedHandlerSlot(handler, containerSize) : -1;
                    if (emptySlot != -1 && emptySlot != playerSlot) atomicSwap(handler.containerId, containerSlot, emptySlot);
                    else atomicSwap(handler.containerId, containerSlot, playerSlot);
                    return true;
                }
            } else if (playerStack.getCount() < playerStack.getMaxStackSize()) {
                int exactSlot = findExactItemInContainer(handler, containerSize, playerStack);
                if (exactSlot != -1) { atomicSwap(handler.containerId, exactSlot, playerSlot); return true; }

                int bestSlot = findBestItemInContainer(handler, containerSize, kit);
                if (bestSlot != -1) {
                    ItemStack containerStack = handler.getSlot(bestSlot).getItem();
                    if (containerStack.getCount() > playerStack.getCount()) { atomicSwap(handler.containerId, bestSlot, playerSlot); return true; }
                }
            }
        }
        for (int i = 0; i < 36; i++) {
            KitItem kit = activeKit.get(i);
            if (kit == null) continue;
            int playerSlot = getPlayerHandlerSlot(containerSize, i);
            ItemStack playerStack = handler.getSlot(playerSlot).getItem();
            if (isShulkerBox(playerStack) && !isItemCompensated(handler, containerSize, kit)) {
                int containerSlot = findBestItemInContainer(handler, containerSize, kit);
                int emptySlot = findEmptyUnassignedSlot(handler, containerSize);
                if (containerSlot != -1 && emptySlot != -1) { atomicSwap(handler.containerId, containerSlot, emptySlot); return true; }
            }
        }
        return false;
    }

    private void atomicSwap(int syncId, int containerSlot, int playerSlot) {
        click(syncId, containerSlot, 0, ContainerInput.PICKUP);
        click(syncId, playerSlot, 0, ContainerInput.PICKUP);
        click(syncId, containerSlot, 0, ContainerInput.PICKUP);
    }

    private void click(int syncId, int slotId, int button, ContainerInput type) {
        lastContainerActionMs = System.currentTimeMillis();
        mc.gameMode.handleContainerInput(syncId, slotId, button, type, mc.player);
    }

    private void swapToSlotNormal(int slot) {
        savedSlot = mc.player.getInventory().getSelectedSlot();
        mc.player.getInventory().setSelectedSlot(slot);
        ((ClientPlayerInteractionManagerAccessor) mc.gameMode).invokeSyncSelectedSlot();
    }

    private void swapBackNormal() {
        if (savedSlot == -1) return;
        mc.player.getInventory().setSelectedSlot(savedSlot);
        ((ClientPlayerInteractionManagerAccessor) mc.gameMode).invokeSyncSelectedSlot();
        savedSlot = -1;
    }

    // Single-tick instant break attempt -- START_DESTROY_BLOCK/STOP_DESTROY_BLOCK every tick
    // until the block-state check in BREAK_SHULKER catches the real break, same rebreak-until-
    // confirmed pattern SpeedMine's own Instant mode uses (a shulker breaks in one real hit
    // anyway; this just guarantees the attempt actually lands regardless of tool/reach edge cases).
    private void instantBreak(BlockPos pos) {
        Direction direction = Direction.UP;
        mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, direction));
        mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, direction));
        mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
    }

    private boolean isCorrectItem(ItemStack stack, KitItem kit) {
        if (kit == null) return true;
        if (stack.isEmpty()) return false;
        Item expected = BuiltInRegistries.ITEM.getValue(Identifier.parse(kit.id));
        return stack.getItem() == expected;
    }

    private int findBestItemInContainer(AbstractContainerMenu handler, int containerSize, KitItem kit) {
        Item expected = BuiltInRegistries.ITEM.getValue(Identifier.parse(kit.id));
        int bestSlot = -1;
        long bestScore = -1;
        for (int i = 0; i < containerSize; i++) {
            ItemStack stack = handler.getSlot(i).getItem();
            if (!stack.isEmpty() && stack.getItem() == expected) {
                long score = scoreCandidate(stack);
                if (score > bestScore) { bestScore = score; bestSlot = i; }
            }
        }
        return bestSlot;
    }

    // Stackables keep the max-count rule. Unstackables (gear) rank by: preferred enchant present
    // (dominates) > remaining durability -- weights are strictly ordered so a preferred-enchant
    // item always beats a higher-durability one without it.
    private long scoreCandidate(ItemStack stack) {
        if (stack.getMaxStackSize() > 1) return stack.getCount();
        long score = 0;
        String prefEnchant = preferredEnchantId(stack);
        if (prefEnchant != null && hasEnchant(stack, prefEnchant)) score += 1_000_000L;
        if (stack.isDamageableItem()) score += stack.getMaxDamage() - stack.getDamageValue();
        return score;
    }

    private String preferredEnchantId(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (id.endsWith("_pickaxe")) return pickaxePref.getValue().equalsIgnoreCase("SilkTouch") ? "silk_touch" : "efficiency";
        String pref;
        if (id.endsWith("_helmet") || id.equals("turtle_helmet")) pref = helmetPref.getValue();
        else if (id.endsWith("_chestplate")) pref = chestplatePref.getValue();
        else if (id.endsWith("_leggings")) pref = leggingsPref.getValue();
        else if (id.endsWith("_boots")) pref = bootsPref.getValue();
        else return null;
        return pref.equalsIgnoreCase("Blast") ? "blast_protection" : "protection";
    }

    private boolean hasEnchant(ItemStack stack, String enchantPath) {
        net.minecraft.world.item.enchantment.ItemEnchantments enchants = stack.get(DataComponents.ENCHANTMENTS);
        if (enchants == null || enchants.isEmpty()) return false;
        Identifier id = Identifier.parse("minecraft:" + enchantPath);
        for (var holder : enchants.keySet()) if (holder.is(id)) return true;
        return false;
    }

    private int findExactItemInContainer(AbstractContainerMenu handler, int containerSize, ItemStack targetStack) {
        for (int i = 0; i < containerSize; i++) {
            ItemStack stack = handler.getSlot(i).getItem();
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, targetStack)) return i;
        }
        return -1;
    }

    private int getPlayerHandlerSlot(int containerSize, int invSlot) {
        if (invSlot >= 0 && invSlot <= 8) return containerSize + 27 + invSlot;
        if (invSlot >= 9 && invSlot <= 35) return containerSize + (invSlot - 9);
        return -1;
    }

    private int getHandlerSlotPlayerOnly(int invSlot) {
        if (invSlot >= 0 && invSlot <= 8) return 36 + invSlot;
        if (invSlot >= 9 && invSlot <= 35) return invSlot;
        return -1;
    }

    private static String normalizeName(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC).trim();
    }

    private boolean isShulkerBox(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private boolean isItemCompensated(AbstractContainerMenu handler, int containerSize, KitItem kit) {
        Item expected = BuiltInRegistries.ITEM.getValue(Identifier.parse(kit.id));
        for (int i = 0; i < 36; i++) {
            if (activeKit.get(i) == null) {
                int slot = getPlayerHandlerSlot(containerSize, i);
                if (handler.getSlot(slot).getItem().getItem() == expected) return true;
            }
        }
        return false;
    }

    private int findEmptyUnassignedSlot(AbstractContainerMenu handler, int containerSize) {
        for (int i = 0; i < 36; i++) {
            if (activeKit.get(i) == null) {
                int slot = getPlayerHandlerSlot(containerSize, i);
                if (handler.getSlot(slot).getItem().isEmpty()) return slot;
            }
        }
        return -1;
    }
}
