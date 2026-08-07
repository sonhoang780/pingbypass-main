package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.core.ClickGuiModule;
import eu.client.utils.graphics.NeekeriFill;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Same animated pattern + FillMode/Speed/Opacity as ClickGui's own row fill (see NeekeriFill /
// ModuleButton) -- one shared source, drawn AFTER vanilla's own button sprite+text so it isn't
// covered by it, for every vanilla button on every screen (pause menu, options, ...).
//
// Verified via .mcref (decompiled 26.1.2 source) after two wrong guesses on Button.class -- the
// GUI rewrite splits widget rendering into extractRenderState (final, AbstractWidget) ->
// extractWidgetRenderState (final, AbstractButton) -> extractContents (abstract, Button's actual
// per-instance override) -> extractDefaultSprite (final, AbstractButton), the last of which is
// what actually calls graphics.blitSprite(...) for the button's grey background texture. Neither
// "renderWidget" nor Button.class itself exist in this chain at all -- extractDefaultSprite is
// declared in AbstractButton, not Button, which is why both earlier attempts silently no-op'd
// (require=0 caught it, no crash, just never applied).
@Mixin(AbstractButton.class)
public abstract class ButtonWidgetMixin {
    @Inject(method = "extractDefaultSprite", at = @At("TAIL"))
    private void euclient$neekeriFill(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        ClickGuiModule clickGui = EUClient.MODULE_MANAGER.getModule(ClickGuiModule.class);
        if (clickGui.fillMode.getValue().equalsIgnoreCase("Default")) return;

        AbstractButton self = (AbstractButton) (Object) this;
        int alpha = Math.round(clickGui.neekeriOpacity.getValue().floatValue() / 100.0f * 255.0f);
        NeekeriFill.fill(graphics, self.getX(), self.getY(), self.getWidth(), self.getHeight(), alpha);
    }
}
