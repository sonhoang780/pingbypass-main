package eu.client.mixins;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import eu.client.utils.graphics.ChamsVertexConsumer;
import eu.client.utils.mixins.IChamsCapture;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// ChamsModule's real per-quad capture point -- verified via javap that ModelFeatureRenderer's
// private renderModel(ModelSubmit, RenderType, VertexConsumer, ...) is where Model.renderToBuffer
// actually gets called with a real VertexConsumer for ANY submitModel(...) entity (players, mobs,
// crystals -- all of them, since they all funnel through the same generic <S> ModelSubmit<S>
// storage). This is the modern equivalent of the pre-port ModelRenderer's fake
// VertexConsumerProvider swap: same idea (intercept every quad the model draws), different hook
// point because entity geometry submission is deferred now instead of drawn immediately.
@Mixin(ModelFeatureRenderer.class)
public class ModelFeatureRendererMixin {
    @Redirect(
            method = "renderModel(Lnet/minecraft/client/renderer/SubmitNodeStorage$ModelSubmit;Lnet/minecraft/client/renderer/rendertype/RenderType;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/renderer/OutlineBufferSource;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V")
    )
    private <S> void euclient$captureChams(Model<S> model, PoseStack pose, VertexConsumer consumer, int light, int overlay, int tint,
                                            @Local(ordinal = 0) SubmitNodeStorage.ModelSubmit<S> submit) {
        if (submit.state() instanceof IChamsCapture capture && (capture.euclient$chamsFill() || capture.euclient$chamsOutline())) {
            model.renderToBuffer(pose, new ChamsVertexConsumer(consumer, capture.euclient$chamsFill(), capture.euclient$chamsFillColor(),
                    capture.euclient$chamsOutline(), capture.euclient$chamsOutlineColor(), capture.euclient$chamsShine()), light, overlay, tint);
        } else {
            model.renderToBuffer(pose, consumer, light, overlay, tint);
        }
    }
}
