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

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZLnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;)V", shift = At.Shift.AFTER))
    private void renderWorld$swap(DeltaTracker tickCounter, CallbackInfo info, @Local(ordinal = 0) Matrix4fc modelViewMatrix, @Local PoseStack bobStack) {
        float tickDelta = tickCounter.getGameTimeDeltaPartialTick(false);

        RenderSystem.getModelViewStack().pushMatrix();

        RenderSystem.getModelViewStack().mul(modelViewMatrix);
        RenderSystem.getModelViewStack().mul(bobStack.last().pose().invert());

        EUClient.EVENT_HANDLER.post(new RenderWorldEvent(bobStack, tickDelta));

        Renderer3D.draw(Renderer3D.QUADS, Renderer3D.DEBUG_LINES, false);
        Renderer3D.draw(Renderer3D.SHINE_QUADS, Renderer3D.SHINE_DEBUG_LINES, true);

        EUClient.EVENT_HANDLER.post(new RenderWorldEvent.Post(bobStack, tickDelta));

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
