package eu.client.modules.impl.core;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.TickEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.StringSetting;
import eu.client.utils.system.MathUtils;
import eu.client.utils.system.Timer;
import meteordevelopment.discordipc.DiscordIPC;
import meteordevelopment.discordipc.RichPresence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RegisterModule(
        name = "RPC",
        description = "Enables Discord Rich Presence for the client.",
        category = Module.Category.CORE
)
public class RPCModule extends Module {
    private static final Logger LOGGER = LoggerFactory.getLogger("EUClient/RPC");

    public ModeSetting detailsMode = new ModeSetting(
            "Details",
            "The mode for the discord presence details.",
            "Random",
            new String[]{"Custom", "Random"}
    );

    public StringSetting customDetails = new StringSetting(
            "CustomDetails",
            "Custom RPC text.",
            new ModeSetting.Visibility(detailsMode, "Custom"),
            "margielaware.cc"
    );

    private final String[] DETAILS = {
            "my richness powered by EUClient"
    };

    private final RichPresence rpc = new RichPresence();
    private final Timer timer = new Timer();

    @Override
    public void onEnable() {
        // Was ignored entirely -- start() returns false when it can't find/open the local
        // Discord IPC pipe at all (Discord not running, or -- when launched via `gradlew
        // runClient` -- Discord's own "no activity from unknown/dev processes" setting
        // silently dropping it), and setActivity()'s c==null guard then makes every later call
        // a silent no-op. Route errors through the client logger (DiscordIPC's own default
        // handler is a raw System.err.println, invisible in .minecraft/logs/latest.log) so a
        // failure is actually diagnosable instead of just "nothing happens".
        DiscordIPC.setOnError((code, message) -> LOGGER.warn("[RPC] Discord IPC error {}: {}", code, message));

        boolean started = DiscordIPC.start(1474637830906052631L, () -> LOGGER.info("[RPC] Connected to Discord IPC"));
        if (!started) {
            LOGGER.warn("[RPC] DiscordIPC.start() returned false -- no local Discord IPC pipe found (is Discord running?)");
            return;
        }

        rpc.setStart(EUClient.UPTIME / 1000);

        // set initial text immediately
        rpc.setDetails(getDetails());

        DiscordIPC.setActivity(rpc);
    }

    @Override
    public void onDisable() {
        DiscordIPC.stop();
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {

        // update text occasionally if random mode
        if (detailsMode.getValue().equals("Random")) {
            if (timer.hasTimeElapsed(300000)) {
                rpc.setDetails(getDetails());
                DiscordIPC.setActivity(rpc);
                timer.reset();
            }
        } else {
            rpc.setDetails(customDetails.getValue());
            DiscordIPC.setActivity(rpc);
        }
    }

    private String getDetails() {
        return DETAILS[(int) MathUtils.random(DETAILS.length, 0)];
    }
}