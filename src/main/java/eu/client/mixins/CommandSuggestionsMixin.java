package eu.client.mixins;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.Suggestions;
import eu.client.EUClient;
import eu.client.commands.CommandManager;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;

// Reuses vanilla's OWN command-suggestion UI (the same rendering "/" commands get -- ghost-text
// completion, tab-cycling, the usage/argument tooltip box) for EUClient's own "." commands,
// instead of ChatScreenMixin's old from-scratch reimplementation (deleted). Same idea homovore's
// own MixinCommandSuggestions uses, adapted to 26.1.2's actual updateCommandInfo() body (verified
// via .mcref -- it's a materially different method here: vanilla's version now gates command
// parsing behind `commandsOnly || startsWithSlash` and reads a bunch of private state (currentParse,
// currentParseIsCommand/Message, commandUsage...) that homovore's simpler 1.21.4-era method didn't
// have, and updateUsageInfo() takes the parse+suggestions as PARAMETERS now, not zero-arg). Rather
// than fight that branching with a mid-method injection (fragile against exactly this kind of
// version drift -- a `@Local` sugar match already broke a DIFFERENT client's build this session for
// this exact reason, see the .fakeplayer/homovore session notes), this hooks the TAIL instead: let
// vanilla run its own logic completely unmodified (harmlessly treating "." text as a chat message,
// since it doesn't start with "/"), then unconditionally overwrite the two fields that actually
// drive rendering (currentParse, pendingSuggestions) with OUR OWN dispatcher's results whenever the
// raw text starts with EUClient's own prefix. No @Local, no dependency on the method's internal
// control flow at all -- just its two output fields and its own already-shadowed updateUsageInfo.
@Mixin(CommandSuggestions.class)
public abstract class CommandSuggestionsMixin {
    @Shadow private @Nullable ParseResults<ClientSuggestionProvider> currentParse;
    @Shadow private @Nullable CompletableFuture<Suggestions> pendingSuggestions;
    @Shadow private CommandSuggestions.@Nullable SuggestionsList suggestions;
    @Shadow private boolean keepSuggestions;
    @Shadow @Final private EditBox input;

    @Shadow
    private void updateUsageInfo(ParseResults<ClientSuggestionProvider> parseResults, Suggestions suggestionResult) {
    }

    @SuppressWarnings("unchecked")
    @Inject(method = "updateCommandInfo", at = @At("RETURN"))
    private void euclient$updateCommandInfo(CallbackInfo ci) {
        String raw = input.getValue();
        String prefix = EUClient.COMMAND_MANAGER.getPrefix();
        if (!raw.startsWith(prefix)) return;

        StringReader reader = new StringReader(raw);
        reader.setCursor(prefix.length());

        CommandDispatcher<CommandManager> dispatcher = EUClient.COMMAND_MANAGER.getDispatcher();
        ParseResults<CommandManager> parse = dispatcher.parse(reader, EUClient.COMMAND_MANAGER);
        // Brigadier's own suggestion/rendering machinery is generic over the dispatcher's "source"
        // type only where it actually matters for OUR OWN commands (which never touch
        // ClientSuggestionProvider); vanilla's CommandSuggestions field is typed
        // ParseResults<ClientSuggestionProvider> purely for ITS OWN commands, so this cast is safe
        // the same way homovore's identical cast is.
        currentParse = (ParseResults<ClientSuggestionProvider>) (Object) parse;

        if (suggestions == null || !keepSuggestions) {
            CompletableFuture<Suggestions> future = dispatcher.getCompletionSuggestions(parse, input.getCursorPosition());
            pendingSuggestions = future;
            future.thenRun(() -> {
                if (future.isDone()) {
                    updateUsageInfo(currentParse, future.join());
                }
            });
        }
    }
}
