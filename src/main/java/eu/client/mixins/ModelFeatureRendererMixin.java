package eu.client.mixins;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import eu.client.utils.graphics.ChamsVertexConsumer;
import eu.client.utils.mixins.IChamsCapture;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// ChamsModule's real per-quad capture point -- verified via real 26.2 source
// (net/minecraft/client/renderer/feature/ModelFeatureRenderer.java, read in full): the old
// SubmitNodeStorage$ModelSubmit + separate renderModel(...) hook is GONE, the whole per-entity
// model-render path collapsed into ModelFeatureRenderer itself, a RenderTypeFeatureRenderer<Submit<?>>
// (the FeatureRenderer/SubmitNode batching system also covering Text/NameTag/etc, see the 26.2 port
// audit doc). `prepareModel(Submit<S> submit)` (private, called from buildGroup per queued submit)
// is the new call site for Model.renderToBuffer -- same generic idea (intercept every quad the
// model draws via a wrapped VertexConsumer), same target method (Model.renderToBuffer's own
// signature is unchanged), just a different enclosing method/param shape. `submit` is now a real
// declared parameter of the enclosing method (not a MixinExtras @Local-captured local variable like
// the old renderModel's was), so Sponge Mixin's plain trailing-parameter convention picks it up
// without needing the @Local sugar anymore.
@Mixin(ModelFeatureRenderer.class)
public class ModelFeatureRendererMixin {
    @Redirect(
            method = "prepareModel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V")
    )
    private <S> void euclient$captureChams(Model<S> model, PoseStack pose, VertexConsumer consumer, int light, int overlay, int tint,
                                            ModelFeatureRenderer.Submit<S> submit) {
        if (submit.state() instanceof IChamsCapture capture && (capture.euclient$chamsFill() || capture.euclient$chamsOutline())) {
            model.renderToBuffer(pose, new ChamsVertexConsumer(consumer, capture.euclient$chamsFill(), capture.euclient$chamsFillColor(),
                    capture.euclient$chamsOutline(), capture.euclient$chamsOutlineColor(), capture.euclient$chamsShine(), capture.euclient$chamsSuppressReal()), light, overlay, tint);
        } else {
            model.renderToBuffer(pose, consumer, light, overlay, tint);
        }
    }
}
