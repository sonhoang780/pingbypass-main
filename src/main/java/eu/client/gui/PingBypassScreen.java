package eu.client.gui;

import eu.client.EUClient;
import eu.client.pingbypass.PingBypassConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * PingBypass connection screen — allows configuring proxy IP/port/password
 * and target server, then connecting or resuming a session.
 */
public class PingBypassScreen extends Screen {
    private final Screen parent;
    private EditBox ipField;
    private EditBox portField;
    private EditBox passwordField;
    private EditBox serverField;
    private Button connectButton;
    private Button resumeButton;
    private volatile String proxyStatus = "Pinging...";
    private volatile boolean pinged = false;

    public PingBypassScreen(Screen parent) {
        super(Component.literal("PingBypass"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int startY = 50;

        // IP field
        ipField = new EditBox(this.font, centerX - 150, startY, 300, 20, Component.literal("Proxy IP"));
        ipField.setMaxLength(256);
        ipField.setValue(getConfig().getIp());
        ipField.setHint(Component.literal("127.0.0.1"));
        addRenderableWidget(ipField);

        // Port field
        portField = new EditBox(this.font, centerX - 150, startY + 34, 145, 20, Component.literal("Port"));
        portField.setMaxLength(5);
        portField.setValue(String.valueOf(getConfig().getPort()));
        portField.setHint(Component.literal("25565"));
        addRenderableWidget(portField);

        // Password field
        passwordField = new EditBox(this.font, centerX + 5, startY + 34, 145, 20, Component.literal("Password"));
        passwordField.setMaxLength(64);
        passwordField.setValue(getConfig().getPassword());
        passwordField.setHint(Component.literal("Password"));
        addRenderableWidget(passwordField);

        // Server target field
        serverField = new EditBox(this.font, centerX - 150, startY + 68, 300, 20, Component.literal("Target Server"));
        serverField.setMaxLength(256);
        String savedServer = EUClient.MODULE_MANAGER.getModule(
                eu.client.modules.impl.core.PingBypassModule.class).server.getValue();
        serverField.setValue(savedServer.isEmpty() ? "" : savedServer);
        serverField.setHint(Component.literal("mc.server.com:25565"));
        addRenderableWidget(serverField);

        // Connect button
        connectButton = Button.builder(Component.literal("Connect"), button -> connect())
                .bounds(centerX - 150, startY + 110, 300, 20)
                .build();
        addRenderableWidget(connectButton);

        // Resume button (only active if PingBypass module is toggled = connected to proxy)
        var pbModule = EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.core.PingBypassModule.class);
        resumeButton = Button.builder(Component.literal("Resume Session"), button -> resume())
                .bounds(centerX - 150, startY + 138, 300, 20)
                .build();
        resumeButton.active = pbModule != null && pbModule.isToggled();
        addRenderableWidget(resumeButton);

        // Back button
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
                .bounds(centerX - 150, this.height - 30, 300, 20)
                .build());

        // Ping the proxy to check its status
        pingProxy();
    }

    private void pingProxy() {
        pinged = false;
        proxyStatus = "Pinging...";
        String proxyIp = getConfig().getIp();
        int proxyPort = getConfig().getPort();

        new Thread(() -> {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(proxyIp, proxyPort), 2000);
                proxyStatus = "Proxy online";
                pinged = true;
            } catch (Exception e) {
                proxyStatus = "§cOffline — cannot reach proxy";
                pinged = true;
            }
        }, "PingBypass-Pinger").start();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);

        // Title
        context.centeredText(this.font, "PingBypass", width / 2, 20, 0xFFFFFF);

        // Labels
        int centerX = width / 2;
        int startY = 50;
        context.text(this.font, "Proxy IP", centerX - 150, startY - 11, 0xA0A0A0);
        context.text(this.font, "Port", centerX - 150, startY + 23, 0xA0A0A0);
        context.text(this.font, "Password", centerX + 5, startY + 23, 0xA0A0A0);
        context.text(this.font, "Target Server", centerX - 150, startY + 57, 0xA0A0A0);

        // Status from ping
        int statusColor = proxyStatus.contains("Connected") ? 0x55FF55
                : proxyStatus.contains("Idle") ? 0xFFAA00
                : proxyStatus.contains("Offline") ? 0xFF5555
                : 0xAAAAAA;
        context.centeredText(this.font, proxyStatus, width / 2, startY + 165, statusColor);

        // Active session status
        if (eu.client.pingbypass.PingBypassFlags.proxyForwardingActive) {
            var pbModule = EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.core.PingBypassModule.class);
            String session = "Session active — " + (pbModule.getServerName() != null ? pbModule.getServerName() : "connected");
            context.centeredText(this.font, session, width / 2, startY + 178, 0x55FF55);
        }
    }

    private void connect() {
        // Save settings to the module
        var pbModule = EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.core.PingBypassModule.class);
        pbModule.ip.setValue(ipField.getValue().trim());
        try {
            pbModule.port.setValue(Integer.parseInt(portField.getValue().trim()));
        } catch (NumberFormatException ignored) {}
        pbModule.password.setValue(passwordField.getValue());
        pbModule.server.setValue(serverField.getValue().trim());

        // Disable first if already toggled (reset state), then re-enable
        if (pbModule.isToggled()) {
            pbModule.setToggled(false, false);
        }
        pbModule.setToggled(true);
    }

    private void resume() {
        // Return to parent — session is already active, just close this screen
        this.minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    private PingBypassConfig getConfig() {
        return EUClient.PINGBYPASS_CONFIG;
    }
}
