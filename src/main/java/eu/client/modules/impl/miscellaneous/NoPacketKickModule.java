package eu.client.modules.impl.miscellaneous;

import eu.client.EUClient;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RegisterModule(name = "NoPacketKick", description = "Prevents you from being kicked from the server due to Netty, packet decoding, or codec exceptions.", category = Module.Category.MISCELLANEOUS)
public class NoPacketKickModule extends Module {
    private static final Logger LOGGER = LoggerFactory.getLogger("EUClient/NoPacketKick");

    public BooleanSetting logChat = new BooleanSetting("LogChat", "Notifies you in chat when a corrupted or invalid packet is caught and suppressed.", true);
    public BooleanSetting logConsole = new BooleanSetting("LogConsole", "Prints the full exception stacktrace to the console log.", true);
    public BooleanSetting onlyDecoder = new BooleanSetting("OnlyDecoder", "Only suppresses decoder and codec errors, allowing critical socket disconnects through.", false);

    private long lastChatNotification = 0L;

    public boolean shouldSuppress(Throwable throwable) {
        if (!onlyDecoder.getValue()) {
            // Suppress all network pipeline exceptions (TimeoutException, DecoderException, IllegalArgumentException, etc.)
            return true;
        }
        return throwable instanceof DecoderException
                || throwable instanceof EncoderException
                || throwable instanceof IllegalArgumentException
                || throwable instanceof IndexOutOfBoundsException
                || throwable instanceof NullPointerException;
    }

    public void onExceptionCaught(Throwable throwable) {
        if (logConsole.getValue()) {
            LOGGER.warn("[NoPacketKick] Suppressed Netty packet exception to prevent disconnect: {}", throwable.getMessage(), throwable);
        }

        if (logChat.getValue() && mc.player != null && mc.level != null) {
            long now = System.currentTimeMillis();
            // Throttle chat notifications to at most once every 500ms to prevent chat spam if packet bursts fail
            if (now - lastChatNotification > 500L) {
                lastChatNotification = now;
                String msg = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                if (msg == null || msg.isBlank()) msg = throwable.getClass().getSimpleName();
                if (msg.length() > 100) msg = msg.substring(0, 100) + "...";
                EUClient.CHAT_MANAGER.tagged("Suppressed packet error: " + msg, getName());
            }
        }
    }
}
