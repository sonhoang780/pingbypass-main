package eu.client.modules.impl.visuals;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.events.impl.RenderWorldEvent;
import eu.client.events.impl.TickEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.modules.impl.visuals.stashfinder.StashFinderStore;
import eu.client.modules.impl.visuals.stashfinder.StashWebhook;
import eu.client.modules.impl.visuals.stashfinder.StructureSignature;
import eu.client.settings.impl.*;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.graphics.Renderer3D;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.animal.equine.Donkey;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.vehicle.boat.ChestBoat;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import java.awt.Color;
import java.util.*;

@RegisterModule(name = "StashFinder", description = "Scans loaded chunks for loot stashes and storage animals, alerting via chat and Discord webhook.", category = Module.Category.VISUALS)
public class StashFinderModule extends Module {

    public CategorySetting containersCategory = new CategorySetting("Containers", "Threshold settings for containers.");
    public NumberSetting minChests = new NumberSetting("Chests", "Minimum chests required.", new CategorySetting.Visibility(containersCategory), 10, 1, 64);
    public NumberSetting minBarrels = new NumberSetting("Barrels", "Minimum barrels required.", new CategorySetting.Visibility(containersCategory), 10, 1, 64);
    public NumberSetting minShulkers = new NumberSetting("Shulkers", "Minimum shulker boxes required.", new CategorySetting.Visibility(containersCategory), 4, 1, 64);
    public NumberSetting minEnderChests = new NumberSetting("EnderChests", "Minimum ender chests required.", new CategorySetting.Visibility(containersCategory), 2, 1, 64);
    public NumberSetting minHoppers = new NumberSetting("Hoppers", "Minimum hoppers required.", new CategorySetting.Visibility(containersCategory), 10, 1, 64);
    public NumberSetting minDispensersDroppers = new NumberSetting("Dispensers/Droppers", "Minimum dispensers or droppers required.", new CategorySetting.Visibility(containersCategory), 10, 1, 64);
    public NumberSetting minFurnaces = new NumberSetting("Furnaces", "Minimum furnaces required.", new CategorySetting.Visibility(containersCategory), 10, 1, 64);
    public NumberSetting minCrafters = new NumberSetting("Crafters", "Minimum crafters required.", new CategorySetting.Visibility(containersCategory), 4, 1, 64);

    public CategorySetting animalsCategory = new CategorySetting("Animals", "Threshold settings for storage animals.");
    public NumberSetting minDonkeys = new NumberSetting("Donkeys", "Minimum donkeys required.", new CategorySetting.Visibility(animalsCategory), 2, 1, 16);
    public NumberSetting minLlamas = new NumberSetting("Llamas", "Minimum llamas required.", new CategorySetting.Visibility(animalsCategory), 2, 1, 16);
    public NumberSetting minChestBoats = new NumberSetting("ChestBoats", "Minimum chest boats required.", new CategorySetting.Visibility(animalsCategory), 1, 1, 16);

    public CategorySetting generalCategory = new CategorySetting("General", "General settings.");
    public BooleanSetting ignoreNatural = new BooleanSetting("IgnoreNatural", "Ignores naturally generated structures.", new CategorySetting.Visibility(generalCategory), true);
    public BooleanSetting chatNotify = new BooleanSetting("ChatNotify", "Notifies in chat when a stash is found.", new CategorySetting.Visibility(generalCategory), true);
    public BooleanSetting renderHighlight = new BooleanSetting("RenderHighlight", "Highlights found stash chunks in the world.", new CategorySetting.Visibility(generalCategory), true);
    public ColorSetting highlightColor = new ColorSetting("HighlightColor", "Highlight color for stash chunks.", new CategorySetting.Visibility(generalCategory), new ColorSetting.Color(new Color(0, 230, 120, 100), false, false));

    public CategorySetting webhookCategory = new CategorySetting("Webhook", "Discord webhook settings.");
    public BooleanSetting sendWebhook = new BooleanSetting("SendWebhook", "Sends stash alerts to Discord webhook.", new CategorySetting.Visibility(webhookCategory), true);
    public StringSetting webhookUrl = new StringSetting("WebhookURL", "Discord webhook URL.", new CategorySetting.Visibility(webhookCategory), "");
    public StringSetting userId = new StringSetting("UserID", "Discord User ID to ping.", new CategorySetting.Visibility(webhookCategory), "");

