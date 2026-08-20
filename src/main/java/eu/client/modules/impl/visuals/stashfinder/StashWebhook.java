package eu.client.modules.impl.visuals.stashfinder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Sends Discord webhook alerts formatted with an embed containing stash coordinates, server, dimension, and container counts.
 */
public class StashWebhook {

    private static File file() {
        File dir = new File(FabricLoader.getInstance().getGameDir().toFile(), "euclient/stashfinder");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "webhook.json");
    }

    private static volatile String webhookUrl = "";
    private static volatile String userId = "";
    private static volatile boolean loaded = false;

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        File f = file();
        if (!f.exists()) return;
        try (FileReader reader = new FileReader(f)) {
            JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
            if (obj.has("url")) webhookUrl = obj.get("url").getAsString();
            if (obj.has("userId")) userId = obj.get("userId").getAsString();
        } catch (Exception ignored) {}
    }

    private static synchronized void save() {
        try (FileWriter writer = new FileWriter(file())) {
            JsonObject obj = new JsonObject();
            obj.addProperty("url", webhookUrl);
            obj.addProperty("userId", userId);
            writer.write(obj.toString());
        } catch (Exception ignored) {}
    }

    public static String getWebhookUrl() {
        ensureLoaded();
        return webhookUrl;
    }

    public static String getUserId() {
        ensureLoaded();
        return userId;
    }

    public static void setWebhookUrl(String url) {
        ensureLoaded();
        webhookUrl = url;
        save();
    }

    public static void setUserId(String id) {
        ensureLoaded();
        userId = id;
        save();
    }

    private static final long PING_INTERVAL_MS = 5000;
    private static volatile long lastSentAtMs = 0;

    private static synchronized boolean tryClaimSendSlot() {
        long now = System.currentTimeMillis();
        if (now - lastSentAtMs < PING_INTERVAL_MS) return false;
        lastSentAtMs = now;
        return true;
    }

    private static String serverLabel() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getCurrentServer() != null) return mc.getCurrentServer().ip;
        if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
            return "Singleplayer: " + mc.getSingleplayerServer().getWorldData().getLevelName();
        }
        return "Unknown";
    }

    public static void send(int x, int z, String dimension, Map<String, Integer> countsInOrder, String customUrl, String customUserId) {
        String url = (customUrl != null && !customUrl.isBlank()) ? customUrl : getWebhookUrl();
        if (url == null || url.isBlank()) return;
        if (!tryClaimSendSlot()) return;

        String server = serverLabel();
        String user = (customUserId != null && !customUserId.isBlank()) ? customUserId : getUserId();

        CompletableFuture.runAsync(() -> {
            try {
                JsonObject embed = new JsonObject();
                embed.addProperty("title", "Stash Found!");
                embed.addProperty("description", "Coordinates: **X: " + x + " Z: " + z + "** (" + dimension + ")");
                embed.addProperty("color", 0x2ECC71);

                JsonArray fields = new JsonArray();

                JsonObject serverField = new JsonObject();
                serverField.addProperty("name", "Server");
                serverField.addProperty("value", server);
                serverField.addProperty("inline", false);
                fields.add(serverField);

                for (Map.Entry<String, Integer> e : countsInOrder.entrySet()) {
                    JsonObject field = new JsonObject();
                    field.addProperty("name", e.getKey());
                    field.addProperty("value", String.valueOf(e.getValue()));
                    field.addProperty("inline", true);
                    fields.add(field);
                }
                embed.add("fields", fields);

                JsonArray embeds = new JsonArray();
                embeds.add(embed);

                JsonObject payload = new JsonObject();
                payload.addProperty("content", (user == null || user.isBlank()) ? "" : "<@" + user + ">");
                payload.add("embeds", embeds);

                byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);

                URL targetUrl = URI.create(url).toURL();
                HttpURLConnection conn = (HttpURLConnection) targetUrl.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setDoOutput(true);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                    os.flush();
                }

                int responseCode = conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {}
        });
    }
}
