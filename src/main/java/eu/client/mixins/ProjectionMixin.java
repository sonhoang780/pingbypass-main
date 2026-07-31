package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.visuals.AspectRatioModule;
import net.minecraft.client.renderer.Projection;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

// GameRenderer.getBasicProjectionMatrix/zoom fields were removed; perspective aspect ratio
// is now computed inside Projection.getMatrix() via Matrix4f.setPerspective(fov, aspect, near, far, zZeroToOne)
@Mixin(Projection.class)
public class ProjectionMixin {
    @ModifyArgs(method = "getMatrix", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;setPerspective(FFFFZ)Lorg/joml/Matrix4f;"))
    private void getMatrix$aspectRatio(Args args) {
        if (EUClient.MODULE_MANAGER.getModule(AspectRatioModule.class).isToggled()) {
            args.set(1, EUClient.MODULE_MANAGER.getModule(AspectRatioModule.class).ratio.getValue().floatValue());
        }
    }
}
