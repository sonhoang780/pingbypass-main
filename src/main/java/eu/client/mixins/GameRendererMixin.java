package eu.client.mixins;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import eu.client.EUClient;
import eu.client.events.impl.RenderWorldEvent;
import eu.client.modules.impl.miscellaneous.FOVModifierModule;
import eu.client.modules.impl.visuals.AspectRatioModule;
import eu.client.modules.impl.visuals.NoRenderModule;
import eu.client.utils.graphics.Renderer2D;
import eu.client.utils.graphics.Renderer3D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4fc;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow @Final private Minecraft client;

    @Shadow private float zoom;

    @Shadow private float zoomX;

    @Shadow private float zoomY;

    @Shadow private float viewDistance;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void renderWorld$HEAD(DeltaTracker tickCounter, CallbackInfo info) {
        Renderer3D.prepare();
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZLnet/minecraft/client/renderer/state/level/ChunkSectionsToRender;)V", shift = At.Shift.AFTER))
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

    @Inject(method = "tiltViewWhenHurt", at = @At("HEAD"), cancellable = true)
    private void tiltViewWhenHurt(CallbackInfo info) {
        if (EUClient.MODULE_MANAGER.getModule(NoRenderModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(NoRenderModule.class).hurtCamera.getValue()) {
            info.cancel();
        }
    }

    @Inject(method = "showFloatingItem", at = @At("HEAD"), cancellable = true)
    private void showFloatingItem(ItemStack floatingItem, CallbackInfo info) {
        if (EUClient.MODULE_MANAGER.getModule(NoRenderModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(NoRenderModule.class).totemAnimation.getValue()) {
            info.cancel();
        }
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZLnet/minecraft/client/renderer/state/level/ChunkSectionsToRender;)V", shift = At.Shift.AFTER))
    private void renderWorld(DeltaTracker tickCounter, CallbackInfo info, @Local(ordinal = 0) Matrix4fc modelViewMatrix, @Local(ordinal = 0) Matrix4f projectionMatrix) {
        PoseStack matrix = new PoseStack();
        matrix.last().pose().mul(modelViewMatrix);

        Renderer2D.LAST_PROJECTION_MATRIX.set(projectionMatrix);
        Renderer2D.LAST_MODEL_MATRIX.set(RenderSystem.getModelViewMatrix());
        Renderer2D.LAST_WORLD_MATRIX.set(matrix.last().pose());
    }

    @Inject(method = "getFov", at = @At("TAIL"), cancellable = true)
    private void getFOV(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Float> info) {
        FOVModifierModule module = EUClient.MODULE_MANAGER.getModule(FOVModifierModule.class);
        if(module.isToggled()) {
            if(info.getReturnValue() == 70 && !module.items.getValue()) return;
            info.setReturnValue(module.fov.getValue().floatValue());
        }
    }

    @Inject(method = "getBasicProjectionMatrix",at = @At("TAIL"), cancellable = true)
    public void getBasicProjectionMatrix(float fovDegrees, CallbackInfoReturnable<Matrix4f> info) {
        if (EUClient.MODULE_MANAGER.getModule(AspectRatioModule.class).isToggled()) {
            PoseStack matrixStack = new PoseStack();
            matrixStack.last().pose().identity();
            if (zoom != 1.0f) {
                matrixStack.translate(zoomX, -zoomY, 0.0f);
                matrixStack.scale(zoom, zoom, 1.0f);
            }

            matrixStack.last().pose().mul(new Matrix4f().setPerspective((float)(fovDegrees * 0.01745329238474369), EUClient.MODULE_MANAGER.getModule(AspectRatioModule.class).ratio.getValue().floatValue(), 0.05f, viewDistance * 4.0f));
            info.setReturnValue(matrixStack.last().pose());
        }
    }
}
