package eu.client.modules.impl.core;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PacketReceiveEvent;
import eu.client.events.impl.TickEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.pingbypass.protocol.PbCustomPayload;
import eu.client.pingbypass.protocol.PbPacket;
import eu.client.pingbypass.protocol.packets.*;
import eu.client.settings.impl.NumberSetting;
import eu.client.settings.impl.StringSetting;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side module that manages the connection to a PingBypass proxy server.
 * Provides IP, Port, and Password settings and displays the current proxy ping.
 *
 * <p>Handles incoming S2C packets from the proxy:</p>
 * <ul>
 *   <li>S2C_MODULE_STATE (ID 5): updates local proxy module mirror state</li>
 *   <li>S2C_SETTING_STATE (ID 6): updates local proxy module mirror settings</li>
 *   <li>S2C_ERROR (ID 7): displays error in chat</li>
 *   <li>S2C_PASSWORD_REQUEST (ID 1): sends C2S_PASSWORD with configured password</li>
 *   <li>S2C_SERVER_NAME (ID 8): displays connected server info</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 4.3, 9.4, 10.1</b></p>
 */
@RegisterModule(name = "PingBypass", description = "Connects to a PingBypass proxy server for low-latency combat.", category = Module.Category.CORE)
public class PingBypassModule extends Module {

    public final StringSetting ip = new StringSetting("IP", "The IP address of the PingBypass proxy server.", "127.0.0.1");
    public final NumberSetting port = new NumberSetting("Port", "The port of the PingBypass proxy server.", 25565, 1, 65535);
    public final StringSetting password = new StringSetting("Password", "The password for the PingBypass proxy server.", "");
    public final StringSetting server = new StringSetting("Server", "The target Minecraft server to join through the proxy (e.g. mc.hypixel.net or mc.hypixel.net:25565).", "");

    /**
     * Whether we've already sent the join request for this session.
     */
    private volatile boolean joinSent;

    /**
     * Current round-trip latency to the proxy in milliseconds.
     * Updated from keep-alive packet round-trips.
     */
    public volatile int proxyPing;

    /**
     * The server name/IP the proxy is currently connected to.
     */
    private volatile String serverName;

    /**
     * Client-side mirror of proxy module states. Key: module name, Value: enabled state.
     */
    private final Map<String, Boolean> proxyModuleStates = new ConcurrentHashMap<>();

    /**
     * Client-side mirror of proxy module settings. Key: "moduleName.settingName", Value: setting value as string.
     */
    private final Map<String, String> proxySettingValues = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        // Guard: refuse to enable in server mode
        if (EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer()) {
            EUClient.CHAT_MANAGER.error("Cannot enable PingBypass on a PingBypass server!");
            setToggled(false, false);
            return;
        }

        // Guard: if the client isn't fully initialized yet (e.g. config loading
        // during startup), skip the connection. User can re-toggle to connect.
        // ConnectScreen.connect() calls mc.disconnect() -> mc.reset() -> mc.render()
        // which requires fields like inactivityFpsLimiter to be initialized.
        if (mc.getOverlay() != null) {
            EUClient.LOGGER.info("[PingBypass] Skipping auto-connect during startup");
            return;
        }

        // Close the ClickGUI if it's open
        if (mc.screen instanceof eu.client.gui.ClickGuiScreen) {
            mc.setScreen(null);
        }

        // Clear mirror state
        proxyModuleStates.clear();
        proxySettingValues.clear();
        serverName = null;
        joinSent = false;

        // Disconnect from current server if connected
        if (mc.level != null) {
            mc.level.disconnect(Component.literal("PingBypass enabled."));
        }
        if (mc.getConnection() != null) {
            mc.getConnection().getConnection().disconnect(Component.literal("PingBypass enabled."));
        }

        // Initiate connection to the proxy server
        String proxyIp = ip.getValue();
        int proxyPort = port.getValue().intValue();
        ServerAddress address = new ServerAddress(proxyIp, proxyPort);
        ServerData serverData = new ServerData("PingBypass Proxy", address.getHost() + ":" + address.getPort(), ServerData.Type.OTHER);