    private final StashFinderStore store = new StashFinderStore();
    private String lastWorldKey = null;

    private final Map<Long, Integer> pending = new HashMap<>();
    private static final int SCAN_DELAY_TICKS = 25; // ~1.25s debounce
    private static final int MAX_EVALS_PER_TICK = 4;
    private static final int BOOTSTRAP_RADIUS_CHUNKS = 12;

    private static boolean listenerRegistered = false;

    public StashFinderModule() {
        registerChunkListener();
    }

    private void registerChunkListener() {
        if (listenerRegistered) return;
        listenerRegistered = true;
        ClientChunkEvents.CHUNK_LOAD.register((ClientLevel level, LevelChunk chunk) -> {
            StashFinderModule module = EUClient.MODULE_MANAGER != null ? EUClient.MODULE_MANAGER.getModule(StashFinderModule.class) : null;
            if (module == null || !module.isToggled()) return;
            Minecraft client = Minecraft.getInstance();
            if (client.level != level) return;

            long key = StashFinderStore.chunkKey(chunk.getPos().x(), chunk.getPos().z());
            if (module.store.isEvaluated(key)) return;
            module.pending.putIfAbsent(key, SCAN_DELAY_TICKS);
        });
    }

    @Override
    public void onEnable() {
        pending.clear();
        store.loadForWorld();
        lastWorldKey = StashFinderStore.currentWorldKey();

        if (mc.player == null || mc.level == null) return;
        int pcx = mc.player.blockPosition().getX() >> 4;
        int pcz = mc.player.blockPosition().getZ() >> 4;
        for (int dx = -BOOTSTRAP_RADIUS_CHUNKS; dx <= BOOTSTRAP_RADIUS_CHUNKS; dx++) {
            for (int dz = -BOOTSTRAP_RADIUS_CHUNKS; dz <= BOOTSTRAP_RADIUS_CHUNKS; dz++) {
                int cx = pcx + dx, cz = pcz + dz;
                if (!mc.level.hasChunk(cx, cz)) continue;
                long key = StashFinderStore.chunkKey(cx, cz);
                if (store.isEvaluated(key)) continue;
                pending.putIfAbsent(key, SCAN_DELAY_TICKS);
            }
        }
    }

    @Override
    public void onDisable() {
        store.flush();
        pending.clear();
    }

