package eu.client.gui.special;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import eu.client.mixins.accessors.PostEffectProcessorAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

import java.util.Set;

/**
 * Shared real-blur engine for the Cozy menu (docs/menu-style-ideas.md) -- replaces the earlier
 * fake glow (a handful of semi-transparent quads layered by hand) with an actual GPU box blur,
 * same family of technique as {@code outline.fsh}/{@code EspShader}, reused here for the hover
 * text glow AND the ember particles.
 * <p>
 * Mechanism: draw the glowing content (text or embers) IMMEDIATE-mode into a scratch
 * {@link TextureTarget} the same size as the screen (avoids ever needing a second projection
 * matrix -- the existing GUI ortho projection already matches 1:1), run it through a one-pass
 * box-blur {@link PostChain} ({@code glow_blur.fsh}/{@code post_effect/glow_blur.json}), then
 * blit the blurred, tinted result back as a normal (retained, correctly-ordered) GUI quad.
 * <p>
 * "Immediate" matters: {@code GuiGraphicsExtractor}'s own draw calls ({@code context.text}/
 * {@code renderQuad}) are retained -- they queue state for {@code GuiRenderer} to batch-flush
 * LATER in the frame, so wrapping them in {@link RenderSystem#outputColorTextureOverride} does
 * nothing (the override would already be cleared by the time the batch actually flushes). Real
 * immediate draws (this class's {@link #beginCapture}/{@link #endCapture} window) execute their
 * GPU commands synchronously, the same way {@code ItemInHandRendererMixin}'s hand-outline flush
 * does -- that's the one other place in this codebase doing exactly this kind of redirect.
 */
public class CozyGlowCapture {
    private static final Identifier CHAIN_ID = Identifier.fromNamespaceAndPath("euclient", "glow_blur");
    private static final Identifier SWAP_TARGET_ID = Identifier.withDefaultNamespace("swap");
    private static final Identifier GLOW_TEXTURE_ID = Identifier.fromNamespaceAndPath("euclient", "menu/cozy_glow");

    private TextureTarget captureTarget;
    private int cachedWidth = -1, cachedHeight = -1;
    private PostChain chain;
    private boolean chainLoadFailed = false;
    private GlowTexture glowTexture;

    /** Clears the scratch target and redirects immediate draws into it. Pair with {@link #endCapture}. */
    public void beginCapture(int width, int height) {
        ensureTarget(width, height);
        RenderSystem.getDevice().createCommandEncoder()
                .clearColorAndDepthTextures(captureTarget.getColorTexture(), 0, captureTarget.getDepthTexture(), 1.0);
        RenderSystem.outputColorTextureOverride = captureTarget.getColorTextureView();
        // RenderType.draw() only substitutes the depth attachment when the ORIGINAL bound
        // target (whatever "minecraft:main" resolves to right now, which DOES useDepth) says
        // useDepth -- if only the color override is set, it still binds THAT target's real
        // depth view alongside our differently-sized capture target's color view. A render pass
        // with mismatched color/depth attachment sizes is invalid and gets silently dropped by
        // the backend (no exception, nothing drawn) -- exactly the "chain loads fine, nothing
        // ever appears" symptom. Matches ItemInHandRendererMixin's flush, which overrides BOTH
        // for the same reason; that one working (and this one not) was the tell.
        RenderSystem.outputDepthTextureOverride = captureTarget.getDepthTextureView();
    }

    public void endCapture() {
        RenderSystem.outputColorTextureOverride = null;
        RenderSystem.outputDepthTextureOverride = null;
    }

    /** Immediate text draw for use between {@link #beginCapture}/{@link #endCapture} -- same idea as ItemInHandRendererMixin's endOutlineBatch flush, just for Font instead of the outline buffer. */
    public void captureText(String text, float x, float y, int color) {
        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        Minecraft.getInstance().font.drawInBatch(text, x, y, color, false, new PoseStack().last().pose(),
                bufferSource, Font.DisplayMode.NORMAL, 0, 0xF000F0);
        bufferSource.endBatch();
    }

