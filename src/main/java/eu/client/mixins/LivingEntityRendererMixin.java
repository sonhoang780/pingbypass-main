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
// draw calls to hand-roll anymore). PopChamsModule/ShadersModule still ride EntityRenderState.
// outlineColor (see EntityRendererMixin) -- vanilla's own submit() already applies that field.
// ChamsModule instead captures real per-quad geometry at flush time now (ModelFeatureRendererMixin,
// EntityRenderStateMixin/IChamsCapture) -- the modern equivalent of the old ModelRenderer.java
// re-render trick, at the new architecture's actual vertex-level hook point.
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, S extends LivingEntityRenderState, M extends EntityModel<? super S>> extends EntityRenderer<T, S> implements IMinecraft {
    public LivingEntityRendererMixin(EntityRendererProvider.Context context) { super(context); }

    // ~1 player-width overlap radius -- see NoRenderModule.player's own doc.
    private static final double SAME_SPOT_RANGE_SQ = 1.0 * 1.0;

    @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
    private void submit(S state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera, CallbackInfo info) {
        NoRenderModule noRender = EUClient.MODULE_MANAGER.getModule(NoRenderModule.class);
        if (!noRender.isToggled()) return;

        if (noRender.corpses.getValue() && state.deathTime > 0) {
            info.cancel();
            return;
        }

        // AvatarRenderState (state instanceof check, no entity param needed here) is the player-
        // specific render state in this version -- see AvatarRenderer<AvatarlikeEntity>. Requested
        // (2026-08-12), then corrected: NOT a blanket "hide every other player" -- only when they're
        // standing packed on top of/right next to you (surrounds/1x1 fights), since THAT'S what
        // actually blocks seeing anything around you. A player rendering normally 10 blocks away is
        // fine and should stay visible. Horizontal-only (x/z) -- two players stacked at different Y
        // (one in a hole) still count as "same spot" for this purpose.
        if (noRender.player.getValue() && state instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState
                && !((eu.client.utils.mixins.ISelfState) state).euclient$isSelf() && mc.player != null) {
            double dx = state.x - mc.player.getX();
            double dz = state.z - mc.player.getZ();
            if (dx * dx + dz * dz <= SAME_SPOT_RANGE_SQ) {
                info.cancel();
            }
        }
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void euclient$extractRenderState(T entity, S state, float partialTicks, CallbackInfo info) {
        ((eu.client.utils.mixins.ISelfState) state).euclient$setSelf(entity == mc.player);

        if (entity == mc.player && EUClient.ROTATION_MANAGER.inRenderTime()) {
            float[] renderRotations = EUClient.ROTATION_MANAGER.getRenderRotations();
            state.bodyRot = renderRotations[0];
            state.yRot = net.minecraft.util.Mth.wrapDegrees(renderRotations[0] - state.bodyRot);
            state.xRot = renderRotations[1];
        }
    }
}
