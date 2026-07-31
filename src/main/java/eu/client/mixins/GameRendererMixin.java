package eu.client.mixins;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import eu.client.EUClient;
import eu.client.events.impl.RenderWorldEvent;
import eu.client.modules.impl.visuals.NoRenderModule;
import eu.client.utils.graphics.Renderer2D;
import eu.client.utils.graphics.Renderer3D;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4fc;
import org.joml.Matrix4f;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void renderWorld$HEAD(DeltaTracker tickCounter, CallbackInfo info) {
        Renderer3D.prepare();
    }

    // PORT: in 26.1.2 view-bob (bobHurt/bobView) is composed into the PROJECTION matrix
    // (GameRenderer.renderLevel: `projectionMatrix.mul(bobStack.last().pose())`), not the
    // model-view matrix -- unlike the pre-port pipeline where bob lived in model-view and had
    // to be inverted-out here for world-space rendering to stay bob-free. `modelViewMatrix`
    // (cameraState.viewRotationMatrix) never contains bob now, so multiplying by an inverted
    // bob stack here no longer cancels anything -- it *injects* an uncancelled bob transform,
    // which is exactly why BlockHighlight/AutoCrystal/etc. boxes visibly bobbed while walking.
    // Modules get a fresh, bob-free PoseStack instead of the old (bobbed) local capture.
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZLnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;)V", shift = At.Shift.AFTER))
    private void renderWorld$swap(DeltaTracker tickCounter, CallbackInfo info, @Local(ordinal = 0) Matrix4fc modelViewMatrix) {
        float tickDelta = tickCounter.getGameTimeDeltaPartialTick(false);

        RenderSystem.getModelViewStack().pushMatrix();

        RenderSystem.getModelViewStack().mul(modelViewMatrix);

        PoseStack noBobStack = new PoseStack();

        EUClient.EVENT_HANDLER.post(new RenderWorldEvent(noBobStack, tickDelta));

        Renderer3D.draw(Renderer3D.QUADS, Renderer3D.DEBUG_LINES, false);
        Renderer3D.draw(Renderer3D.SHINE_QUADS, Renderer3D.SHINE_DEBUG_LINES, true);

        EUClient.EVENT_HANDLER.post(new RenderWorldEvent.Post(noBobStack, tickDelta));

        // Post-phase callers (e.g. NameTagsModule's border, which needs the text width computed
        // earlier in the same handler) add to Renderer3D.QUADS/DEBUG_LINES here too, but the only
        // draw() call was above, before this event fired -- so that geometry sat unused until
        // prepare() wiped it at the start of the next frame and never actually rendered. Flush again.
        Renderer3D.draw(Renderer3D.QUADS, Renderer3D.DEBUG_LINES, false);
        Renderer3D.draw(Renderer3D.SHINE_QUADS, Renderer3D.SHINE_DEBUG_LINES, true);

        // Modules that draw world-space text via the vanilla font (mc.font.drawInBatch) queue
        // into the shared MultiBufferSource instead of drawing immediately -- unlike our own
        // immediate-mode RenderType draws above, that queued geometry doesn't actually hit the
        // GPU until something calls endBatch(). Nothing does until the HUD/hand pass reassigns
        // the projection matrix, so by the time it flushes the 3D world projection is gone and
        // the text renders wildly mispositioned/skewed (worse than the CustomFont path, which
        // draws immediately here and is therefore unaffected). Force the flush now, while the
        // correct 3D projection from this renderLevel call is still bound.
        net.minecraft.client.Minecraft.getInstance().renderBuffers().bufferSource().endBatch();

        RenderSystem.getModelViewStack().popMatrix();
    }

    // tiltViewWhenHurt renamed to bobHurt(CameraRenderState, PoseStack)
    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void tiltViewWhenHurt(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo info) {
        if (EUClient.MODULE_MANAGER.getModule(NoRenderModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(NoRenderModule.class).hurtCamera.getValue()) {
            info.cancel();
        }
    }

    // showFloatingItem renamed to displayItemActivation(ItemStack) — same totem-pop-animation entry point
    @Inject(method = "displayItemActivation", at = @At("HEAD"), cancellable = true)
    private void showFloatingItem(ItemStack floatingItem, CallbackInfo info) {
        if (EUClient.MODULE_MANAGER.getModule(NoRenderModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(NoRenderModule.class).totemAnimation.getValue()) {
            info.cancel();
        }
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZLnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;)V", shift = At.Shift.AFTER))
    private void renderWorld(DeltaTracker tickCounter, CallbackInfo info, @Local(ordinal = 0) Matrix4fc modelViewMatrix, @Local(ordinal = 0) Matrix4f projectionMatrix) {
        PoseStack matrix = new PoseStack();
        matrix.last().pose().mul(modelViewMatrix);

        Renderer2D.LAST_PROJECTION_MATRIX.set(projectionMatrix);
        Renderer2D.LAST_MODEL_MATRIX.set(RenderSystem.getModelViewMatrix());
        Renderer2D.LAST_WORLD_MATRIX.set(matrix.last().pose());
    }
}
