package eu.client.modules.impl.visuals.stashfinder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.*;

/**
 * Persists evaluated chunk keys and found stash locations per-world so that stashes are not
 * repeatedly scanned/announced on subsequent visits.
 */
public class StashFinderStore {

    public record StashData(int x, int z, String dimension, Map<String, Integer> counts, long timestamp) {}

    private static class PersistentData {
        Set<Long> evaluated = new HashSet<>();
        List<StashData> stashes = new ArrayList<>();
    }

    private final Set<Long> evaluated = new HashSet<>();
    private final List<StashData> stashes = new ArrayList<>();
    private File file;

    private boolean dirty = false;
    private long lastSaveMs = 0;
    private static final long SAVE_INTERVAL_MS = 10_000;

    public static String currentWorldKey() {
        Minecraft mc = Minecraft.getInstance();
        String base;
        if (mc.getCurrentServer() != null) {
            base = "server_" + mc.getCurrentServer().ip;
        } else if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
            base = "sp_" + mc.getSingleplayerServer().getWorldData().getLevelName();
        } else {
            base = "unknown";
        }
        String dimension = mc.level != null ? mc.level.dimension().identifier().toString() : "no_dimension";
        return sanitize(base) + "__" + sanitize(dimension);
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }

    public void loadForWorld() {
        flush();
        evaluated.clear();
        stashes.clear();

        File dir = new File(FabricLoader.getInstance().getGameDir().toFile(), "euclient/stashfinder");
        if (!dir.exists()) dir.mkdirs();
        file = new File(dir, currentWorldKey() + ".json");
        if (!file.exists()) return;

        try (FileReader reader = new FileReader(file)) {
            Gson gson = new Gson();
            Type type = new TypeToken<PersistentData>() {}.getType();
            PersistentData data = gson.fromJson(reader, type);
            if (data != null) {
                if (data.evaluated != null) evaluated.addAll(data.evaluated);
                if (data.stashes != null) stashes.addAll(data.stashes);
            }
        } catch (Exception ignored) {}
    }

    private void save() {
        if (file == null) return;
        try (FileWriter writer = new FileWriter(file)) {
            PersistentData data = new PersistentData();
            data.evaluated = evaluated;
            data.stashes = stashes;
            new GsonBuilder().create().toJson(data, writer);
        } catch (Exception ignored) {}
    }

    public boolean isEvaluated(long chunkKey) {
        return evaluated.contains(chunkKey);
    }

    public void markEvaluated(long chunkKey) {
        if (evaluated.add(chunkKey)) dirty = true;
    }

    public void recordStash(int x, int z, String dimension, Map<String, Integer> counts) {
        stashes.add(new StashData(x, z, dimension, counts, System.currentTimeMillis()));
        dirty = true;
    }

    public List<StashData> getStashes() {
        return Collections.unmodifiableList(stashes);
    }

    public void flushIfStale() {
        if (dirty && System.currentTimeMillis() - lastSaveMs >= SAVE_INTERVAL_MS) flush();
    }

    public void flush() {
        if (!dirty) return;
        save();
        dirty = false;
        lastSaveMs = System.currentTimeMillis();
    }

    public void clearAll() {
        evaluated.clear();
        stashes.clear();
        dirty = true;
        flush();
    }
}
