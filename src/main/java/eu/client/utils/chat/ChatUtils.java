package eu.client.utils.chat;

import eu.client.EUClient;
import eu.client.modules.impl.core.CommandsModule;
import eu.client.utils.text.FormattingUtils;

// PORT (26.2): return type follows FormattingUtils.getFormatting's own -- ChatFormatting no longer
// implements StringRepresentable (see FormattingUtils' PORT comment). Every caller of these two
// methods (grepped repo-wide) only ever concatenates the result into a String, so Object is a safe
// common return type here too.
public class ChatUtils {
    public static Object getPrimary() {
        return FormattingUtils.getFormatting(EUClient.MODULE_MANAGER.getModule(CommandsModule.class).primaryMessageColor.getValue());
    }

    public static Object getSecondary() {
        return FormattingUtils.getFormatting(EUClient.MODULE_MANAGER.getModule(CommandsModule.class).secondaryMessageColor.getValue());
    }
}