        // Connect to the proxy server
        ConnectScreen.startConnecting(new TitleScreen(), mc, address, serverData, false, null);
    }

    @Override
    public void onDisable() {
        // Disconnect from the proxy
        if (mc.level != null) {
            mc.level.disconnect(Component.literal("PingBypass disabled."));
        }
        if (mc.getConnection() != null) {
            mc.getConnection().getConnection().disconnect(Component.literal("PingBypass disabled."));
        }

        proxyPing = 0;
        serverName = null;
        proxyConnection = null;
        eu.client.pingbypass.PingBypassFlags.proxyForwardingActive = false;
        if (inputForwarder != null) {
            inputForwarder.stop();
            inputForwarder = null;
        }
        proxyModuleStates.clear();
        proxySettingValues.clear();
    }

    @Override
    public String getMetaData() {
        return proxyPing + "ms";
    }

    /**
     * Intercepts incoming S2C custom payload packets on the euclient:pingbypass channel
     * and dispatches them to the appropriate handler.
     */
    /**
     * Active connection to the proxy, captured from incoming packet events.
     * Used to send responses when mc.getConnection() is not yet available.
     */
    private volatile net.minecraft.network.Connection proxyConnection;

    @SubscribeEvent
    public void onDisconnect(eu.client.events.impl.ClientDisconnectEvent event) {
        // Auto-disable PingBypass when we disconnect from the proxy
        if (isToggled() && eu.client.pingbypass.PingBypassFlags.proxyForwardingActive) {
            mc.execute(() -> setToggled(false, false));
        }
    }

    @SubscribeEvent
    public void onSettingChange(eu.client.events.impl.SettingChangeEvent event) {
        // Sync setting changes to the proxy for proxy-enhanced modules
        if (!isToggled() || !eu.client.pingbypass.PingBypassFlags.proxyForwardingActive) return;
        if (EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer()) return;

        // Find which module owns this setting
        eu.client.settings.Setting setting = event.getSetting();
        for (Module module : EUClient.MODULE_MANAGER.getModules()) {
            if (!module.isProxyEnhanced()) continue;
            if (!module.getSettings().contains(setting)) continue;

            // Serialize the value to string
            String value = serializeSetting(setting);
            if (value == null) return;

            // Send to proxy
            net.minecraft.network.Connection connection = this.proxyConnection;
            if (connection == null && mc.getConnection() != null) {
                connection = mc.getConnection().getConnection();
            }
            if (connection != null && connection.isConnected()) {
                connection.send(new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
                        PbCustomPayload.fromPacket(new C2SSettingChangePacket(
                                module.getName(), setting.getName(), value))));
            }
            return;
        }
    }

    private String serializeSetting(eu.client.settings.Setting setting) {
        if (setting instanceof eu.client.settings.impl.BooleanSetting s) return String.valueOf(s.getValue());
        if (setting instanceof eu.client.settings.impl.NumberSetting s) return s.getValue().toString();
        if (setting instanceof eu.client.settings.impl.ModeSetting s) return s.getValue();
        if (setting instanceof eu.client.settings.impl.StringSetting s) return s.getValue();
        if (setting instanceof eu.client.settings.impl.ColorSetting s) {
            java.awt.Color c = s.getValue().getColor();
            return c.getRed() + "," + c.getGreen() + "," + c.getBlue() + "," + c.getAlpha()
                    + "," + s.isSync() + "," + s.isRainbow();
        }
        return null;
    }

    @SubscribeEvent
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPacket() instanceof ClientboundCustomPayloadPacket s2cPacket)) {
            return;
        }

        CustomPacketPayload payload = s2cPacket.payload();
        if (!(payload instanceof PbCustomPayload pbPayload)) {
            return;
        }

        // Capture the connection for sending responses
        if (event.getConnection() != null) {
            this.proxyConnection = event.getConnection();
        }

        FriendlyByteBuf buf = pbPayload.toBuf();
        try {
            int packetId = buf.readVarInt();
            switch (packetId) {
                case S2CPasswordRequestPacket.ID -> handlePasswordRequest();
                case S2CModuleStatePacket.ID -> handleModuleState(new S2CModuleStatePacket(buf));
                case S2CSettingStatePacket.ID -> handleSettingState(new S2CSettingStatePacket(buf));
                case S2CErrorPacket.ID -> handleError(new S2CErrorPacket(buf));
                case S2CServerNamePacket.ID -> handleServerName(new S2CServerNamePacket(buf));
                case S2CRenderPositionPacket.ID -> handleRenderPosition(new S2CRenderPositionPacket(buf));
                case S2CBlockRenderPacket.ID -> handleBlockRender(new S2CBlockRenderPacket(buf));
                case S2CMiningStatePacket.ID -> handleMiningState(new S2CMiningStatePacket(buf));
                case S2CSlotSyncPacket.ID -> handleSlotSync(new S2CSlotSyncPacket(buf));
                case eu.client.pingbypass.protocol.packets.S2CWindowClickPacket.ID ->
                        handleWindowClick(new eu.client.pingbypass.protocol.packets.S2CWindowClickPacket(buf));
            }
        } catch (Exception e) {
            EUClient.LOGGER.warn("[PingBypass] Failed to handle S2C packet", e);
        } finally {
            buf.release();
        }
    }

    /**
     * Handles S2C_PASSWORD_REQUEST by sending C2S_PASSWORD with the configured password.
     * Validates: Requirement 4.3
     */
    private void handlePasswordRequest() {
        net.minecraft.network.Connection connection = this.proxyConnection;
        if (connection == null && mc.getConnection() != null) {
            connection = mc.getConnection().getConnection();
        }

        if (connection == null || !connection.isConnected()) {
            EUClient.LOGGER.warn("[PingBypass] Cannot send password response — no active connection");
            return;
        }

        // Write raw bytes directly to the Netty channel, bypassing the Fabric
        // networking layer's packet codec which isn't ready before GameJoinS2CPacket.
        sendRawCustomPayload(connection, new C2SPasswordPacket(password.getValue()));
        EUClient.LOGGER.info("[PingBypass] Sent password response to proxy");

        sendJoinIfReady(connection);
    }

    /**
     * Sends a C2S_JOIN packet to the proxy if a target server is configured.
     */
    private void sendJoinIfReady(net.minecraft.network.Connection connection) {
        if (joinSent) return;

        String targetServer = server.getValue();
        if (targetServer == null || targetServer.isBlank()) return;

        String host;
        int targetPort = 25565;
        if (targetServer.contains(":")) {
            String[] parts = targetServer.split(":", 2);
            host = parts[0];
            try {
                targetPort = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                EUClient.LOGGER.warn("[PingBypass] Invalid port in server setting: {}", targetServer);
                return;
            }
        } else {
            host = targetServer;
        }

        joinSent = true;
        sendRawCustomPayload(connection, new C2SJoinPacket(host, targetPort));
        EUClient.LOGGER.info("[PingBypass] Sent join request to proxy: {}:{}", host, targetPort);
    }

    /**
     * Sends a PbPacket by writing raw bytes directly to the Netty channel,
     * completely bypassing the Fabric packet splitter and vanilla encoder
     * which can't handle custom payloads before GameJoinS2CPacket.
     */
    private void sendRawCustomPayload(net.minecraft.network.Connection connection, PbPacket packet) {
        PbCustomPayload payload = PbCustomPayload.fromPacket(packet);
        pendingPayloads.add(new PendingPayload(payload, connection));
        pendingDelay = 10; // Wait 10 ticks for PLAY state to be fully ready
    }

    private final java.util.List<PendingPayload> pendingPayloads =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private record PendingPayload(PbCustomPayload payload, net.minecraft.network.Connection connection) {}

    /**
     * Called from a TickEvent to send pending payloads once the client is in game.
     */
    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (pendingPayloads.isEmpty()) return;
        // Wait a few ticks after GameJoinS2CPacket to ensure the client has
        // fully transitioned to PLAY state (outbound codec + Fabric splitter ready)
        if (pendingDelay > 0) {
            pendingDelay--;
            return;
        }

        var iterator = pendingPayloads.iterator();
        while (iterator.hasNext()) {
            var pending = iterator.next();
            if (!pending.connection.isConnected()) {
                iterator.remove();
                continue;
            }
            try {
                // Send via vanilla connection.send() instead of Fabric networking
                // since we don't register C2S payloads with Fabric's PayloadTypeRegistry
                pending.connection.send(new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(pending.payload));
                iterator.remove();
                EUClient.LOGGER.info("[PingBypass] Sent deferred payload via vanilla networking");
            } catch (IllegalStateException e) {
                // Not in game yet, will retry next tick
                pendingDelay = 5; // Wait 5 more ticks
            }
        }
    }
    private int pendingDelay = 10; // Initial delay: 10 ticks (0.5s) after module enabled

    /**
     * Handles S2C_MODULE_STATE by updating the local proxy module mirror state.
     * Validates: Requirement 9.4
     */
    private void handleModuleState(S2CModuleStatePacket packet) {
        proxyModuleStates.put(packet.getModuleName(), packet.isEnabled());
        // Actually toggle the module on the client to sync state (e.g., auto-disable)
        Module module = EUClient.MODULE_MANAGER.getModule(packet.getModuleName());
        if (module != null && module.isToggled() != packet.isEnabled()) {
            mc.execute(() -> module.setToggled(packet.isEnabled(), false));
        }
        EUClient.LOGGER.debug("[PingBypass] Module {} state synced to {}", packet.getModuleName(), packet.isEnabled());
    }

    /**
     * Handles S2C_SETTING_STATE by updating the local proxy module mirror settings.
     * Validates: Requirement 9.4
     */
    private void handleSettingState(S2CSettingStatePacket packet) {
        String key = packet.getModuleName() + "." + packet.getSettingName();
        proxySettingValues.put(key, packet.getValue());
        EUClient.LOGGER.debug("[PingBypass] Setting {}.{} updated to {}", packet.getModuleName(), packet.getSettingName(), packet.getValue());
    }

    /**
     * Handles S2C_ERROR by displaying the error message in chat.
     * Validates: Requirement 9.4
     */
    private void handleError(S2CErrorPacket packet) {
        EUClient.CHAT_MANAGER.error("[PingBypass] " + packet.getMessage());
    }

    /**
     * Handles S2C_SERVER_NAME by storing and displaying the connected server info.
     * Validates: Requirement 10.1
     */
    private void handleServerName(S2CServerNamePacket packet) {
        this.serverName = packet.getServerIp();
        eu.client.pingbypass.PingBypassFlags.proxyForwardingActive = true;
        // Start forwarding mouse/keyboard input to the proxy
        if (inputForwarder == null) {
            inputForwarder = new eu.client.pingbypass.input.ClientInputForwarder();
            inputForwarder.start();
        }
        // Bulk-sync all proxy-enhanced module settings and toggle states to the proxy
        syncAllSettingsToProxy();
        EUClient.CHAT_MANAGER.message("[PingBypass] Proxy connected to: " + packet.getServerIp());
    }

    /**
     * Sends all settings and toggle states for proxy-enhanced modules to the proxy.
     * Called once when the connection is established so the proxy matches the client's config.
     */
    public void syncAllSettingsToProxy() {
        net.minecraft.network.Connection connection = this.proxyConnection;
        if (connection == null && mc.getConnection() != null) {
            connection = mc.getConnection().getConnection();
        }
        if (connection == null || !connection.isConnected()) return;

        int settingCount = 0;
        for (Module module : EUClient.MODULE_MANAGER.getModules()) {
            if (!module.isProxyEnhanced()) continue;

            // Sync toggle state
            connection.send(new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
                    PbCustomPayload.fromPacket(new eu.client.pingbypass.protocol.packets.C2SModuleTogglePacket(
                            module.getName(), module.isToggled()))));

            // Sync all settings
            for (eu.client.settings.Setting setting : module.getSettings()) {
                String value = serializeSetting(setting);
                if (value == null) continue;

                connection.send(new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
                        PbCustomPayload.fromPacket(new C2SSettingChangePacket(
                                module.getName(), setting.getName(), value))));
                settingCount++;
            }
        }
        EUClient.LOGGER.info("[PingBypass] Synced {} settings to proxy", settingCount);
        syncFriendsToProxy();
    }

    /**
     * Sends the full friends list to the proxy so its own target-scanning (AutoCrystal.getPlayers(),
     * KillAura) actually excludes them. FriendManager has no other sync path at all -- it's loaded
     * from a local config file the proxy's VPS process never has.
     */
    public void syncFriendsToProxy() {
        net.minecraft.network.Connection connection = this.proxyConnection;
        if (connection == null && mc.getConnection() != null) {
            connection = mc.getConnection().getConnection();
        }
        if (connection == null || !connection.isConnected()) return;

        connection.send(new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
                PbCustomPayload.fromPacket(new eu.client.pingbypass.protocol.packets.C2SFriendSyncPacket(
                        new java.util.ArrayList<>(EUClient.FRIEND_MANAGER.getFriends())))));
    }

    private eu.client.pingbypass.input.ClientInputForwarder inputForwarder;

    /**
     * Handles S2C_RENDER_POSITION — syncs the AutoCrystal render position from proxy.
     */
    private void handleRenderPosition(S2CRenderPositionPacket packet) {
        EUClient.RENDER_MANAGER.setRenderPosition(packet.getPosition());
    }

    /**
     * Handles S2C_BLOCK_RENDER — syncs block placement renders (Surround, SelfTrap, etc.) from proxy.
     */
    private void handleBlockRender(S2CBlockRenderPacket packet) {
        eu.client.utils.miscellaneous.RenderPosition renderPosition = new eu.client.utils.miscellaneous.RenderPosition(packet.getPosition());
        if (!EUClient.RENDER_MANAGER.renderPositions.contains(renderPosition)) {
            EUClient.RENDER_MANAGER.renderPositions.add(renderPosition);
        }
    }

    /**
     * Handles S2C_MINING_STATE — syncs SpeedMine render state from proxy.
     */
    private void handleMiningState(S2CMiningStatePacket packet) {
        eu.client.modules.impl.player.SpeedMineModule speedMine =
                EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.player.SpeedMineModule.class);
        if (speedMine != null) {
            speedMine.updateProxyMiningState(packet.getPrimaryPos(), packet.getPrimaryProgress(),
                    packet.getSecondaryPos(), packet.getSecondaryProgress());
        }
    }

    /**
     * Handles S2C_SLOT_SYNC — mirrors a proxy-side "Normal" hotbar switch onto
     * the real client's own hotbar. Cosmetic only; the proxy already sent the
     * real ServerboundSetCarriedItemPacket to the actual server itself.
     */
    private void handleSlotSync(S2CSlotSyncPacket packet) {
        if (mc.player != null) {
            mc.player.getInventory().setSelectedSlot(packet.getSlot());
        }
    }

    /**
     * Handles S2C_WINDOW_CLICK -- replays a container click the PROXY made on its own (a module's
     * AltSwap/AltPickup switch) onto the real client's own container, matching earthhack's real
     * S2CWindowClick. Uses AbstractContainerMenu.clicked() directly, NOT
     * mc.gameMode.handleContainerInput() -- the latter would send a fresh
     * ServerboundContainerClickPacket back to the proxy and double-apply the click.
     */
    private void handleWindowClick(eu.client.pingbypass.protocol.packets.S2CWindowClickPacket packet) {
        mc.execute(() -> {
            if (mc.player == null || mc.player.containerMenu.containerId != packet.getContainerId()) return;
            try {
                mc.player.containerMenu.clicked(packet.getSlotNum(), packet.getButtonNum(), packet.getContainerInput(), mc.player);
            } catch (Exception e) {
                EUClient.LOGGER.warn("[PingBypass] Failed to replay proxy window click", e);
            }
        });
    }

    /**
     * Returns the server name/IP the proxy is currently connected to.
     */
    public String getServerName() {
        return serverName;
    }

    /**
     * Returns the client-side mirror of proxy module states.
     */
    public Map<String, Boolean> getProxyModuleStates() {
        return proxyModuleStates;
    }

    /**
     * Returns the client-side mirror of proxy module setting values.
     */
    public Map<String, String> getProxySettingValues() {
        return proxySettingValues;
    }

    /**
     * Checks if a proxy module is enabled according to the local mirror.
     */
    public boolean isProxyModuleEnabled(String moduleName) {
        return proxyModuleStates.getOrDefault(moduleName, false);
    }

    /**
     * Gets a proxy module setting value from the local mirror.
     */
    public String getProxySettingValue(String moduleName, String settingName) {
        return proxySettingValues.get(moduleName + "." + settingName);
    }
}
