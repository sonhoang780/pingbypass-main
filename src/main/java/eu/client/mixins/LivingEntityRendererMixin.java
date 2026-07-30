package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.visuals.NoRenderModule;
import eu.client.utils.IMinecraft;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// ponytail: the old @Overwrite of updateRenderState / the custom euclient$render re-implementation
// of LivingEntityRenderer.render(...) are both gone. render() itself was renamed submit(...) and
// rewritten around SubmitNodeCollector.submitModel(...) (high-level, no manual VertexConsumer/model
// draw calls to hand-roll anymore); euclient$render existed ONLY to be called from the now-deleted
// ModelRenderer.java, which is unused since ChamsModule/PopChamsModule/ShadersModule moved onto
// EntityRenderState.outlineColor (see EntityRendererMixin) -- vanilla's own submit() already applies
// that field, so there's nothing left for a custom render path to do.
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, S extends LivingEntityRenderState, M extends EntityModel<? super S>> extends EntityRenderer<T, S> implements IMinecraft {
    public LivingEntityRendererMixin(EntityRendererProvider.Context context) { super(context); }

    @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
    private void submit(S state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera, CallbackInfo info) {
        if (EUClient.MODULE_MANAGER.getModule(NoRenderModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(NoRenderModule.class).corpses.getValue() && state.deathTime > 0) {
            info.cancel();
        }
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void euclient$extractRenderState(T entity, S state, float partialTicks, CallbackInfo info) {
        if (entity == mc.player && EUClient.ROTATION_MANAGER.inRenderTime()) {
            float[] renderRotations = EUClient.ROTATION_MANAGER.getRenderRotations();
            state.bodyRot = renderRotations[0];
            state.yRot = net.minecraft.util.Mth.wrapDegrees(renderRotations[0] - state.bodyRot);
            state.xRot = renderRotations[1];
        }
    }
}
