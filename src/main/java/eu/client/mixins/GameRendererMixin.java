package eu.client.mixins;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import eu.client.EUClient;
import eu.client.events.impl.RenderWorldEvent;
import eu.client.mixins.accessors.LevelRendererAccessor;
import eu.client.modules.impl.visuals.NoRenderModule;
import eu.client.utils.graphics.Renderer2D;
import eu.client.utils.graphics.EspShader;
import eu.client.utils.graphics.Renderer3D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.Identifier;
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

    // Runs our own outline post-chain manually, once, right before vanilla's own
    // doEntityOutline() blends whatever's in entityOutlineTarget onto the screen. By this point
    // both the world silhouette (captured during renderLevel, left unprocessed --
    // WorldRendererMixin's redirect blocked vanilla's own in-FrameGraph run of this same chain)
    // AND the hand silhouette (flushed by ItemInHandRendererMixin.euclient$flushHandOutline,
    // which runs between renderLevel and here) are sitting in the same target, so one pass
    // resolves both together -- same reasoning WorldRendererMixin's comment covers for why it
    // must NOT also run there (would double-process the world part).
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;doEntityOutline()V"))
    private void euclient$resolveOutline(DeltaTracker tracker, boolean tick, CallbackInfo ci) {
        Identifier variant = eu.client.modules.impl.visuals.ShadersModule.pickActiveOutlineChain();
        if (variant == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer == null) return;
        RenderTarget outline = ((LevelRendererAccessor) mc.levelRenderer).euclient$getEntityOutlineTarget();
        if (outline == null) return;

        // Post-chain JSON references "minecraft:main" (not "minecraft:entity_outline") as its
        // in/out target id -- PostChain.process(target, alloc) binds ONLY MAIN_TARGET_ID to the
        // given RenderTarget, and addToFrame throws if the chain references any target id not in
        // that single-entry bundle. "minecraft:entity_outline" would throw here; the label is
        // arbitrary, only the id needs to match what process() actually binds.
        PostChain chain = mc.getShaderManager().getPostChain(variant, LevelTargetBundle.MAIN_TARGETS);
        if (chain == null) return;

        // The one per-frame write the animated shader modes need: the post-chain's own uniform
        // values are baked at JSON load, so an animated Time has to be pushed into the pass's
        // GpuBuffer directly, right before the chain runs. No-op-equivalent (Effect 0) when the
        // Shader mode is None, which keeps the flat-color path byte-for-byte as it was.
        EspShader.writeOutlineSettings(chain, eu.client.modules.impl.visuals.ShadersModule.shaderSettings());
        chain.process(outline, GraphicsResourceAllocator.UNPOOLED);
    }

    // StarGlow -- SkyRendererMixin redirects the ACTUAL star draw call (not a whole-screen
    // threshold guess) into StarCapture's isolated target this frame. Blur it in place (star_glow
    // chain, own identifier/buffer, not shared with anything), then composite it onto the real
    // screen via RenderTarget.blitAndBlendToTexture -- the SAME alpha-blend pipeline
    // (ENTITY_OUTLINE_BLIT) vanilla's own doEntityOutline() uses, verified via .mcref. No
    // brightness/chroma approximation anywhere in this path.
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;doEntityOutline()V"))
    private void euclient$resolveStarGlow(DeltaTracker tracker, boolean tick, CallbackInfo ci) {
        eu.client.modules.impl.visuals.AtmosphereModule atmosphere = EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.visuals.AtmosphereModule.class);
        if (!atmosphere.isToggled() || !atmosphere.starGlow.getValue()) return;

        RenderTarget starTarget = eu.client.utils.graphics.StarCapture.get();
        if (starTarget == null) return;

        Minecraft mc = Minecraft.getInstance();
        PostChain chain = mc.getShaderManager().getPostChain(Identifier.fromNamespaceAndPath("euclient", "star_glow"), LevelTargetBundle.MAIN_TARGETS);
        if (chain == null) return;

        EspShader.writeStarGlowSettings(chain, atmosphere.starGlowIntensity.getValue().floatValue());
        chain.process(starTarget, GraphicsResourceAllocator.UNPOOLED);
        starTarget.blitAndBlendToTexture(mc.getMainRenderTarget().getColorTextureView());
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
        EspShader.draw(Renderer3D.SHADER_QUADS, Renderer3D.SHADER_DEBUG_LINES);

        EUClient.EVENT_HANDLER.post(new RenderWorldEvent.Post(noBobStack, tickDelta));

        // Post-phase callers (e.g. NameTagsModule's border, which needs the text width computed
        // earlier in the same handler) add to Renderer3D.QUADS/DEBUG_LINES here too, but the only
        // draw() call was above, before this event fired -- so that geometry sat unused until
        // prepare() wiped it at the start of the next frame and never actually rendered. Flush again.
        Renderer3D.draw(Renderer3D.QUADS, Renderer3D.DEBUG_LINES, false);
        Renderer3D.draw(Renderer3D.SHINE_QUADS, Renderer3D.SHINE_DEBUG_LINES, true);
        EspShader.draw(Renderer3D.SHADER_QUADS, Renderer3D.SHADER_DEBUG_LINES);

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
