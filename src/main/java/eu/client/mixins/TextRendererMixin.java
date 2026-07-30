package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.core.FontModule;
import eu.client.utils.IMinecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.FormattedText;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Font.class)
public class TextRendererMixin {
    @Inject(method = "drawInBatch(Ljava/lang/String;FFIZLorg/joml/Matrix4fc;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V", at = @At("HEAD"), cancellable = true)
    private void drawInBatch$String(String text, float x, float y, int color, boolean shadow, Matrix4fc matrix, MultiBufferSource vertexConsumerProvider, Font.DisplayMode displayMode, int backgroundColor, int light, CallbackInfo info) {
        if (EUClient.MODULE_MANAGER.getModule(FontModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(FontModule.class).customFont.getValue() && EUClient.MODULE_MANAGER.getModule(FontModule.class).global.getValue()) {
            PoseStack matrices = new PoseStack();

            matrices.pushPose();
            matrices.mulPose(matrix);

            if (shadow) EUClient.FONT_MANAGER.getFontRenderer().drawString(matrices, text, x + EUClient.FONT_MANAGER.getShadowOffset(), y + EUClient.FONT_MANAGER.getShadowOffset(), color, true);
            EUClient.FONT_MANAGER.getFontRenderer().drawString(matrices, text, x, y, color, false);

            matrices.popPose();

            info.cancel();
        }
    }

    @Inject(method = "drawInBatch(Lnet/minecraft/util/FormattedCharSequence;FFIZLorg/joml/Matrix4fc;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V", at = @At("HEAD"), cancellable = true)
    private void drawInBatch$FormattedCharSequence(FormattedCharSequence text, float x, float y, int color, boolean shadow, Matrix4fc matrix, MultiBufferSource vertexConsumerProvider, Font.DisplayMode displayMode, int underlineColor, int light, CallbackInfo info) {
        if (EUClient.MODULE_MANAGER.getModule(FontModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(FontModule.class).customFont.getValue() && EUClient.MODULE_MANAGER.getModule(FontModule.class).global.getValue()) {
            PoseStack matrices = new PoseStack();

            matrices.pushPose();
            matrices.mulPose(matrix);

            if (shadow) EUClient.FONT_MANAGER.getFontRenderer().drawText(matrices, text, x + EUClient.FONT_MANAGER.getShadowOffset(), y + EUClient.FONT_MANAGER.getShadowOffset(), color, true);
            EUClient.FONT_MANAGER.getFontRenderer().drawText(matrices, text, x, y, color, false);

            matrices.popPose();

            info.cancel();
        }
    }

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
