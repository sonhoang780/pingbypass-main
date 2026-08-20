package eu.client.utils.graphics;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import eu.client.mixins.accessors.PostEffectProcessorAccessor;
import eu.client.mixins.accessors.PostPassAccessor;
import eu.client.mixins.accessors.RenderPipelinesAccessor;
import eu.client.utils.IMinecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import java.awt.*;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Animated ESP shader modes ported from Sydney-Legacy's GraphicsShaderUtil / Shader pair.
 * <p>
 * Sydney bound its nine GLSL programs with raw {@code glUseProgram} + {@code glUniform1f} around a
 * plain immediate-mode box draw, saving and restoring {@code GL_CURRENT_PROGRAM} by hand. None of
 * that reaches the 26.1.2 renderer: the program comes from a {@link RenderPipeline}, and uniforms
 * only arrive through a std140 UBO bound on the {@link RenderPass}. So the nine programs became one
 * fragment shader (assets/euclient/shaders/core/esp.fsh) switched by the {@code Effect} index, and
 * the draw is a hand-rolled copy of {@code RenderType.draw} with one extra
 * {@code renderPass.setUniform("EspSettings", ...)} -- {@code RenderType.draw} itself only ever binds
 * the built-in uniforms, so it cannot carry a custom UBO and cannot be reused here.
 * <p>
 * Both pipelines reuse VANILLA vertex shaders by Identifier (core/position_color for quads,
 * core/rendertype_lines for lines) -- the vertex stage needed no changes at all, so no .vsh ships
 * with this. The lines pipeline declares Fog/Globals UBOs that esp.fsh never references; that is
 * fine, GlProgram.setupUniforms drops pipeline uniforms with no matching active block.
 */
public class EspShader implements IMinecraft {
    public static final String[] MODES = {"None", "Gradient", "Rainbow", "Garmadon", "Nebula", "Blue", "Purple", "Glowing", "Pink", "Neekeri"};

    private static final Identifier FRAGMENT = Identifier.fromNamespaceAndPath("euclient", "core/esp");

    // Same no-depth-test / no-depth-write treatment as Renderer3D's NO_DEPTH_* types so the shaded
    // box behaves identically to the flat one it replaces (visible through terrain).
    private static final RenderPipeline QUADS_PIPELINE = RenderPipeline.builder(new RenderPipeline.Snippet[]{RenderPipelinesAccessor.getDebugFilledSnippet()})
            .withLocation("euclient/esp_shader_quads")
            .withFragmentShader(FRAGMENT)
            .withUniform("EspSettings", UniformType.UNIFORM_BUFFER)
            .withCull(false)
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .build();

    private static final RenderPipeline LINES_PIPELINE = RenderPipeline.builder(new RenderPipeline.Snippet[]{RenderPipelinesAccessor.getLinesSnippet()})
            .withLocation("euclient/esp_shader_lines")
            .withFragmentShader(FRAGMENT)
            .withUniform("EspSettings", UniformType.UNIFORM_BUFFER)
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .build();

    private static final int UBO_SIZE = new Std140SizeCalculator()
            .putVec4().putVec4().putVec4().putVec4()
            .putVec2().putFloat().putFloat().putFloat().putFloat().putInt()
            // std140 pads a block out to a multiple of 16; the calculator doesn't (vanilla's own
            // GlobalSettingsUniform is a non-multiple and gets away with it), but binding a range
            // shorter than the block size the driver reports is asking for trouble on some GL
            // implementations. 4 bytes to not care.
            .align(16)
            .get();

    // ShadersModule's Fill/Outline is a POST-CHAIN, not geometry, so it can't share the UBO above:
    // its uniform block lives inside a PostPass built from JSON. All-vec4 block, deliberately --
    // an all-vec4 block has exactly one possible std140 layout, so our Java-side write can't
    // disagree with whatever packing vanilla's UniformValue.writeTo used for the JSON defaults.
    // 6th vec4 (Extra.x = Opacity) added for ShadersModule's per-shader Opacity dial -- packed
    // alongside instead of a lone trailing float, same all-vec4 reasoning as the original 5.
    private static final String OUTLINE_UNIFORM = "EspSettings";
    private static final int OUTLINE_UBO_SIZE = new Std140SizeCalculator()
            .putVec4().putVec4().putVec4().putVec4().putVec4().putVec4()
            .get();

