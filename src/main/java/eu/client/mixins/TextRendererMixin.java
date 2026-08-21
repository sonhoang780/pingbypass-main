package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.core.FontModule;
import eu.client.utils.IMinecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.FormattedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// PORT (26.2): FontModule "Global" (custom font overriding EVERY vanilla text draw game-wide) --
// DISABLED, not ported, pending real design. Font.drawInBatch (this mixin's old cancel-and-
// substitute target) is GONE entirely in 26.2, not renamed: replaced by Font.prepareText(...) ->
// PreparedText.visit(GlyphVisitor), which critically has NO transform-matrix parameter at
// prepareText() time -- prepareText only takes local (x,y) floats, the real PoseStack/pose gets
// applied LATER by whichever caller eventually calls PreparedText.visit (e.g.
// TextFeatureRenderer.buildGroup passing submit.pose(), for world-space text). Cancelling at
// prepareText() and drawing immediately with our own FontRenderer under a fresh identity-ish
// PoseStack (the only pose available at that point) would silently drop whatever real transform
// was coming later -- correct for simple GUI text, WRONG for anything drawn with a real transform
// (world nametags, rotated/scaled UI elements), and "wrong but compiles clean" is exactly the
// failure mode this project's port work explicitly guards against (see feedback_never_trust_euclient
// memory). Needs its own real design pass (found: which callers apply which pose, whether a safe
// choke point exists) before re-enabling -- not a same-session mechanical fix. `width(...)` below
// is unaffected (Font.width's own signature is unchanged in 26.2, confirmed against real source)
// and still works.
@Mixin(Font.class)
public class TextRendererMixin {
    @Inject(method = "width(Ljava/lang/String;)I", at = @At("HEAD"), cancellable = true)
    private void width(String text, CallbackInfoReturnable<Integer> info) {
        if (EUClient.MODULE_MANAGER.getModule(FontModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(FontModule.class).customFont.getValue() && EUClient.MODULE_MANAGER.getModule(FontModule.class).global.getValue()) {
            info.setReturnValue(EUClient.FONT_MANAGER.getWidth(text));
        }
    }

    @Inject(method = "width(Lnet/minecraft/network/chat/FormattedText;)I", at = @At("HEAD"), cancellable = true)
    private void width(FormattedText text, CallbackInfoReturnable<Integer> info) {
        if (EUClient.MODULE_MANAGER.getModule(FontModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(FontModule.class).customFont.getValue() && EUClient.MODULE_MANAGER.getModule(FontModule.class).global.getValue()) {
            info.setReturnValue(EUClient.FONT_MANAGER.getWidth(text.getString()));
        }
    }

    @Inject(method = "width(Lnet/minecraft/util/FormattedCharSequence;)I", at = @At("HEAD"), cancellable = true)
    private void width(FormattedCharSequence text, CallbackInfoReturnable<Integer> info) {
        if (EUClient.MODULE_MANAGER.getModule(FontModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(FontModule.class).customFont.getValue() && EUClient.MODULE_MANAGER.getModule(FontModule.class).global.getValue()) {
            info.setReturnValue((int) EUClient.FONT_MANAGER.getFontRenderer().getTextWidth(text));
        }
    }
}
