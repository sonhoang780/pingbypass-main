package eu.client.mixins;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import eu.client.EUClient;
import eu.client.modules.impl.visuals.AtmosphereModule;
import eu.client.utils.graphics.EspShader;
import eu.client.utils.graphics.StarCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.MoonPhase;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import org.joml.Vector4f;
import org.joml.Vector4fc;

// StarGlow: verified via .mcref that SkyRenderer.renderStars() reads
// Minecraft.getInstance().gameRenderer.mainRenderTarget() directly (bypasses RenderSystem.
// outputColorTextureOverride entirely) to build its render pass. Redirecting the createRenderPass
// call itself, only for THIS one call site, points the star geometry's draw at an isolated target
// instead -- true per-pixel isolation (only stars ever land there), no threshold approximation.
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
    // PORT (26.2): createRenderPass's clear-color param is Optional<Vector4fc> (float RGBA) now,
    // not OptionalInt -- confirmed via real CommandEncoder/RenderPass source and multiple real
    // vanilla callers (WeatherEffectRenderer/DebugCrosshairRenderer both pass Optional.empty()).
    @Redirect(method = "renderStars", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;"))
    private RenderPass euclient$redirectStars(CommandEncoder encoder, Supplier<String> label, GpuTextureView color, Optional<Vector4fc> clearColor, GpuTextureView depth, OptionalDouble clearDepth) {
        AtmosphereModule atmosphere = EUClient.MODULE_MANAGER.getModule(AtmosphereModule.class);
        if (atmosphere.isToggled() && atmosphere.starGlow.getValue()) {
            var target = StarCapture.ensure();
            // Isolate COLOR only -- cleared to transparent every frame right here (as part of
            // creating the render pass), so the isolated target doesn't accumulate every past
            // frame's stars on top of each other. Depth stays the REAL scene depth (already
            // written by opaque terrain earlier this frame) and is passed through unmodified/
            // unclearred, so vanilla's own depth test still occludes star pixels behind blocks.
            // Previously this redirected depth to the isolated target's own buffer, cleared to
            // 1.0 (far) -- an empty depth buffer nothing could ever fail against, so every star
            // drew straight through walls/terrain regardless of what was actually blocking it.
            return encoder.createRenderPass(label, target.getColorTextureView(), Optional.of(new Vector4f(0.0F, 0.0F, 0.0F, 0.0F)), depth, clearDepth);
        }
        return encoder.createRenderPass(label, color, clearColor, depth, clearDepth);
    }

    // Composite the blurred glow here -- TAIL of renderSunMoonAndStars is the exact draw-order slot
    // vanilla's own star draw occupies within the "sky" frame-graph pass (see LevelRenderer#addSkyPass,
    // which writes straight to targets.main). Terrain/chunk sections render in a LATER frame-graph
    // pass and paint over targets.main normally -- occlusion here is draw order (painter's algorithm),
    // same as vanilla, not a depth test. Doing this composite later (as it used to, at GameRenderer's
    // doEntityOutline()) ran AFTER terrain had already painted, so the glow blit landed on top of
    // everything unconditionally and "glowed" straight through walls.
    @Inject(method = "renderSunMoonAndStars", at = @At("TAIL"))
    private void euclient$resolveStarGlow(PoseStack poseStack, float sunAngle, float moonAngle, float starAngle, MoonPhase moonPhase, float rainBrightness, float starBrightness, CallbackInfo ci) {
        if (starBrightness <= 0.0F) return; // renderStars() never ran this frame -- nothing new captured, target may be stale

        AtmosphereModule atmosphere = EUClient.MODULE_MANAGER.getModule(AtmosphereModule.class);
        if (!atmosphere.isToggled() || !atmosphere.starGlow.getValue()) return;

        RenderTarget starTarget = StarCapture.get();
        if (starTarget == null) return;

        Minecraft mc = Minecraft.getInstance();
        PostChain chain = mc.getShaderManager().getPostChain(Identifier.fromNamespaceAndPath("euclient", "star_glow"), LevelTargetBundle.MAIN_TARGETS);
        if (chain == null) return;

        EspShader.writeStarGlowSettings(chain, atmosphere.starGlowIntensity.getValue().floatValue());
        chain.process(starTarget, GraphicsResourceAllocator.UNPOOLED);
        // PORT (26.2): blitAndBlendToTexture gained a required depth-view param (confirmed via real
        // RenderTarget.java source) -- pass the main target's own depth view alongside its color view.
        starTarget.blitAndBlendToTexture(mc.gameRenderer.mainRenderTarget().getColorTextureView(), mc.gameRenderer.mainRenderTarget().getDepthTextureView());
    }
}
