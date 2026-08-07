package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.visuals.NoRenderModule;
import eu.client.utils.IMinecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntityRenderDispatcher.class)
public class BlockEntityRenderDispatcherMixin implements IMinecraft {
    @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
    private void euclient$submit(BlockEntityRenderState state, PoseStack matrices, SubmitNodeCollector collector, CameraRenderState cameraState, CallbackInfo info) {
        NoRenderModule noRender = EUClient.MODULE_MANAGER.getModule(NoRenderModule.class);
        // Was checking != "None" -- the mode's actual values are Never/Distance/Always, so that
        // check was always true and did nothing. Also inverted: Distance mode is meant to hide
        // tile entities WITHIN the radius of the player (a "TileDistance 10" = don't render tile
        // entities inside 10 blocks of me), not cull ones beyond it -- was doing the opposite.
        if (noRender.isToggled() && !noRender.tileEntities.getValue().equals("Never")) {
            if (noRender.tileEntities.getValue().equals("Always") || (noRender.tileEntities.getValue().equals("Distance") && Math.sqrt(mc.player.distanceToSqr(state.blockPos.getX(), state.blockPos.getY(), state.blockPos.getZ())) < noRender.tileDistance.getValue().floatValue())) {
                info.cancel();
            }
        }
    }
}