    // StarGlow's own live uniform (Intensity, packed into a vec4 same as OUTLINE_UNIFORM's
    // all-vec4 reasoning) -- same COPY_DST buffer-swap trick, own chain/buffer since star_glow's
    // "swap" target is now genuinely isolated (SkyRendererMixin) and no longer shared with
    // anything else this could cross-talk with.
    // Two separate uniform keys now (2026-08-19 perf fix, see star_glow.fsh's own doc for the
    // FPS-drop root cause): pass 1 (star_glow_blur.fsh, horizontal) reads "StarGlowConfig" with
    // Direction=(1,0); pass 2 (star_glow.fsh, vertical+composite) reads "StarGlowConfigV" with
    // Direction=(0,1). Sharing one key/buffer across both (the old shape) would overwrite each
    // pass's baked-in direction with whatever the other pass's write left last -- a single "loop
    // over every pass with this key" can only ever hold one Direction value.
    private static final String STAR_GLOW_UNIFORM = "StarGlowConfig";
    private static final String STAR_GLOW_UNIFORM_V = "StarGlowConfigV";
    private static final int STAR_GLOW_UBO_SIZE = new Std140SizeCalculator().putVec4().get();
    private static GpuBuffer starGlowUboBuffer;
    private static GpuBuffer starGlowUboBufferV;

