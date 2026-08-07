package eu.client.mixins;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import eu.client.EUClient;
import eu.client.events.impl.ChatInputEvent;
import eu.client.events.impl.CommandInputEvent;
import eu.client.modules.impl.core.HUDModule;
import eu.client.utils.graphics.Renderer2D;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.awt.*;
import java.util.List;

@Mixin(ChatScreen.class)
public class ChatScreenMixin extends Screen {
    @Shadow protected EditBox input;

    // Selection-cycle state shared by Tab and Up/Down. The candidate list and its base text are
    // captured ONCE when a cycle starts and reused on every subsequent press -- recomputing
    // suggestions from the just-completed text would filter the list down to that single exact
    // match (e.g. after ".fr" -> Tab -> ".freecam", asking CommandManager for suggestions on
    // ".freecam" only returns "freecam" itself, so "friends" could never be reached again). Reset
    // only when the user actually types something new (raw input differs from what our own last
    // completion produced). euclient$tabIndex is -1 while nothing is selected yet, so the first
    // Tab/Down press lands on index 0 instead of skipping it.
    private int euclient$tabIndex = -1;
    private String euclient$cycleBase = null;
    private List<String> euclient$cycleCandidates = List.of();
    private String euclient$lastCompleted = null;

    protected ChatScreenMixin(Component title) {
        super(title);
    }

    // Client-side command autocomplete: EUClient's own "." commands never go through vanilla's
    // Brigadier-backed CommandSuggestions (that's server round-trip / real "/" commands only), so
    // there was no visibility into what commands/sub-args exist. Draw our own hint list above the
    // input box, driven by CommandManager.getSuggestions. Command names (nothing typed after the
    // prefix yet) list vertically -- there can be dozens, one per module -- while sub-arg suggestions
    // (setting names, true/false, mode values...) are a short, fixed set that reads fine as one row.
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void euclient$renderCommandSuggestions(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTick, CallbackInfo info) {
        String raw = input.getValue();

        // Mid-cycle (this frame's text is exactly what our last Tab completion produced): keep
        // showing the FULL candidate list captured when the cycle started, with whichever entry
        // Tab last picked highlighted -- recomputing suggestions from the completed text (e.g.
        // ".freecam") would filter it down to that single exact match and "friend" would vanish
        // from the list, even though it's still one Tab press away.
        List<String> suggestions;
        int selected;
        if (raw.equals(euclient$lastCompleted) && !euclient$cycleCandidates.isEmpty()) {
            suggestions = euclient$cycleCandidates;
            selected = euclient$tabIndex;
        } else {
            suggestions = EUClient.COMMAND_MANAGER.getSuggestions(raw);
            selected = 0;
        }
        if (suggestions.isEmpty()) return;

        boolean topLevel = euclient$isTopLevel(raw);
        // Was measured up to the cursor -- moved per keystroke as the current argument grows
        // (".config load g" -> box under "g", then "gr", "gri", "grim", drifting right every
        // character). Real command suggestions are anchored to where the CURRENT ARGUMENT
        // STARTS (the component being completed), not to the cursor -- pin the box to the last
        // space before the cursor instead, so it stays put while that one word is typed.
        int cursor = input.getCursorPosition();
        int lastSpace = raw.lastIndexOf(' ', Math.max(0, cursor - 1));
        int tokenStart = lastSpace + 1;
        int x = input.getX() + 4 + EUClient.FONT_MANAGER.getWidth(raw.substring(0, tokenStart));
        int lineHeight = EUClient.FONT_MANAGER.getHeight();

        // Both lists render as a single vertical column now -- sub-arg suggestions (add/clear/
        // del/list, true/false, mode values...) used to render as one horizontal row, which reads
        // fine for 3-4 entries but was inconsistent with the top-level command list right above it.
        // Unselected entries are GRAY (not WHITE) so the selected one actually stands out.
        int maxShown = topLevel ? 10 : 8;
        int total = suggestions.size();
        int shown = Math.min(total, maxShown);

        // Scroll the window so the selected entry is always visible instead of always showing
        // indices 0..9 -- with 50+ modules registered, cycling past the 10th match (e.g. Tab-ing
        // all the way to "config") used to pick it as `selected` while the rendered list stayed
        // frozen on the alphabetically-first page, so the highlighted entry was never on screen.
        int start = total <= maxShown ? 0 : Math.max(0, Math.min(selected - maxShown / 2, total - maxShown));
        int end = start + shown;

        boolean overflow = total > shown;
        int rows = shown + (overflow ? 1 : 0);

        int width = 0;
        for (int i = start; i < end; i++) width = Math.max(width, EUClient.FONT_MANAGER.getWidth(suggestions.get(i)));
        if (overflow) width = Math.max(width, EUClient.FONT_MANAGER.getWidth("..."));

        int bottom = input.getY() - 4;
        int top = bottom - rows * lineHeight - 4;

        Renderer2D.renderQuad(context, x - 2, top, x + width + 4, bottom, new Color(0, 0, 0, 215));
        Renderer2D.renderOutline(context, x - 2, top, x + width + 4, bottom, new Color(0, 0, 0, 255));
        for (int i = start; i < end; i++) {
            int y = top + 2 + (i - start) * lineHeight;
            EUClient.FONT_MANAGER.drawTextWithShadow(context, suggestions.get(i), x, y, i == selected ? Color.CYAN : Color.GRAY);
        }
        if (overflow) {
            EUClient.FONT_MANAGER.drawTextWithShadow(context, "...", x, top + 2 + shown * lineHeight, Color.GRAY);
        }

        // Inline ghost-text completion (vanilla's own "/" command suggestions render the missing
        // suffix directly in the input line, in gray, right after the cursor -- e.g. typing
        // ".fakep" shows "layer" appended to complete "fakeplayer"). Only when the cursor sits at
        // the very end of the current token: mid-string edits have no single unambiguous "rest of
        // the word" to hint at without also affecting whatever comes after the cursor.
        if (cursor == raw.length()) {
            // For the FIRST token (the command name itself, tokenStart == 0), the raw substring
            // still carries the "." prefix -- but CommandManager.getSuggestions returns bare names
            // ("fakelag", not ".fakelag"), so comparing the prefixed token against them never
            // matched (silently no-op, no visible bug, just no hint ever showing for command names --
            // only for sub-arguments after a space, which don't carry the prefix).
            String prefix = EUClient.COMMAND_MANAGER.getPrefix();
            String currentToken = raw.substring(Math.max(tokenStart, prefix.length()));
            String topSuggestion = suggestions.get(Math.max(0, Math.min(selected, suggestions.size() - 1)));
            if (topSuggestion.length() > currentToken.length() && topSuggestion.regionMatches(true, 0, currentToken, 0, currentToken.length())) {
                String hint = topSuggestion.substring(currentToken.length());
                int hintX = input.getX() + 4 + EUClient.FONT_MANAGER.getWidth(raw);
                EUClient.FONT_MANAGER.drawTextWithShadow(context, hint, hintX, input.getY() + 2, Color.GRAY);
            }
        }
    }

