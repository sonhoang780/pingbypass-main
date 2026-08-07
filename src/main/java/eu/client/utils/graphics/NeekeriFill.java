package eu.client.utils.graphics;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import eu.client.mixins.accessors.PostEffectProcessorAccessor;
import eu.client.mixins.accessors.PostPassAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.Map;

// The real Neekeri GLSL pattern (assets/euclient/shaders/program/neekeri_ui.fsh, a standalone
// copy of outline.fsh's esp_neekeri()) rendered ONCE per frame into a screen-sized persistent
// target, then blitted as CROPPED windows into it for however many UI elements want the fill this
// frame (ClickGui rows, vanilla buttons) -- same idea as CozyGlowCapture's GlowTexture, just
// procedural (no capture step) and reused across many draw calls instead of one.
public class NeekeriFill {
    private static final Identifier CHAIN_ID = Identifier.fromNamespaceAndPath("euclient", "neekeri_ui");
    private static final Identifier PATTERN_TARGET_ID = Identifier.withDefaultNamespace("pattern");
    private static final Identifier TEXTURE_ID = Identifier.fromNamespaceAndPath("euclient", "neekeri_ui_pattern");
    private static final String UNIFORM_NAME = "NeekeriConfig";
    private static final int UBO_SIZE = new Std140SizeCalculator().putVec4().get();

    private static PostChain chain;
    private static boolean chainLoadFailed = false;
    private static GpuBuffer uboBuffer;
    private static long lastProcessedAtMs = -1;
    private static PatternTexture texture;

    // One shared phase, not real elapsed time directly -- so ClickGuiModule.neekeriSpeed can
    // scale the animation rate (matching ShadersModule's own Speed dial) without discontinuously
    // jumping the pattern when the slider moves. Every caller (ClickGui rows, vanilla buttons)
    // reads the SAME phase every frame, which is what makes it "chung 1 neekeri" -- one shared
    // animated field, not one instance per widget.
    private static float phaseSeconds = 0f;
    private static long lastPhaseUpdateMs = -1;

    /** Reprocesses the pattern at most once per ~frame (cheap guard, not per-widget). No frame-id API in 26.1.2's RenderSystem; a time guard is close enough since this is a purely cosmetic animation. */
    private static void ensureProcessed() {
        long now = System.currentTimeMillis();
        if (now - lastProcessedAtMs < 10) return;
        lastProcessedAtMs = now;

        if (chain == null && !chainLoadFailed) {
            chain = Minecraft.getInstance().getShaderManager().getPostChain(CHAIN_ID, LevelTargetBundle.MAIN_TARGETS);
            if (chain == null) chainLoadFailed = true;
        }
        if (chain == null) return;

        writeTime();
        chain.process(Minecraft.getInstance().getMainRenderTarget(), GraphicsResourceAllocator.UNPOOLED);

        RenderTarget pattern = ((PostEffectProcessorAccessor) chain).getPersistentTargets().get(PATTERN_TARGET_ID);
        if (pattern == null || pattern.getColorTextureView() == null) return;

        if (texture == null || !texture.wraps(pattern.getColorTextureView())) {
            if (texture != null) Minecraft.getInstance().getTextureManager().release(TEXTURE_ID);
            texture = new PatternTexture(pattern.getColorTexture(), pattern.getColorTextureView());
            Minecraft.getInstance().getTextureManager().register(TEXTURE_ID, texture);
        }
    }

    private static void writeTime() {
        for (PostPass pass : ((PostEffectProcessorAccessor) chain).getPasses()) {
            Map<String, GpuBuffer> uniforms = ((PostPassAccessor) pass).euclient$getCustomUniforms();
            if (!uniforms.containsKey(UNIFORM_NAME)) continue;

            if (uboBuffer == null || uniforms.get(UNIFORM_NAME) != uboBuffer) {
                uboBuffer = RenderSystem.getDevice().createBuffer(() -> "EUClient neekeri fill UBO", 136, UBO_SIZE);
                GpuBuffer displaced = uniforms.put(UNIFORM_NAME, uboBuffer);
                if (displaced != null) displaced.close();
            }

            eu.client.modules.impl.core.ClickGuiModule clickGui = eu.client.EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.core.ClickGuiModule.class);
            long now = System.currentTimeMillis();
            float speed = clickGui.neekeriSpeed.getValue().floatValue();
            if (lastPhaseUpdateMs >= 0) phaseSeconds += (now - lastPhaseUpdateMs) / 1000.0f * speed;
            lastPhaseUpdateMs = now;

            // Effect index matches EspShader.MODES (1=Gradient .. 9=Neekeri); "Default" never
            // reaches this shader at all (callers skip NeekeriFill.fill() for that mode).
            int effect = java.util.Arrays.asList(EspShader.MODES).indexOf(clickGui.fillMode.getValue());
            if (effect <= 0) effect = EspShader.MODES.length - 1; // fall back to Neekeri

            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer data = Std140Builder.onStack(stack, UBO_SIZE).putVec4(phaseSeconds, (float) effect, 0.0f, 0.0f).get();
                RenderSystem.getDevice().createCommandEncoder().writeToBuffer(uboBuffer.slice(), data);
            }
        }
    }

    /** Draws a WINDOW into the shared pattern at the given screen rect (own screen position as the crop offset, so every widget shows its own slice of the SAME continuous field instead of dozens of identical self-contained swirls). */
    public static void fill(GuiGraphicsExtractor context, int x, int y, int width, int height, int alpha) {
        ensureProcessed();
        if (texture == null) return;

        int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        // u/v (x,y) = pixel offset into the texture to start cropping from; srcWidth/srcHeight
        // (width,height) = how many texture pixels that crop covers -- these were wrongly set to
        // the FULL screen size before, which sampled nearly the whole texture into every tiny row
        // (each looking like its own independent pattern). textureWidth/textureHeight (screenW/
        // screenH) just normalize u/v into 0..1, unrelated to crop size.
        context.blit(RenderPipelines.GUI_TEXTURED, TEXTURE_ID, x, y, x, y, width, height, width, height, screenW, screenH,
                (alpha << 24) | 0xFFFFFF);
    }

    private static class PatternTexture extends AbstractTexture {
        PatternTexture(GpuTexture texture, GpuTextureView view) {
            this.texture = texture;
            this.textureView = view;
        }

        boolean wraps(GpuTextureView view) {
            return this.textureView == view;
        }

        @Override
        public void close() {
            // Not owned by us -- the PostChain's own persistent target texture, reused next frame.
        }
    }
}