    private void maybeReloadStoreForWorld() {
        String key = StashFinderStore.currentWorldKey();
        if (!key.equals(lastWorldKey)) {
            store.loadForWorld();
            lastWorldKey = key;
            pending.clear();
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null) return;
        maybeReloadStoreForWorld();
        store.flushIfStale();
        if (pending.isEmpty()) return;

        int budget = MAX_EVALS_PER_TICK;
        var it = pending.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            int left = e.getValue() - 1;
            if (left > 0) {
                e.setValue(left);
                continue;
            }
            if (budget == 0) {
                e.setValue(1);
                continue;
            }
            budget--;
            it.remove();
            long key = e.getKey();
            int chunkX = (int) (key & 0xFFFFFFFFL);
            int chunkZ = (int) (key >>> 32);
            evaluateChunk(chunkX, chunkZ, key);
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        if (!renderHighlight.getValue() || mc.level == null) return;

        Color fill = highlightColor.getColor();
        Color outline = new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), 255);

        for (StashFinderStore.StashData stash : store.getStashes()) {
            AABB box = new AABB(stash.x(), mc.level.getMinY(), stash.z(), stash.x() + 16, mc.level.getMaxY(), stash.z() + 16);
            Renderer3D.renderBox(event.getMatrices(), box, fill);
            Renderer3D.renderBoxOutline(event.getMatrices(), box, outline);
        }
    }

    public void resetCurrentWorldStore() {
        store.loadForWorld();
        store.clearAll();
        pending.clear();
        lastWorldKey = StashFinderStore.currentWorldKey();
    }

    private void evaluateChunk(int chunkX, int chunkZ, long key) {
        if (mc.level == null || !mc.level.hasChunk(chunkX, chunkZ)) return;
        LevelChunk chunk = mc.level.getChunk(chunkX, chunkZ);

        store.markEvaluated(key);

        Map<String, Integer> counts = countContainers(chunk);
        counts.putAll(countAnimals(chunk));

        if (!meetsAnyThreshold(counts)) return;
        if (ignoreNatural.getValue() && StructureSignature.isNatural(mc, chunk, counts)) return;

        Map<String, Integer> nonZero = new LinkedHashMap<>();
        for (var e : counts.entrySet()) {
            if (e.getValue() > 0) nonZero.put(e.getKey(), e.getValue());
        }

        ChunkPos pos = chunk.getPos();
        int reportX = pos.getMinBlockX();
        int reportZ = pos.getMinBlockZ();
        String dimension = mc.level.dimension().identifier().toString();

        store.recordStash(reportX, reportZ, dimension, nonZero);

        if (chatNotify.getValue()) {
            StringBuilder sb = new StringBuilder();
            sb.append("Stash found at X: ").append(reportX).append(" Z: ").append(reportZ).append(" (");
            int idx = 0;
            for (var entry : nonZero.entrySet()) {
                if (idx++ > 0) sb.append(", ");
                sb.append(entry.getKey()).append(": ").append(entry.getValue());
            }
            sb.append(")");
            EUClient.CHAT_MANAGER.tagged(sb.toString(), "StashFinder");
        }

        if (sendWebhook.getValue()) {
            String url = webhookUrl.getValue();
            String id = userId.getValue();
            StashWebhook.send(reportX, reportZ, dimension, nonZero, url, id);
        }
    }

    private boolean meetsAnyThreshold(Map<String, Integer> counts) {
        return check(counts, "Chests", minChests)
                || check(counts, "Barrels", minBarrels)
                || check(counts, "Shulkers", minShulkers)
                || check(counts, "Ender Chests", minEnderChests)
                || check(counts, "Hoppers", minHoppers)
                || check(counts, "Dispensers/Droppers", minDispensersDroppers)
                || check(counts, "Furnaces", minFurnaces)
                || check(counts, "Crafters", minCrafters)
                || check(counts, "Donkey", minDonkeys)
                || check(counts, "Llama", minLlamas)
                || check(counts, "Chest Boat", minChestBoats);
    }

    private boolean check(Map<String, Integer> counts, String key, NumberSetting min) {
        int threshold = min.getValue().intValue();
        if (threshold <= 0) return false;
        return counts.getOrDefault(key, 0) >= threshold;
    }

    private Map<String, Integer> countContainers(LevelChunk chunk) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int chests = 0, barrels = 0, shulkers = 0, enderChests = 0, hoppers = 0,
                dispensersDroppers = 0, furnaces = 0, crafters = 0;

        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof ChestBlockEntity || be instanceof TrappedChestBlockEntity) chests++;
            else if (be instanceof BarrelBlockEntity) barrels++;
            else if (be instanceof ShulkerBoxBlockEntity) shulkers++;
            else if (be instanceof EnderChestBlockEntity) enderChests++;
            else if (be instanceof HopperBlockEntity) hoppers++;
            else if (be instanceof DispenserBlockEntity || be instanceof DropperBlockEntity) dispensersDroppers++;
            else if (be instanceof FurnaceBlockEntity || be instanceof BlastFurnaceBlockEntity || be instanceof SmokerBlockEntity) furnaces++;
            else if (be instanceof CrafterBlockEntity) crafters++;
        }

        counts.put("Chests", chests);
        counts.put("Barrels", barrels);
        counts.put("Shulkers", shulkers);
        counts.put("Ender Chests", enderChests);
        counts.put("Hoppers", hoppers);
        counts.put("Dispensers/Droppers", dispensersDroppers);
        counts.put("Furnaces", furnaces);
        counts.put("Crafters", crafters);
        return counts;
    }

    private Map<String, Integer> countAnimals(LevelChunk chunk) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int donkeys = 0, llamas = 0, chestBoats = 0;

        if (mc.level == null) return counts;

        ChunkPos pos = chunk.getPos();
        AABB box = new AABB(pos.getMinBlockX(), mc.level.getMinY(), pos.getMinBlockZ(),
                pos.getMaxBlockX() + 1, mc.level.getMaxY(), pos.getMaxBlockZ() + 1);

        for (var entity : mc.level.getEntities((net.minecraft.world.entity.Entity) null, box, e -> true)) {
            if (entity instanceof Donkey) donkeys++;
            else if (entity instanceof Llama) llamas++;
            else if (entity instanceof ChestBoat) chestBoats++;
        }

        counts.put("Donkey", donkeys);
        counts.put("Llama", llamas);
        counts.put("Chest Boat", chestBoats);
        return counts;
    }
}