    private boolean euclient$isTopLevel(String raw) {
        String prefix = EUClient.COMMAND_MANAGER.getPrefix();
        return raw.startsWith(prefix) && !raw.substring(prefix.length()).contains(" ");
    }

    // Tab / Down / Up move the selection through the current suggestion list and complete the token
    // being typed. Claimed at HEAD (cancellable) so this wins over CommandSuggestions/Screen's own
    // handling (focus-cycling for Tab, chat history recall for Up/Down) whenever we actually have
    // something to suggest -- if there are no suggestions, the key falls through to whatever vanilla
    // normally does with it (chat history navigation still works as usual when not autocompleting).
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void euclient$handleSuggestionKeys(KeyEvent event, CallbackInfoReturnable<Boolean> info) {
        int delta;
        if (event.key() == GLFW.GLFW_KEY_TAB || event.key() == GLFW.GLFW_KEY_DOWN) delta = 1;
        else if (event.key() == GLFW.GLFW_KEY_UP) delta = -1;
        else return;

        String raw = input.getValue();

        boolean continuingCycle = raw.equals(euclient$lastCompleted);
        if (!continuingCycle) {
            euclient$cycleBase = raw;
            euclient$cycleCandidates = EUClient.COMMAND_MANAGER.getSuggestions(raw);
            euclient$tabIndex = -1;
        }

        if (euclient$cycleCandidates.isEmpty()) return;

        int size = euclient$cycleCandidates.size();
        euclient$tabIndex = ((euclient$tabIndex + delta) % size + size) % size;
        String chosen = euclient$cycleCandidates.get(euclient$tabIndex);

        // No trailing space -- appending one would immediately flip ".fr" -> ".friend " out of
        // top-level mode (CommandManager.getSuggestions would then treat "friend" as the resolved
        // command and start suggesting ITS args), so a second press could never cycle to "freecam"
        // instead. Leaving the cursor right after the completed word keeps repeated presses cycling
        // through every match; the user presses space themselves to move on to args.
        String prefix = EUClient.COMMAND_MANAGER.getPrefix();
        String body = euclient$cycleBase.substring(prefix.length());
        int lastSpace = body.lastIndexOf(' ');
        String completed = prefix + (lastSpace < 0 ? chosen : body.substring(0, lastSpace + 1) + chosen);

        input.setValue(completed);
        euclient$lastCompleted = completed;

        info.setReturnValue(true);
        info.cancel();
    }

    @Inject(method = "handleChatInput", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;sendChat(Ljava/lang/String;)V"), cancellable = true)
    private void sendMessage(String chatText, boolean addToHistory, CallbackInfo info) {
        ChatInputEvent event = new ChatInputEvent(chatText);
        EUClient.EVENT_HANDLER.post(event);
        if (event.isCancelled()) info.cancel();
    }

    @Inject(method = "handleChatInput", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;sendCommand(Ljava/lang/String;)V"), cancellable = true)
    private void sendCommand(String chatText, boolean addToHistory, CallbackInfo info) {
        CommandInputEvent event = new CommandInputEvent(chatText);
        EUClient.EVENT_HANDLER.post(event);
        if (event.isCancelled()) info.cancel();
    }

    @WrapWithCondition(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"))
    private boolean render(GuiGraphicsExtractor instance, int x1, int y1, int x2, int y2, int color) {
        return !EUClient.MODULE_MANAGER.getModule(HUDModule.class).isToggled();
    }
}