    /** Blurs whatever was captured and blits it full-bleed as a normal (retained, order-correct) GUI quad. */
    public void blurAndComposite(GuiGraphicsExtractor context, int width, int height) {
        if (captureTarget == null || !ensureChain()) return;

        chain.process(captureTarget, GraphicsResourceAllocator.UNPOOLED);

        RenderTarget swap = ((PostEffectProcessorAccessor) chain).getPersistentTargets().get(SWAP_TARGET_ID);
        if (swap == null || swap.getColorTextureView() == null) {
            if (!loggedSwapNull) {
                loggedSwapNull = true;
                org.slf4j.LoggerFactory.getLogger("EUClient/CozyGlow").error("[CozyGlow] swap target missing after process(): swap={} keys={}",
                        swap, ((PostEffectProcessorAccessor) chain).getPersistentTargets().keySet());
            }
            return;
        }

        if (glowTexture == null || !glowTexture.wraps(swap.getColorTextureView())) {
            if (glowTexture != null) Minecraft.getInstance().getTextureManager().release(GLOW_TEXTURE_ID);
            glowTexture = new GlowTexture(swap.getColorTexture(), swap.getColorTextureView());
            Minecraft.getInstance().getTextureManager().register(GLOW_TEXTURE_ID, glowTexture);
        }

        context.blit(RenderPipelines.GUI_TEXTURED, GLOW_TEXTURE_ID, 0, 0, 0, 0, width, height, width, height, width, height, -1);
    }

    private void ensureTarget(int width, int height) {
        if (captureTarget != null && width == cachedWidth && height == cachedHeight) return;

        if (captureTarget != null) captureTarget.destroyBuffers();
        captureTarget = new TextureTarget("euclient_cozy_glow_capture", width, height, true);
        cachedWidth = width;
        cachedHeight = height;
    }

    private static boolean loggedOnce = false;
    private boolean loggedSwapNull = false;

    private boolean ensureChain() {
        if (chain != null) return true;
        if (chainLoadFailed) return false;

        chain = Minecraft.getInstance().getShaderManager().getPostChain(CHAIN_ID, Set.of(PostChain.MAIN_TARGET_ID));
        if (chain == null) {
            chainLoadFailed = true;
            if (!loggedOnce) {
                loggedOnce = true;
                org.slf4j.LoggerFactory.getLogger("EUClient/CozyGlow").error("[CozyGlow] getPostChain returned null for {}", CHAIN_ID);
            }
        } else if (!loggedOnce) {
            loggedOnce = true;
            org.slf4j.LoggerFactory.getLogger("EUClient/CozyGlow").info("[CozyGlow] chain loaded ok: {}", CHAIN_ID);
        }
        return chain != null;
    }

    public void close() {
        if (captureTarget != null) {
            captureTarget.destroyBuffers();
            captureTarget = null;
        }
        if (glowTexture != null) {
            Minecraft.getInstance().getTextureManager().release(GLOW_TEXTURE_ID);
            glowTexture.close();
            glowTexture = null;
        }
        // Not owned by us -- getPostChain() is cached/managed by ShaderManager itself (same
        // instance handed back to anyone requesting this id, reloaded on resource reload), same
        // as how ShadersModule/GameRendererMixin never close() the outline chains they fetch.
        chain = null;
        cachedWidth = -1;
        cachedHeight = -1;
    }

    /** Thin wrapper so the blur target's already-existing GPU texture can be blit()'d by Identifier -- doesn't own or close the underlying texture. */
    private static class GlowTexture extends AbstractTexture {
        GlowTexture(GpuTexture texture, GpuTextureView textureView) {
            this.texture = texture;
            this.textureView = textureView;
        }

        boolean wraps(GpuTextureView view) {
            return this.textureView == view;
        }

        @Override
        public void close() {
            // Deliberately not super.close() -- that would close the PostChain's own persistent
            // target textures, which we don't own and which the chain reuses next frame.
        }
    }
}
