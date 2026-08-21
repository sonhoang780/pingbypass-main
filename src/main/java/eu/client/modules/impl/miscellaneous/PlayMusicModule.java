package eu.client.modules.impl.miscellaneous;

import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.AudioPlayerInputStream;
import com.sedmelluq.discord.lavaplayer.format.Pcm16AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.ChatInputEvent;
import eu.client.events.impl.TickEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.NumberSetting;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import eu.client.EUClient;
import eu.client.modules.impl.core.ClickGuiModule;
import org.lwjgl.glfw.GLFW;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// PORT (26.2): PlayMusic disabled alongside MusicHUD (its Skia-rendered display, see
// HUDModule/MusicHUDComponent) -- module never registers so INSTANCE stays null.
// @RegisterModule(name = "PlayMusic", description = "Direct YouTube music player with chat suggestions.", category = Module.Category.MISCELLANEOUS)
public class PlayMusicModule extends Module {
    public static PlayMusicModule INSTANCE;

    public static String CHAT_PREFIX = ">";
    private static final File PREFIX_FILE = new File(FabricLoader.getInstance().getGameDir().toFile(), "playmusic_prefix.txt");

    static {
        try {
            if (PREFIX_FILE.exists()) {
                String content = Files.readString(PREFIX_FILE.toPath()).trim();
                if (!content.isEmpty() && content.length() <= 3) CHAT_PREFIX = content;
            }
        } catch (Exception ignored) {}
    }

    public final BooleanSetting openGuiBtn  = new BooleanSetting("OpenMusicUI", "Click to open the music control interface.", false);
    public final BooleanSetting chatSearch  = new BooleanSetting("ChatSearch", "Enable searching music and showing live suggestions via chat.", true);
    public final NumberSetting volume      = new NumberSetting("Volume", "Adjust the media playback volume.", 50.0, 0.0, 100.0);
    public final BooleanSetting previousBtn = new BooleanSetting("Previous", "Play the previous track from history.", false);
    public final BooleanSetting togglePause = new BooleanSetting("PlayPause", "Pause or resume current track playback.", false);
    public final BooleanSetting nextBtn     = new BooleanSetting("Next", "Skip the current track.", false);
    public final BooleanSetting loopCurrent = new BooleanSetting("Loop", "Loop the currently playing track infinitely.", false);
    public final BooleanSetting autoPlay    = new BooleanSetting("AutoPlayNext", "Automatically queue related tracks when empty.", true);
    public final BooleanSetting logoutBtn   = new BooleanSetting("LogoutYT", "Log out of your YouTube account from the client.", false);

    private static AudioPlayerManager playerManager;
    private static AudioPlayer player;
    private static TrackScheduler scheduler;
    private static StreamPlayer streamPlayer;
    private static Thread soundThread;
    private static YoutubeAudioSourceManager ytSourceManager;

    public static int playCount = 0;

