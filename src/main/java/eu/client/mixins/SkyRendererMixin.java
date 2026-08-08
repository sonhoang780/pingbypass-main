package eu.client.mixins;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import eu.client.EUClient;
import eu.client.modules.impl.visuals.AtmosphereModule;
import eu.client.utils.graphics.StarCapture;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;

// StarGlow: verified via .mcref that SkyRenderer.renderStars() reads
// Minecraft.getInstance().getMainRenderTarget() directly (bypasses RenderSystem.
// outputColorTextureOverride entirely) to build its render pass. Redirecting the createRenderPass
// call itself, only for THIS one call site, points the star geometry's draw at an isolated target
// instead -- true per-pixel isolation (only stars ever land there), no threshold approximation.
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
    @Redirect(method = "renderStars", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalInt;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;"))
    private RenderPass euclient$redirectStars(CommandEncoder encoder, Supplier<String> label, GpuTextureView color, OptionalInt clearColor, GpuTextureView depth, OptionalDouble clearDepth) {
        AtmosphereModule atmosphere = EUClient.MODULE_MANAGER.getModule(AtmosphereModule.class);
        if (atmosphere.isToggled() && atmosphere.starGlow.getValue()) {
            var target = StarCapture.ensure();
            // Cleared to transparent every frame right here (as part of creating the render pass)
            // -- without this the isolated target would just accumulate every past frame's stars
            // on top of each other forever, since nothing else ever clears it.
            return encoder.createRenderPass(label, target.getColorTextureView(), OptionalInt.of(0), target.getDepthTextureView(), OptionalDouble.of(1.0));
        }
        return encoder.createRenderPass(label, color, clearColor, depth, clearDepth);
    }
}
