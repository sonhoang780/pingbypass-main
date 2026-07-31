package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.visuals.NoRenderModule;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractSignRenderer.class)
public abstract class AbstractSignBlockEntityRendererMixin<S extends SignRenderState> {
    @Inject(method = "submitSignText", at = @At("HEAD"), cancellable = true)
    private void submitSignText(S state, PoseStack matrices, SubmitNodeCollector collector, SignText signText, CallbackInfo info) {
        if (EUClient.MODULE_MANAGER.getModule(NoRenderModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(NoRenderModule.class).signText.getValue()) {
            info.cancel();
        }
    }
}
