package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.visuals.BlockHighlightModule;
import eu.client.modules.impl.visuals.FreecamModule;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// ponytail: renderEntity's old per-entity VertexConsumer-swap WrapOperation (RenderEntityEvent)
// is dropped -- same as the old ModelRenderer chams technique it served, entity geometry is
// submitted as high-level submitModel(...) descriptors now, not per-vertex VertexConsumer draws,
// so there's no per-entity buffer to swap anymore (see ChamsModule's EntityRenderState.outlineColor
// rewrite). hasBlindnessOrDarkness's fog-bypass moved into the FogEnvironment system together with
// BackgroundRendererMixin's still-deferred fog rearchitecture.
@Mixin(LevelRenderer.class)
public abstract class WorldRendererMixin {
    @ModifyVariable(method = "renderLevel", at = @At("HEAD"), argsOnly = true)
    private boolean renderOutline(boolean renderOutline) {
        if (EUClient.MODULE_MANAGER != null && EUClient.MODULE_MANAGER.getModule(BlockHighlightModule.class).isToggled()) {
            return false;
        }

        return renderOutline;
    }

    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;cullTerrain(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Z)V"), index = 2)
    private boolean cullTerrain$isSpectator(boolean spectator) {
        return EUClient.MODULE_MANAGER.getModule(FreecamModule.class).isToggled() || spectator;
    }
}
