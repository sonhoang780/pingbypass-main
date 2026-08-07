package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.core.FontModule;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// FontModule "Global" -- the real single choke point for ALL vanilla GUI text, verified via
// javap: GuiGraphicsExtractor.text(...) (every vanilla Screen widget/tooltip/options menu label)
// AND ActiveTextCollector's real implementation, GuiGraphicsExtractor$RenderingTextCollector
// (what ChatComponent's own textRenderer field actually is -- chat included) both hardcode
// Minecraft.font and end by calling GuiRenderState.addText(GuiTextRenderState) -- neither goes
// through Font.drawInBatch at all in this renderer generation, so TextRendererMixin's hook on
// that method never fires for any of this (only world-space/direct text uses drawInBatch).
// Cancel the vanilla text node here and resubmit the same glyphs through our own atlas instead.
@Mixin(GuiRenderState.class)
public class GuiRenderStateMixin {
    @Inject(method = "addText", at = @At("HEAD"), cancellable = true)
    private void euclient$globalFont(GuiTextRenderState state, CallbackInfo ci) {
        FontModule module = EUClient.MODULE_MANAGER.getModule(FontModule.class);
        if (!module.isToggled() || !module.customFont.getValue() || !module.global.getValue()) return;

        GuiRenderState self = (GuiRenderState) (Object) this;
        // state.dropShadow means "ALSO draw a shadow layer", not "darken this one draw" -- vanilla
        // renders it as TWO layers (an offset darkened copy, then the normal full-color text on
        // top). A single submitGlyphs(..., dropShadow=true) call darkens the WHOLE (only) layer
        // instead, which is why Global text came out uniformly gray instead of white.
        if (state.dropShadow) {
            float offset = EUClient.FONT_MANAGER.getShadowOffset();
            EUClient.FONT_MANAGER.getFontRenderer().submitGlyphs(
                    self, state.pose, state.scissor, state.text,
                    state.x + offset, state.y + offset, state.color, true);
        }
        EUClient.FONT_MANAGER.getFontRenderer().submitGlyphs(
                self, state.pose, state.scissor, state.text,
                state.x, state.y, state.color, false);
        ci.cancel();
    }
}