    private static final Set<String> playedHistory = Collections.newSetFromMap(
        new LinkedHashMap<String, Boolean>(100, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) { return size() > 100; }
        }
    );

    private final List<String> currentSuggestions = new CopyOnWriteArrayList<>();
    private String lastQuery = "";
    private String pendingQuery = "";
    private int suggestionCooldown = 0;

    private boolean wasMouseDown = false;
    private boolean wasTabDown = false;
    private boolean wasLeftArrowDown = false;
    private boolean wasRightArrowDown = false;
    private long lastTrackSkipMs = 0;

    private double animSuggestHeight = 0.0;
    public static volatile float currentAmplitude = 0f;
    private static boolean consoleHooked = false;

    public PlayMusicModule() {
        INSTANCE = this;
    }

    public static AudioTrack getCurrentTrack() { return player != null ? player.getPlayingTrack() : null; }
    public static boolean isPlayerPaused() { return player != null && player.isPaused(); }
    public static void seekTo(long positionMs) {
        if (player != null && player.getPlayingTrack() != null) {
            player.getPlayingTrack().setPosition(Math.max(0, Math.min(positionMs, player.getPlayingTrack().getDuration())));
        }
    }

    public static void setPausedExternal(boolean paused) {
        if (player != null) player.setPaused(paused);
    }

    @Override
    public void onEnable() {
        try {
            initAudioEngine();
        } catch (Throwable t) {
            System.err.println("[PlayMusic] onEnable init failed: " + t);
        }
    }

    private void initAudioEngine() {
        hookConsoleForOauth();

        if (playerManager == null) {
            playerManager = new DefaultAudioPlayerManager();
            playerManager.getConfiguration().setOutputFormat(StandardAudioDataFormats.COMMON_PCM_S16_BE);
            playerManager.setPlayerCleanupThreshold(Long.MAX_VALUE);
            
            // Khôi phục chính xác 4 Client chuẩn từ bản OG
            ytSourceManager = new YoutubeAudioSourceManager(true,
                    new dev.lavalink.youtube.clients.Music(),
                    new dev.lavalink.youtube.clients.TvHtml5Simply(),
                    new dev.lavalink.youtube.clients.AndroidVr(),
                    new dev.lavalink.youtube.clients.Web());

            String token = readToken();
            if (token.isEmpty()) {
                CompletableFuture.runAsync(() -> ytSourceManager.useOauth2(null, false));
                CompletableFuture.runAsync(() -> {
                    try { Thread.sleep(3000); } catch (Exception ignored) {}
                    if (mc.player != null) {
                        mc.execute(() -> {
                            safeInfo("========================================");
                            safeInfo("Welcome to Music Player!");
                            safeInfo("To play age-restricted or premium tracks, type §a" + CHAT_PREFIX + "login §fin chat.");
                            safeInfo("========================================");
                        });
                    }
                });
            } else {
                // Khởi tạo đồng bộ tức thì trước khi đăng ký SourceManager
                ytSourceManager.useOauth2(token, true);
            }

            playerManager.registerSourceManager(ytSourceManager);
            AudioSourceManagers.registerRemoteSources(playerManager, com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager.class);

            player = playerManager.createPlayer();
            player.setVolume(volume.getValue().intValue());
            scheduler = new TrackScheduler();
            player.addListener(scheduler);
        }
        if (streamPlayer == null || !streamPlayer.playing) {
            streamPlayer = new StreamPlayer();
            soundThread = new Thread(streamPlayer, "EUClient-Audio-Thread");
            soundThread.setDaemon(true);
            soundThread.start();
        } else if (player != null && player.isPaused()) {
            player.setPaused(false);
        }
    }

    @Override
    public void onDisable() {
        currentSuggestions.clear();
        animSuggestHeight = 0.0;
        if (streamPlayer != null) {
            streamPlayer.stop = true;
            if (streamPlayer.line != null) streamPlayer.line.flush();
            streamPlayer = null;
        }
        if (soundThread != null && soundThread.isAlive()) soundThread.interrupt();
        if (player != null) player.stopTrack();
    }

    public static void stopCurrentTrack() {
        if (player != null) player.stopTrack();
        if (streamPlayer != null && streamPlayer.line != null) streamPlayer.line.flush();
    }

    @SubscribeEvent
    public void onChatMessage(ChatInputEvent event) {
        if (!isToggled() || !chatSearch.getValue()) return;
        String message = event.getMessage();
        if (message != null && message.startsWith(CHAT_PREFIX)) {
            event.cancel();
            String query = message.substring(CHAT_PREFIX.length()).trim();

            if (query.toLowerCase().startsWith("prefix")) {
                String newPrefix = query.substring(6).trim();
                if (!newPrefix.isEmpty() && newPrefix.length() <= 3) {
                    CHAT_PREFIX = newPrefix;
                    try { Files.writeString(PREFIX_FILE.toPath(), CHAT_PREFIX); } catch (Exception ignored) {}
                    safeInfo("Chat Prefix changed to: §e" + CHAT_PREFIX);
                } else safeError("Invalid prefix! Use 1 to 3 characters.");
                return;
            }

            if (query.equalsIgnoreCase("login")) {
                safeInfo("§e[YouTube Login] §fInitiating login process...");
                safeInfo("§fOpening browser to §bhttps://www.google.com/device §f...");
                try {
                    if (ytSourceManager != null) {
                        ytSourceManager.useOauth2(null, false);
                    }
                    net.minecraft.util.Util.getPlatform().openUri(new java.net.URI("https://www.google.com/device"));
                } catch (Exception e) {
                    safeError("Failed to open browser.");
                }
                currentSuggestions.clear();
                return;
            }

            if (!query.isEmpty()) {
                searchAndPlay(query);
            } else safeError("Search query cannot be empty! Type §e" + CHAT_PREFIX + "prefix <new_prefix> §cto change prefix.");
            currentSuggestions.clear();
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.getWindow() == null) return;
        long win = mc.getWindow().handle();
        boolean mouseDown = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean tabDown = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_TAB) == GLFW.GLFW_PRESS;

        boolean justClicked = mouseDown && !wasMouseDown;
        boolean justTabbed = tabDown && !wasTabDown;

        wasMouseDown = mouseDown;
        wasTabDown = tabDown;

        if (isToggled() && mc.gui.screen() == null && mc.mouseHandler.isMouseGrabbed()) {
            boolean upDown = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_UP) == GLFW.GLFW_PRESS;
            boolean downDown = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_DOWN) == GLFW.GLFW_PRESS;
            boolean leftDown = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_LEFT) == GLFW.GLFW_PRESS;
            boolean rightDown = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_RIGHT) == GLFW.GLFW_PRESS;

            if (upDown) volume.setValue(Math.min(100.0, volume.getValue().doubleValue() + 2.0));
            if (downDown) volume.setValue(Math.max(0.0, volume.getValue().doubleValue() - 2.0));

            boolean justLeftArrow = leftDown && !wasLeftArrowDown;
            boolean justRightArrow = rightDown && !wasRightArrowDown;
            long nowMs = System.currentTimeMillis();
            if (justRightArrow && scheduler != null && nowMs - lastTrackSkipMs > 300) {
                scheduler.nextTrack(); lastTrackSkipMs = nowMs;
            }
            if (justLeftArrow && scheduler != null && nowMs - lastTrackSkipMs > 300) {
                scheduler.previousTrack(); lastTrackSkipMs = nowMs;
            }

            wasLeftArrowDown = leftDown;
            wasRightArrowDown = rightDown;
        }

        if (isToggled() && chatSearch.getValue() && mc.gui.screen() instanceof ChatScreen) {
            EditBox chatField = null;
            for (GuiEventListener e : mc.gui.screen().children()) {
                if (e instanceof EditBox tf) {
                    chatField = tf;
                    break;
                }
            }

            if (chatField != null) {
                String text = chatField.getValue();
                if (text.startsWith(CHAT_PREFIX)) {
                    String query = text.substring(CHAT_PREFIX.length()).trim();

                    if (justTabbed && !currentSuggestions.isEmpty()) {
                        chatField.setValue(CHAT_PREFIX + currentSuggestions.get(0) + " ");
                        currentSuggestions.clear();
                        lastQuery = chatField.getValue();
                        pendingQuery = "";
                        suggestionCooldown = 0;
                    }

                    if (!text.equals(lastQuery)) {
                        lastQuery = text;
                        pendingQuery = query;
                        suggestionCooldown = 12;
                    }
                } else {
                    currentSuggestions.clear();
                    lastQuery = text;
                    pendingQuery = "";
                }
            }

            if (suggestionCooldown > 0) {
                suggestionCooldown--;
                if (suggestionCooldown == 0 && !pendingQuery.isEmpty()) {
                    fetchYouTubeSuggestions(pendingQuery);
                }
            }
        } else {
            if (!currentSuggestions.isEmpty()) currentSuggestions.clear();
            lastQuery = "";
            pendingQuery = "";
        }

        if (openGuiBtn.getValue()) {
            openGuiBtn.setValue(false);
            mc.execute(() -> mc.gui.setScreen(new eu.client.gui.screens.MusicScreen()));
        }
        if (player != null && player.getVolume() != volume.getValue().intValue()) {
            player.setVolume(volume.getValue().intValue());
        }

        if (togglePause.getValue()) {
            togglePause.setValue(false);
            if (player != null) player.setPaused(!player.isPaused());
        }
        if (nextBtn.getValue()) {
            nextBtn.setValue(false);
            if (scheduler != null) scheduler.nextTrack();
        }
        if (previousBtn.getValue()) {
            previousBtn.setValue(false);
            if (scheduler != null) scheduler.previousTrack();
        }
        if (logoutBtn.getValue()) {
            logoutBtn.setValue(false);
            saveToken("");
            if (playerManager != null) { playerManager.shutdown(); playerManager = null; }
            if (streamPlayer != null) { streamPlayer.stop = true; streamPlayer = null; }
            playedHistory.clear();
            safeInfo("Logged out successfully!");
        }
    }

    private void hookConsoleForOauth() {
        if (consoleHooked) return;
        consoleHooked = true;
        try {
            org.apache.logging.log4j.core.Logger targetLogger =
                (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger(dev.lavalink.youtube.http.YoutubeOauth2Handler.class);
            org.apache.logging.log4j.core.Appender appender = new org.apache.logging.log4j.core.appender.AbstractAppender(
                    "PlayMusicOauthCapture", null,
                    org.apache.logging.log4j.core.layout.PatternLayout.createDefaultLayout(), false, null) {
                @Override
                public void append(org.apache.logging.log4j.core.LogEvent event) {
                    handleOauthLogLine(event.getMessage().getFormattedMessage());
                }
            };
            appender.start();
            targetLogger.addAppender(appender);
        } catch (Exception e) {
            System.err.println("[PlayMusic] Failed to hook YouTube OAuth logger: " + e);
        }
    }

    private void handleOauthLogLine(String line) {
        Matcher m = Pattern.compile("enter code\\s+([A-Za-z0-9-]+)", Pattern.CASE_INSENSITIVE).matcher(line);
        if (m.find()) {
            String code = m.group(1);
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> mc.keyboardHandler.setClipboard(code));
            safeInfo("========================================");
            safeInfo("§a[YouTube Auth] §fAction required!");
            safeInfo("§fYour Login Code is: §b§l" + code + " §7(copied to clipboard)");
            safeInfo("§fPlease enter it in the opened browser window.");
            safeInfo("========================================");
            return;
        }
        String lower = line.toLowerCase();
        if (lower.contains("token retrieved successfully") || lower.contains("access token refreshed successfully")) {
            safeInfo("§a[YouTube Auth] §fLogin successful! You can now play any track.");
            try {
                if (ytSourceManager != null && ytSourceManager.getOauth2RefreshToken() != null) {
                    saveToken(ytSourceManager.getOauth2RefreshToken().trim());
                }
            } catch (Exception ignored) {}
        }
    }

    private void fetchYouTubeSuggestions(String query) {
        CompletableFuture.runAsync(() -> {
            try {
                URL url = new URL("https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=" + URLEncoder.encode(query, "UTF-8"));
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(1500);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                InputStream in = conn.getInputStream();
                String json = new String(in.readAllBytes(), "UTF-8");
                in.close(); conn.disconnect();

                int secondArrayStart = json.indexOf(",[");
                if (secondArrayStart != -1) {
                    String suggestionsPart = json.substring(secondArrayStart + 2);
                    List<String> list = new ArrayList<>();

                    Matcher matcher = Pattern.compile("\"([^\"]*)\"").matcher(suggestionsPart);
                    while (matcher.find()) {
                        String clean = matcher.group(1).trim();
                        if (!clean.isEmpty()) list.add(clean);
                        if (list.size() >= 5) break;
                    }

                    currentSuggestions.clear();
                    currentSuggestions.addAll(list);
                }
            } catch(Exception e) {
                currentSuggestions.clear();
            }
        });
    }

    public void searchAndPlay(String keyword) {
        if (playerManager == null || keyword.trim().isEmpty()) return;
        safeInfo("Searching: " + keyword + "...");
        String query = keyword.startsWith("http") ? keyword : "ytsearch:" + keyword;

        playerManager.loadItem(query, new AudioLoadResultHandler() {
            @Override public void trackLoaded(AudioTrack track) { forcePlayInstantly(track); }
            @Override public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.getTracks().isEmpty()) return;
                if (playlist.isSearchResult()) { forcePlayInstantly(playlist.getTracks().get(0)); return; }
                safeInfo("Loaded playlist: §e" + playlist.getName() + " §a(" + playlist.getTracks().size() + " tracks)");
                if (scheduler != null) scheduler.queue.clear();
                forcePlayInstantly(playlist.getTracks().get(0));
                if (scheduler != null) {
                    for (int i = 1; i < playlist.getTracks().size(); i++) scheduler.queue.add(playlist.getTracks().get(i));
                }
            }
            @Override public void noMatches() { safeError("No matches found!"); }
            @Override public void loadFailed(FriendlyException e) { safeError("Load failed: " + e.getMessage()); }
        });
    }

    public void searchAndPlayFromGUI(String keyword) {
        if (!isToggled()) { safeError("PlayMusic module must be enabled first!"); return; }
        searchAndPlay(keyword);
    }

    public void forcePlayInstantly(AudioTrack track) {
        if (scheduler != null) scheduler.queue.clear();
        if (player != null) { player.setPaused(false); player.startTrack(track, false); }

        if (scheduler != null) scheduler.historyQueue.add(track.makeClone());
        if (scheduler != null && scheduler.historyQueue.size() > 50) scheduler.historyQueue.remove(0);

        String id = track.getIdentifier();
        playedHistory.remove(id); playedHistory.add(id);

        playCount++;
        safeInfo("Now playing: §e" + track.getInfo().title);
    }

    private String readToken() {
        try {
            File f = new File(FabricLoader.getInstance().getGameDir().toFile(), "playmusic_token.txt");
            if (f.exists()) return new String(Files.readAllBytes(f.toPath())).trim();
        } catch (Exception ignored) {}
        return "";
    }

    private void saveToken(String token) {
        try {
            File f = new File(FabricLoader.getInstance().getGameDir().toFile(), "playmusic_token.txt");
            Files.write(f.toPath(), token.getBytes());
        } catch (Exception ignored) {}
    }

    public void safeInfo(String msg) {
        if (mc.player != null) {
            mc.execute(() -> {
                int rgb = 0x00D4FF;
                try {
                    ClickGuiModule clickGui = EUClient.MODULE_MANAGER.getModule(ClickGuiModule.class);
                    if (clickGui != null && clickGui.color != null) {
                        rgb = clickGui.color.getColor().getRGB() & 0xFFFFFF;
                    }
                } catch (Exception ignored) {}

                Component prefix = Component.literal("[Music] ")
                        .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)));
                Component body = Component.literal("§a" + msg);
                mc.player.sendSystemMessage(Component.empty().append(prefix).append(body));
            });
        }
    }

    public void safeError(String msg) {
        if (mc.player != null) {
            mc.execute(() -> {
                int rgb = 0x00D4FF;
                try {
                    ClickGuiModule clickGui = EUClient.MODULE_MANAGER.getModule(ClickGuiModule.class);
                    if (clickGui != null && clickGui.color != null) {
                        rgb = clickGui.color.getColor().getRGB() & 0xFFFFFF;
                    }
                } catch (Exception ignored) {}

                Component prefix = Component.literal("[Music] ")
                        .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)));
                Component body = Component.literal("§c" + msg);
                mc.player.sendSystemMessage(Component.empty().append(prefix).append(body));
            });
        }
    }

    public class TrackScheduler extends AudioEventAdapter {
        public final List<AudioTrack> queue = new ArrayList<>();
        public final List<AudioTrack> historyQueue = new ArrayList<>();
        private int consecutiveLoadFailures = 0;
        private static final int MAX_CONSECUTIVE_LOAD_FAILURES = 3;

        public void nextTrack() {
            if (!queue.isEmpty()) {
                forcePlayInstantly(queue.remove(0));
            } else if (autoPlay.getValue() && player.getPlayingTrack() != null) {
                AudioTrack current = player.getPlayingTrack();
                player.stopTrack();
                safeInfo("Loading recommendations from YouTube Music...");
                loadAutoMix(current);
            } else {
                player.stopTrack();
                safeInfo("Queue is empty.");
            }
        }

        public void previousTrack() {
            if (historyQueue.size() >= 2) {
                historyQueue.remove(historyQueue.size() - 1);
                AudioTrack previousTrack = historyQueue.remove(historyQueue.size() - 1);
                forcePlayInstantly(previousTrack);
            } else {
                safeInfo("No previous tracks found in history!");
            }
        }

        @Override
        public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
            if (player.isPaused()) return;

            if (endReason == AudioTrackEndReason.LOAD_FAILED) consecutiveLoadFailures++;
            else consecutiveLoadFailures = 0;

            if (endReason.mayStartNext) {
                if (loopCurrent.getValue()) {
                    player.startTrack(track.makeClone(), false);
                } else if (!queue.isEmpty()) {
                    nextTrack();
                } else if (autoPlay.getValue()) {
                    if (consecutiveLoadFailures >= MAX_CONSECUTIVE_LOAD_FAILURES) {
                        safeError(consecutiveLoadFailures + " tracks failed to stream -- stopping autoplay. Check your network.");
                        consecutiveLoadFailures = 0;
                        return;
                    }
                    loadAutoMix(track);
                }
            }
        }

        private void loadAutoMix(AudioTrack sourceTrack) {
            String trackId = sourceTrack.getIdentifier();
            String trackTitle = sourceTrack.getInfo().title;
            String mixUrl = "https://music.youtube.com/watch?v=" + trackId + "&list=RDAMVM" + trackId;
            playerManager.loadItem(mixUrl, new AudioLoadResultHandler() {
                @Override public void trackLoaded(AudioTrack t) { forcePlayInstantly(t); }
                @Override public void playlistLoaded(AudioPlaylist playlist) {
                    for (AudioTrack t : playlist.getTracks()) {
                        String nextId = t.getIdentifier();
                        if (!nextId.equals(trackId) && !playedHistory.contains(nextId)) {
                            forcePlayInstantly(t); return;
                        }
                    }
                    for (AudioTrack t : playlist.getTracks()) {
                        if (!t.getIdentifier().equals(trackId)) {
                            forcePlayInstantly(t); return;
                        }
                    }
                    trySearchFallback(trackTitle, trackId);
                }
                @Override public void noMatches() { trySearchFallback(trackTitle, trackId); }
                @Override public void loadFailed(FriendlyException e) { trySearchFallback(trackTitle, trackId); }
            });
        }

        private void trySearchFallback(String title, String excludeId) {
            if (title == null || title.isEmpty()) return;
            playerManager.loadItem("ytsearch:" + title, new AudioLoadResultHandler() {
                @Override public void trackLoaded(AudioTrack t) {
                    if (!t.getIdentifier().equals(excludeId)) forcePlayInstantly(t);
                }
                @Override public void playlistLoaded(AudioPlaylist playlist) {
                    for (AudioTrack t : playlist.getTracks()) {
                        if (!t.getIdentifier().equals(excludeId) && !playedHistory.contains(t.getIdentifier())) {
                            forcePlayInstantly(t); return;
                        }
                    }
                    for (AudioTrack t : playlist.getTracks()) {
                        if (!t.getIdentifier().equals(excludeId)) {
                            forcePlayInstantly(t); return;
                        }
                    }
                }
                @Override public void noMatches() {}
                @Override public void loadFailed(FriendlyException e) {}
            });
        }
    }

    private class StreamPlayer implements Runnable {
        public boolean stop = false, playing = false;
        public SourceDataLine line;

        @Override
        public void run() {
            AudioDataFormat format = new Pcm16AudioDataFormat(2, 44100, StandardAudioDataFormats.COMMON_PCM_S16_BE.chunkSampleCount, true);
            AudioInputStream stream = AudioPlayerInputStream.createStream(player, format, 10000L, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, stream.getFormat());
            try {
                line = (SourceDataLine) AudioSystem.getLine(info);
                line.open(stream.getFormat());
                line.start();
                playing = true;
                byte[] buffer = new byte[StandardAudioDataFormats.COMMON_PCM_S16_BE.maximumChunkSize()];
                int chunkSize;

                while (!stop) {
                    if (player != null && player.isPaused()) {
                        PlayMusicModule.currentAmplitude = 0f;
                        Thread.sleep(10);
                        continue;
                    }

                    chunkSize = stream.read(buffer);
                    if (chunkSize == -1) break;
                    if (chunkSize == 0) {
                        PlayMusicModule.currentAmplitude = 0f;
                        Thread.sleep(10);
                        continue;
                    }

                    int maxSample = 0;
                    for (int i = 0; i < chunkSize - 1; i += 2) {
                        short sample = (short) ((buffer[i] << 8) | (buffer[i + 1] & 0xFF));
                        int absSample = Math.abs((int) sample);
                        if (absSample > maxSample) maxSample = absSample;
                    }
                    float rawPeak = maxSample / 32768.0f;
                    float currentVol = player.getVolume() / 100.0f;
                    if (currentVol > 0.05f) rawPeak = rawPeak / currentVol;
                    PlayMusicModule.currentAmplitude = Math.min(1.0f, rawPeak * 1.8f);

                    line.write(buffer, 0, chunkSize);
                }
            } catch (Exception ignored) {
            } finally {
                PlayMusicModule.currentAmplitude = 0f;
                if (line != null) {
                    line.drain();
                    line.stop();
                    line.close();
                }
                playing = false;
            }
        }
    }
}