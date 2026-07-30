package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.visuals.NoRenderModule;
import eu.client.utils.IMinecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntityRenderer.class)
public interface BlockEntityRenderDispatcherMixin extends IMinecraft {
    @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
    private void euclient$submit(BlockEntityRenderState state, PoseStack matrices, SubmitNodeCollector collector, CameraRenderState cameraState, CallbackInfo info) {
        NoRenderModule noRender = EUClient.MODULE_MANAGER.getModule(NoRenderModule.class);
        if (noRender.isToggled() && !noRender.tileEntities.getValue().equals("None")) {
            if (noRender.tileEntities.getValue().equals("Always") || (noRender.tileEntities.getValue().equals("Distance") && Math.sqrt(mc.player.distanceToSqr(state.blockPos.getX(), state.blockPos.getY(), state.blockPos.getZ())) > noRender.tileDistance.getValue().floatValue())) {
                info.cancel();
            }
        }
    }
}