    public static void writeStarGlowSettings(PostChain chain, float intensity) {
        // Brightness alone clamps to solid white well before Intensity's upper range (the shader's
        // max(original, blurred*Intensity) clips to [0,1] on write) -- past that point cranking
        // Intensity higher did nothing visible. Scale blur Radius up with it too, capped at the
        // shader's MAX_WIDTH=14, so the slider keeps producing a visibly bigger/softer halo across
        // its whole range instead of going invisible past ~5.
        float radius = Math.min(4.0f + intensity * 0.05f, 14.0f);

        for (PostPass pass : ((PostEffectProcessorAccessor) chain).getPasses()) {
            Map<String, GpuBuffer> uniforms = ((PostPassAccessor) pass).euclient$getCustomUniforms();

            if (uniforms.containsKey(STAR_GLOW_UNIFORM)) {
                if (starGlowUboBuffer == null || uniforms.get(STAR_GLOW_UNIFORM) != starGlowUboBuffer) {
                    starGlowUboBuffer = RenderSystem.getDevice().createBuffer(() -> "EUClient star glow H shader UBO", 136, STAR_GLOW_UBO_SIZE);
                    GpuBuffer displaced = uniforms.put(STAR_GLOW_UNIFORM, starGlowUboBuffer);
                    if (displaced != null) displaced.close();
                }
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    ByteBuffer data = Std140Builder.onStack(stack, STAR_GLOW_UBO_SIZE)
                            .putVec4(intensity, radius, 1.0f, 0.0f)
                            .get();
                    RenderSystem.getDevice().createCommandEncoder().writeToBuffer(starGlowUboBuffer.slice(), data);
                }
            }

            if (uniforms.containsKey(STAR_GLOW_UNIFORM_V)) {
                if (starGlowUboBufferV == null || uniforms.get(STAR_GLOW_UNIFORM_V) != starGlowUboBufferV) {
                    starGlowUboBufferV = RenderSystem.getDevice().createBuffer(() -> "EUClient star glow V shader UBO", 136, STAR_GLOW_UBO_SIZE);
                    GpuBuffer displaced = uniforms.put(STAR_GLOW_UNIFORM_V, starGlowUboBufferV);
                    if (displaced != null) displaced.close();
                }
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    ByteBuffer data = Std140Builder.onStack(stack, STAR_GLOW_UBO_SIZE)
                            .putVec4(intensity, radius, 0.0f, 1.0f)
                            .get();
                    RenderSystem.getDevice().createCommandEncoder().writeToBuffer(starGlowUboBufferV.slice(), data);
                }
            }
        }
    }

    private static GpuBuffer uboBuffer;
    private static GpuBuffer outlineUboBuffer;

    /** Per-frame state written by whoever queued the geometry, consumed by {@link #draw}. */
    private static Settings settings;

    public static int modeIndex(String mode) {
        for (int i = 0; i < MODES.length; i++) {
            if (MODES[i].equalsIgnoreCase(mode)) return i;
        }

        return 0;
    }

    public static void setSettings(Settings value) {
        settings = value;
    }

    /**
     * Animates the {@code EspSettings} block of an already-loaded outline post-chain, for
     * ShadersModule's Fill/Outline (a screen-space post-process, unlike everything else here).
     * <p>
     * PostPass has no uniform setter, and its own custom-uniform buffers are created with usage
     * {@code 128} (UNIFORM) alone -- no USAGE_COPY_DST -- so writing to vanilla's buffer throws
     * "Buffer needs USAGE_COPY_DST to be a destination for a copy" in GlCommandEncoder. What IS
     * writable is the {@code customUniforms} map itself (a plain HashMap): swap in our OWN buffer,
     * created with COPY_DST, and rewrite that every frame. PostPass.close() closes every buffer in
     * the map -- including ours -- so ownership is detected by identity: if the map no longer holds
     * our instance the chain was rebuilt (resource reload / variant switch) and ours is already
     * closed, so make a fresh one. The displaced buffer is closed here since nothing else holds it.
     * <p>
     * Always called, even with {@code effect == 0}: the chain object is shared with Chams/PopChams,
     * so a stale non-zero Effect left in the buffer would shade THEIR silhouettes too.
     */
    public static void writeOutlineSettings(PostChain chain, Settings settings) {
        for (PostPass pass : ((PostEffectProcessorAccessor) chain).getPasses()) {
            Map<String, GpuBuffer> uniforms = ((PostPassAccessor) pass).euclient$getCustomUniforms();
            if (!uniforms.containsKey(OUTLINE_UNIFORM)) continue;

            if (outlineUboBuffer == null || uniforms.get(OUTLINE_UNIFORM) != outlineUboBuffer) {
                outlineUboBuffer = RenderSystem.getDevice().createBuffer(() -> "EUClient outline shader UBO", 136, OUTLINE_UBO_SIZE);
                GpuBuffer displaced = uniforms.put(OUTLINE_UNIFORM, outlineUboBuffer);
                if (displaced != null) displaced.close();
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer data = Std140Builder.onStack(stack, OUTLINE_UBO_SIZE)
                        .putVec4(settings.color0)
                        .putVec4(settings.color1)
                        .putVec4(settings.color2)
                        .putVec4(settings.color3)
                        .putVec4(settings.time, settings.step, settings.distance, settings.effect)
                        .putVec4(settings.alpha, 0.0f, 0.0f, 0.0f)
                        .get();
                RenderSystem.getDevice().createCommandEncoder().writeToBuffer(outlineUboBuffer.slice(), data);
            }
        }
    }

    public static void draw(List<Renderer3D.VertexCollection> quads, List<Renderer3D.VertexCollection> debugLines) {
        if (settings == null || (quads.isEmpty() && debugLines.isEmpty())) {
            quads.clear();
            debugLines.clear();
            return;
        }

        if (uboBuffer == null) {
            uboBuffer = RenderSystem.getDevice().createBuffer(() -> "EUClient ESP shader UBO", 136, UBO_SIZE);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, UBO_SIZE)
                    .putVec4(settings.color0)
                    .putVec4(settings.color1)
                    .putVec4(settings.color2)
                    .putVec4(settings.color3)
                    .putVec2(mc.getWindow().getWidth(), mc.getWindow().getHeight())
                    .putFloat(settings.time)
                    .putFloat(settings.step)
                    .putFloat(settings.alpha)
                    .putFloat(settings.distance)
                    .putInt(settings.effect)
                    .get();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(uboBuffer.slice(), data);
        }

        if (!quads.isEmpty()) {
            BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (Renderer3D.VertexCollection collection : quads) collection.quad(buffer);
            submit(QUADS_PIPELINE, buffer.build());
        }

        if (!debugLines.isEmpty()) {
            BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH);
            Renderer3D.buildLines(buffer, debugLines);
            submit(LINES_PIPELINE, buffer.build());
        }

        quads.clear();
        debugLines.clear();
    }

    // Mirrors RenderType.draw (26.1.2) exactly, minus the texture/layering bits we never use, plus
    // the custom UBO bind. Kept in one place so both pipelines share it.
    private static void submit(RenderPipeline pipeline, MeshData mesh) {
        if (mesh == null) return;

        var dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrix(), new Vector4f(1.0F, 1.0F, 1.0F, 1.0F), new Vector3f(), new org.joml.Matrix4f());

        try (mesh) {
            GpuBuffer vertices = pipeline.getVertexFormat().uploadImmediateVertexBuffer(mesh.vertexBuffer());
            GpuBuffer indices;
            VertexFormat.IndexType indexType;

            if (mesh.indexBuffer() == null) {
                RenderSystem.AutoStorageIndexBuffer autoIndices = RenderSystem.getSequentialBuffer(mesh.drawState().mode());
                indices = autoIndices.getBuffer(mesh.drawState().indexCount());
                indexType = autoIndices.type();
            } else {
                indices = pipeline.getVertexFormat().uploadImmediateIndexBuffer(mesh.indexBuffer());
                indexType = mesh.drawState().indexType();
            }

            RenderTarget renderTarget = OutputTarget.MAIN_TARGET.getRenderTarget();
            GpuTextureView colorTexture = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : renderTarget.getColorTextureView();
            GpuTextureView depthTexture = renderTarget.useDepth
                    ? (RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : renderTarget.getDepthTextureView())
                    : null;

            try (RenderPass renderPass = RenderSystem.getDevice()
                    .createCommandEncoder()
                    .createRenderPass(() -> "EUClient ESP shader draw", colorTexture, OptionalInt.empty(), depthTexture, OptionalDouble.empty())) {
                renderPass.setPipeline(pipeline);
                RenderSystem.bindDefaultUniforms(renderPass);
                renderPass.setUniform("DynamicTransforms", dynamicTransforms);
                renderPass.setUniform("EspSettings", uboBuffer);
                renderPass.setVertexBuffer(0, vertices);
                renderPass.setIndexBuffer(indices, indexType);
                renderPass.drawIndexed(0, 0, mesh.drawState().indexCount(), 1);
            }
        }
    }

    /**
     * One frame's worth of shader uniforms. {@code time} is already speed-scaled by the caller, the
     * same way Sydney folded speed into its {@code time} uniform rather than uploading it separately.
     */
    public static class Settings {
        public final int effect;
        public final float time;
        public final float step;
        public final float alpha;
        public final float distance;
        public final Vector4f color0;
        public final Vector4f color1;
        public final Vector4f color2;
        public final Vector4f color3;

        public Settings(int effect, float time, float step, float alpha, float distance, Color c0, Color c1, Color c2, Color c3) {
            this.effect = effect;
            this.time = time;
            this.step = step;
            this.alpha = alpha;
            this.distance = distance;
            this.color0 = toVector(c0);
            this.color1 = toVector(c1);
            this.color2 = toVector(c2);
            this.color3 = toVector(c3);
        }

        private static Vector4f toVector(Color color) {
            return new Vector4f(color.getRed() / 255.0f, color.getGreen() / 255.0f, color.getBlue() / 255.0f, color.getAlpha() / 255.0f);
        }
    }
}
