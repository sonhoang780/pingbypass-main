package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.visuals.AspectRatioModule;
import net.minecraft.client.renderer.Projection;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

// GameRenderer.getBasicProjectionMatrix/zoom fields were removed; perspective aspect ratio
// is now computed inside Projection.getMatrix() via Matrix4f.setPerspective(fov, aspect, near, far, zZeroToOne)
@Mixin(Projection.class)
public class ProjectionMixin {
    @Shadow private boolean isMatrixDirty;

    // getMatrix() caches the built matrix behind isMatrixDirty and short-circuits (never reaching
    // setPerspective(), where the aspect-ratio override below hooks in) whenever FOV/size haven't
    // changed since the last call -- e.g. while standing still. That's why toggling this module only
    // took effect once something else (sprint FOV, resize) marked the matrix dirty on its own.
    //
    // Gating this on isToggled() (only force-dirty while the module is ON) fixed toggling it ON while
    // idle, but broke the opposite direction: turning it OFF while idle left the cache stuck holding
    // the overridden aspect ratio forever, since nothing forced one more recompute to restore the
    // real one. Force a dirty matrix unconditionally, every call -- recomputing a 4x4 perspective
    // matrix is cheap, and this way both directions self-correct on the very next frame regardless
    // of module state.
    @Inject(method = "getMatrix", at = @At("HEAD"))
    private void getMatrix$forceDirty(Matrix4f dest, CallbackInfoReturnable<Matrix4f> info) {
        isMatrixDirty = true;
    }

    @ModifyArgs(method = "getMatrix", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;setPerspective(FFFFZ)Lorg/joml/Matrix4f;"))
    private void getMatrix$aspectRatio(Args args) {
        // PORT (26.2): real crash caught via runtime test (gradlew.bat runClient) -- GuiRenderer's
        // new retained-mode pipeline calls CubeMap.render() -> Projection.getMatrix() for the main
        // menu panorama background BEFORE EUClient.MODULE_MANAGER finishes initializing (title
        // screen renders during the client's own boot sequence, earlier than this mixin's callers
        // ever fired pre-26.2). Guard against MODULE_MANAGER/the module itself being null.
        if (EUClient.MODULE_MANAGER == null) return;
        AspectRatioModule module = EUClient.MODULE_MANAGER.getModule(AspectRatioModule.class);
        if (module != null && module.isToggled()) {
            args.set(1, module.ratio.getValue().floatValue());
        }
    }
}
