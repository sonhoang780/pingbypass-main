package eu.client.utils.graphics;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import eu.client.EUClient;
import eu.client.mixins.accessors.ItemStackRenderStateAccessor;
import eu.client.mixins.accessors.LayerRenderStateAccessor;
import eu.client.mixins.accessors.RenderPipelinesAccessor;
import eu.client.utils.IMinecraft;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderSetup.OutlineProperty;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Renderer3D implements IMinecraft {
    public static boolean RENDERING = false;

    // PORT (26.2): RenderType.draw(MeshData) (the old one-line "just draw this mesh" convenience
    // method) is REMOVED entirely -- confirmed via real RenderType.java (no draw method on the
    // class at all anymore). Real replacement, found via real source: renderType.prepare()
    // (-> PreparedRenderType, handles output-target/textures/dynamic-transforms/scissor
    // automatically) + PreparedRenderType.drawFromBuffer(vertexBuffer, indexBuffer, indexType,
    // baseVertex, firstIndex, indexCount) -- read PreparedRenderType.java in full, it does
    // internally EXACTLY the manual RenderPass-build dance EspShader.submit() already hand-rolls
    // (see its own PORT comment), just vanilla-provided now. One shared helper here instead of
    // repeating the upload+prepare+drawFromBuffer dance at every one of the ~15 old .draw(mesh)
    // call sites across Renderer2D/Renderer3D/FontRenderer.
    public static void draw(RenderType renderType, MeshData mesh) {
        if (mesh == null) return;

        try (mesh) {
            GpuBuffer vertices = RenderSystem.getDevice().createBuffer(() -> "EUClient immediate vertices", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            GpuBuffer indices;
            IndexType indexType;

            if (mesh.indexBuffer() == null) {
                RenderSystem.AutoStorageIndexBuffer autoIndices = RenderSystem.getSequentialBuffer(mesh.drawState().primitiveTopology());
                indices = autoIndices.getBuffer(mesh.drawState().indexCount());
                indexType = autoIndices.type();
            } else {
                indices = RenderSystem.getDevice().createBuffer(() -> "EUClient immediate indices", GpuBuffer.USAGE_INDEX, mesh.indexBuffer());
                indexType = mesh.drawState().indexType();
            }

            renderType.prepare().drawFromBuffer(vertices, indices, indexType, 0, 0, mesh.drawState().indexCount());
        }
    }

    // PORT: the 1.21.4 renderer wrapped every draw in RenderSystem.disableDepthTest()/
    // enableDepthTest() so ESP-style boxes/lines always show through solid terrain. That
    // imperative API is gone in 26.1.2 -- depth testing is now baked into the RenderPipeline
    // itself. RenderTypes.debugQuads()/lines() keep depth *testing* on (only depth *writing* is
    // off), so our boxes get occluded by whatever block they're drawn inside of (Expand needed
    // "phasing into" the block to see it; Rise z-fought against the block's own top face at
    // progress~1.0). Clone the same snippets with CompareOp.ALWAYS_PASS to restore the old
    // always-visible behavior.
    private static final RenderType NO_DEPTH_QUADS = RenderType.create("euclient_no_depth_quads",
            RenderSetup.builder(RenderPipeline.builder(new RenderPipeline.Snippet[]{RenderPipelinesAccessor.getDebugFilledSnippet()})
                    .withLocation("euclient/no_depth_quads")
                    .withCull(false)
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                    .build()).sortOnUpload().createRenderSetup());

    private static final RenderType NO_DEPTH_LINES = RenderType.create("euclient_no_depth_lines",
            RenderSetup.builder(RenderPipeline.builder(new RenderPipeline.Snippet[]{RenderPipelinesAccessor.getLinesSnippet()})
                    .withLocation("euclient/no_depth_lines")
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                    .build()).createRenderSetup());

    // ChamsModule "Shine": the pre-port version was literally the SAME no-depth quads/lines,
    // just with RenderSystem.blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_CONSTANT_ALPHA) swapped in for
    // the draw call -- i.e. a DIFFERENT blend than plain alpha-blend, nothing else different (same
    // colors, same geometry). Overlapping translucent faces (front+back of a box both visible
    // through the fill, multiple limbs/cubes crossing on screen, etc.) then compound into bright
    // hotspots instead of just alpha-blending flat -- the "glossy sweep" look in the reference
    // screenshot -- not a special color or animated highlight, purely a blend-mode difference on
    // otherwise identical geometry.
    //
    // NOT vanilla's BlendFunction.ADDITIVE preset -- verified via javap, that's (ONE, ONE), which
    // ignores source alpha entirely (every quad contributes its FULL unscaled RGB regardless of
    // the configured fill alpha), saturating to solid opaque white within 2-3 overlapping layers
    // and completely white-washing anything with denser overlap (a crystal's 3 nested cubes have
    // far more overlapping faces than a player silhouette does -- confirmed live-tested bug: crystal
    // shine rendered as a solid white blob instead of the intended contained glossy highlight).
    // Reproduce the ORIGINAL (SRC_ALPHA, ONE_MINUS_CONSTANT_ALPHA) exactly instead -- SRC_ALPHA
    // properly scales each layer's contribution by its actual configured alpha, self-limiting the
    // same way the pre-port version did. Blend state is baked per-RenderPipeline now (raw GL calls
    // don't reach this pipeline, see the NO_DEPTH_QUADS comment above), hence a second pair of
    // RenderTypes instead of a runtime blendFunc() call.
    private static final BlendFunction SHINE_BLEND = new BlendFunction(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_CONSTANT_ALPHA);

    private static final RenderType NO_DEPTH_QUADS_SHINE = RenderType.create("euclient_no_depth_quads_shine",
            RenderSetup.builder(RenderPipeline.builder(new RenderPipeline.Snippet[]{RenderPipelinesAccessor.getDebugFilledSnippet()})
                    .withLocation("euclient/no_depth_quads_shine")
                    .withCull(false)
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                    .withColorTargetState(new ColorTargetState(SHINE_BLEND))
                    .build()).sortOnUpload().createRenderSetup());

    private static final RenderType NO_DEPTH_LINES_SHINE = RenderType.create("euclient_no_depth_lines_shine",
            RenderSetup.builder(RenderPipeline.builder(new RenderPipeline.Snippet[]{RenderPipelinesAccessor.getLinesSnippet()})
                    .withLocation("euclient/no_depth_lines_shine")
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                    .withColorTargetState(new ColorTargetState(SHINE_BLEND))
                    .build()).createRenderSetup());

    public static List<VertexCollection> QUADS = new ArrayList<>();
    public static List<VertexCollection> DEBUG_LINES = new ArrayList<>();

    public static List<VertexCollection> SHINE_QUADS = new ArrayList<>();
    public static List<VertexCollection> SHINE_DEBUG_LINES = new ArrayList<>();

    // Geometry destined for EspShader's animated fragment shader instead of the flat-colour
    // pipelines. Same vertex data, different draw -- see EspShader.draw().
    public static List<VertexCollection> SHADER_QUADS = new ArrayList<>();
    public static List<VertexCollection> SHADER_DEBUG_LINES = new ArrayList<>();

    public static void renderBox(PoseStack matrices, AABB box, Color color) {
        renderGradientBox(matrices, box, color, color);
    }

    public static void renderGradientBox(PoseStack matrices, AABB box, Color startColor, Color endColor) {
        renderGradientBox(QUADS, matrices, box, startColor, endColor);
    }

    public static void renderGradientBox(List<VertexCollection> QUADS, PoseStack matrices, AABB box, Color startColor, Color endColor) {
        if (!RENDERING) return;
        if (!isFrustumVisible(box)) return;

        Matrix4f matrix = matrices.last().pose();
        box = cameraTransform(box);

        QUADS.add(new VertexCollection(new Vertex(matrix, (float) box.minX, (float) box.maxY, (float) box.minZ, startColor.getRGB()),
                new Vertex(matrix, (float) box.minX, (float) box.maxY, (float) box.maxZ, startColor.getRGB()),
                new Vertex(matrix, (float) box.maxX, (float) box.maxY, (float) box.maxZ, startColor.getRGB()),
                new Vertex(matrix, (float) box.maxX, (float) box.maxY, (float) box.minZ, startColor.getRGB())));

        QUADS.add(new VertexCollection(new Vertex(matrix, (float) box.minX, (float) box.minY, (float) box.maxZ, endColor.getRGB()),
                new Vertex(matrix, (float) box.maxX, (float) box.minY, (float) box.maxZ, endColor.getRGB()),
                new Vertex(matrix, (float) box.maxX, (float) box.maxY, (float) box.maxZ, startColor.getRGB()),
                new Vertex(matrix, (float) box.minX, (float) box.maxY, (float) box.maxZ, startColor.getRGB())));

        QUADS.add(new VertexCollection(new Vertex(matrix, (float) box.maxX, (float) box.maxY, (float) box.maxZ, startColor.getRGB()),
                new Vertex(matrix, (float) box.maxX, (float) box.minY, (float) box.maxZ, endColor.getRGB()),
                new Vertex(matrix, (float) box.maxX, (float) box.minY, (float) box.minZ, endColor.getRGB()),
                new Vertex(matrix, (float) box.maxX, (float) box.maxY, (float) box.minZ, startColor.getRGB())));

        QUADS.add(new VertexCollection(new Vertex(matrix, (float) box.maxX, (float) box.maxY, (float) box.minZ, startColor.getRGB()),
                new Vertex(matrix, (float) box.maxX, (float) box.minY, (float) box.minZ, endColor.getRGB()),
                new Vertex(matrix, (float) box.minX, (float) box.minY, (float) box.minZ, endColor.getRGB()),
                new Vertex(matrix, (float) box.minX, (float) box.maxY, (float) box.minZ, startColor.getRGB())));

        QUADS.add(new VertexCollection(new Vertex(matrix, (float) box.minX, (float) box.maxY, (float) box.minZ, startColor.getRGB()),
                new Vertex(matrix, (float) box.minX, (float) box.minY, (float) box.minZ, endColor.getRGB()),
                new Vertex(matrix, (float) box.minX, (float) box.minY, (float) box.maxZ, endColor.getRGB()),
                new Vertex(matrix, (float) box.minX, (float) box.maxY, (float) box.maxZ, startColor.getRGB())));

        QUADS.add(new VertexCollection(new Vertex(matrix, (float) box.minX, (float) box.minY, (float) box.minZ, endColor.getRGB()),
                new Vertex(matrix, (float) box.maxX, (float) box.minY, (float) box.minZ, endColor.getRGB()),
                new Vertex(matrix, (float) box.maxX, (float) box.minY, (float) box.maxZ, endColor.getRGB()),
                new Vertex(matrix, (float) box.minX, (float) box.minY, (float) box.maxZ, endColor.getRGB())));
    }

    public static void renderBoxOutline(PoseStack matrices, AABB box, Color color) {
        renderGradientBoxOutline(matrices, box, color, color);
    }

    public static void renderGradientBoxOutline(PoseStack matrices, AABB box, Color startColor, Color endColor) {
        renderGradientBoxOutline(DEBUG_LINES, matrices, box, startColor, endColor);
    }

    public static void renderGradientBoxOutline(List<VertexCollection> DEBUG_LINES, PoseStack matrices, AABB box, Color startColor, Color endColor) {
        if (!RENDERING) return;
        if (!isFrustumVisible(box)) return;

        Matrix4f matrix = matrices.last().pose();
        box = cameraTransform(box);

        DEBUG_LINES.add(new VertexCollection(new Vertex(matrix, (float) box.minX, (float) box.minY, (float) box.minZ, endColor.getRGB()),
                new Vertex(matrix, (float) box.minX, (float) box.minY, (float) box.maxZ, endColor.getRGB()),
                new Vertex(matrix, (float) box.minX, (float) box.minY, (float) box.maxZ, endColor.getRGB()),
                new Vertex(matrix, (float) box.maxX, (float) box.minY, (float) box.maxZ, endColor.getRGB())));

        DEBUG_LINES.add(new VertexCollection(new Vertex(matrix, (float) box.maxX, (float) box.minY, (float) box.maxZ, endColor.getRGB()),
                new Vertex(matrix, (float) box.maxX, (float) box.minY, (float) box.minZ, endColor.getRGB()),
                new Vertex(matrix, (float) box.maxX, (float) box.minY, (float) box.minZ, endColor.getRGB()),
                new Vertex(matrix, (float) box.minX, (float) box.minY, (float) box.minZ, endColor.getRGB())));

        DEBUG_LINES.add(new VertexCollection(new Vertex(matrix, (float) box.minX, (float) box.maxY, (float) box.minZ, startColor.getRGB()),
                new Vertex(matrix, (float) box.minX, (float) box.maxY, (float) box.maxZ, startColor.getRGB()),
                new Vertex(matrix, (float) box.minX, (float) box.maxY, (float) box.maxZ, startColor.getRGB()),
                new Vertex(matrix, (float) box.maxX, (float) box.maxY, (float) box.maxZ, startColor.getRGB())));

        DEBUG_LINES.add(new VertexCollection(new Vertex(matrix, (float) box.maxX, (float) box.maxY, (float) box.maxZ, startColor.getRGB()),
                new Vertex(matrix, (float) box.maxX, (float) box.maxY, (float) box.minZ, startColor.getRGB()),
                new Vertex(matrix, (float) box.maxX, (float) box.maxY, (float) box.minZ, startColor.getRGB()),
                new Vertex(matrix, (float) box.minX, (float) box.maxY, (float) box.minZ, startColor.getRGB())));

        DEBUG_LINES.add(new VertexCollection(new Vertex(matrix, (float) box.minX, (float) box.minY, (float) box.minZ, endColor.getRGB()),
                new Vertex(matrix, (float) box.minX, (float) box.maxY, (float) box.minZ, startColor.getRGB()),
                new Vertex(matrix, (float) box.maxX, (float) box.minY, (float) box.minZ, endColor.getRGB()),
                new Vertex(matrix, (float) box.maxX, (float) box.maxY, (float) box.minZ, startColor.getRGB())));

        DEBUG_LINES.add(new VertexCollection(new Vertex(matrix, (float) box.maxX, (float) box.minY, (float) box.maxZ, endColor.getRGB()),
                new Vertex(matrix, (float) box.maxX, (float) box.maxY, (float) box.maxZ, startColor.getRGB()),
                new Vertex(matrix, (float) box.minX, (float) box.minY, (float) box.maxZ, endColor.getRGB()),
                new Vertex(matrix, (float) box.minX, (float) box.maxY, (float) box.maxZ, startColor.getRGB())));
    }

    // ponytail: no GUI-family renderQuad/renderOutline survives for PoseStack callers (the 2D
    // family is GuiGraphicsExtractor-only now) — this is the flat single-quad equivalent for
    // world-space/local-transform callers (nametag borders, floating text backgrounds).
    public static void renderQuad(PoseStack matrices, float left, float top, float right, float bottom, Color color) {
        if (!RENDERING) return;
        Matrix4f matrix = matrices.last().pose();
        int rgb = color.getRGB();

        QUADS.add(new VertexCollection(new Vertex(matrix, left, top, 0, rgb),
                new Vertex(matrix, left, bottom, 0, rgb),
                new Vertex(matrix, right, bottom, 0, rgb),
                new Vertex(matrix, right, top, 0, rgb)));
    }

    public static void renderOutline(PoseStack matrices, float left, float top, float right, float bottom, Color color) {
        if (!RENDERING) return;
        Matrix4f matrix = matrices.last().pose();
        int rgb = color.getRGB();

        DEBUG_LINES.add(new VertexCollection(new Vertex(matrix, left, top, 0, rgb), new Vertex(matrix, right, top, 0, rgb)));
        DEBUG_LINES.add(new VertexCollection(new Vertex(matrix, right, top, 0, rgb), new Vertex(matrix, right, bottom, 0, rgb)));
        DEBUG_LINES.add(new VertexCollection(new Vertex(matrix, right, bottom, 0, rgb), new Vertex(matrix, left, bottom, 0, rgb)));
        DEBUG_LINES.add(new VertexCollection(new Vertex(matrix, left, bottom, 0, rgb), new Vertex(matrix, left, top, 0, rgb)));
    }

    public static void renderLine(PoseStack matrices, Vec3 from, Vec3 to, Color color) {
        Matrix4f matrix = matrices.last().pose();
        from = cameraTransform(from);
        to = cameraTransform(to);

        DEBUG_LINES.add(new VertexCollection(new Vertex(matrix, (float) from.x, (float) from.y, (float) from.z, color.getRGB()),
                new Vertex(matrix, (float) to.x, (float) to.y, (float) to.z, color.getRGB())));
    }

    // PORT: 1.21.4 rendered nametag items immediately -- walk the ItemRenderState's layers, apply
    // each layer's transform, push its baked quads straight into the shared VertexConsumerProvider,
    // then draw(). The public 26.1.2 replacement (ItemStackRenderState.submit) instead *defers* into
    // GameRenderer's SubmitNodeStorage, which only drains inside vanilla's own renderAllFeatures()
    // pass -- a different point in the frame under a different projection. Every attempt to bridge
    // that gap (early-flushing the dispatcher, or bouncing items through the 2D GUI item path) either
    // mispositioned the icons or made them flicker, because the queue's lifetime doesn't line up with
    // our render event. So mirror the original exactly: same layer walk, immediate quads, same
    // ItemFeatureRenderer.renderItem() emit logic, nothing queued.
    private static final QuadInstance QUAD_INSTANCE = new QuadInstance();
    private static final ItemStackRenderState ITEM_RENDER_STATE = new ItemStackRenderState();

    // 1.21.4 wrapped the whole item render block in GL11.glDepthFunc(GL_ALWAYS) so nametag items
    // stayed visible through terrain (same reasoning as NO_DEPTH_QUADS/LINES above). Raw GL calls
    // don't work against this pipeline (depth state is baked per-RenderType, not global GL state,
    // and would get clobbered whenever the buffer actually flushes at endBatch() later) -- so build
    // no-depth clones of the two RenderTypes item quads actually use (vanilla's BakedQuad.MaterialInfo
    // only ever resolves to one of Sheets.cutout/translucentItemSheet(), both backed by ITEM_CUTOUT/
    // ITEM_TRANSLUCENT), keyed by atlas texture like vanilla's own memoized item RenderTypes are.
    private static final Map<Identifier, RenderType> NO_DEPTH_ITEM_CUTOUT = new HashMap<>();
    private static final Map<Identifier, RenderType> NO_DEPTH_ITEM_TRANSLUCENT = new HashMap<>();

    private static RenderType noDepthItemRenderType(BakedQuad.MaterialInfo material) {
        boolean translucent = material.layer().translucent();
        Identifier texture = material.sprite().atlasLocation();
        Map<Identifier, RenderType> cache = translucent ? NO_DEPTH_ITEM_TRANSLUCENT : NO_DEPTH_ITEM_CUTOUT;

        return cache.computeIfAbsent(texture, t -> {
            RenderPipeline.Builder pipelineBuilder = RenderPipeline.builder(new RenderPipeline.Snippet[]{RenderPipelinesAccessor.getItemSnippet()})
                    .withLocation(translucent ? "euclient/no_depth_item_translucent" : "euclient/no_depth_item_cutout")
                    .withShaderDefine("ALPHA_CUTOUT", 0.1F)
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false));
            if (translucent) pipelineBuilder.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT));

            // PORT (26.2): real crash caught via runtime test (Vulkan backend, "Missing sampler
            // Sampler1") -- RenderPipelines.ITEM_SNIPPET declares BindGroupLayouts.
            // SAMPLER0_SAMPLER1_SAMPLER2 (confirmed via real source): Sampler0=atlas texture,
            // Sampler1=overlay, Sampler2=lightmap. Only .useLightmap() (Sampler2) was called even
            // though QUAD_INSTANCE.setOverlayCoords(...) above already writes per-vertex overlay
            // UVs expecting a bound overlay texture -- OpenGL silently tolerated the unbound
            // sampler, Vulkan's descriptor set validation does not.
            return RenderType.create(translucent ? "euclient_no_depth_item_translucent" : "euclient_no_depth_item_cutout",
                    RenderSetup.builder(pipelineBuilder.build())
                            .withTexture("Sampler0", t)
                            .useOverlay()
                            .useLightmap()
                            .affectsCrumbling()
                            .setOutline(OutlineProperty.AFFECTS_OUTLINE)
                            .createRenderSetup());
        });
    }

    // PORT (26.2): MultiBufferSource removed -- own a per-RenderType BufferBuilder here (growable
    // ByteBufferBuilder, quad count per RenderType isn't known upfront) and draw immediately once
    // the whole item's quads are queued, same immediate-mode pattern as FontManager.drawTextWithShadow
    // (see its PORT comment). No shared-buffer sort-order concern anymore since each item draws its
    // own RenderTypes independently -- the old comment about NOT flushing per-item was about a
    // frame-shared MultiBufferSource that no longer exists.
    private static BufferBuilder itemBuffer(Map<RenderType, ByteBufferBuilder> owners, Map<RenderType, BufferBuilder> buffers, RenderType renderType) {
        return buffers.computeIfAbsent(renderType, rt -> {
            ByteBufferBuilder byteBufferBuilder = new ByteBufferBuilder(256);
            owners.put(rt, byteBufferBuilder);
            return new BufferBuilder(byteBufferBuilder, rt.primitiveTopology(), rt.format());
        });
    }

    public static void renderItem(PoseStack matrices, ItemStack stack, ItemOwner owner) {
        if (!RENDERING || stack.isEmpty()) return;

        ITEM_RENDER_STATE.clear();
        mc.getItemModelResolver().updateForTopItem(ITEM_RENDER_STATE, stack, ItemDisplayContext.GUI, mc.level, owner, 0);

        ItemStackRenderStateAccessor stateAccessor = (ItemStackRenderStateAccessor) ITEM_RENDER_STATE;
        QUAD_INSTANCE.setLightCoords(LightCoordsUtil.FULL_BRIGHT);
        QUAD_INSTANCE.setOverlayCoords(OverlayTexture.NO_OVERLAY);

        Map<RenderType, ByteBufferBuilder> owners = new HashMap<>();
        Map<RenderType, BufferBuilder> buffers = new HashMap<>();
        try {
            for (int i = 0; i < stateAccessor.euclient$getActiveLayerCount(); i++) {
                LayerRenderStateAccessor layer = (LayerRenderStateAccessor) stateAccessor.euclient$getLayers()[i];

                matrices.pushPose();
                layer.euclient$applyTransform(matrices.last());

                IntList tints = layer.euclient$getTintLayers();
                int[] tintLayers = tints != null ? tints.toArray(new int[0]) : new int[0];
                boolean hasFoil = layer.euclient$getFoilType() != ItemStackRenderState.FoilType.NONE;

                for (BakedQuad quad : layer.euclient$getQuads()) {
                    BakedQuad.MaterialInfo material = quad.materialInfo();
                    RenderType renderType = noDepthItemRenderType(material);

                    int tintIndex = material.isTinted() ? material.tintIndex() : -1;
                    QUAD_INSTANCE.setColor(tintIndex >= 0 && tintIndex < tintLayers.length ? tintLayers[tintIndex] : -1);

                    if (hasFoil) {
                        // PORT (26.2): ItemFeatureRenderer.getFoilRenderType (a static helper) is
                        // gone -- real 26.2 source moved this to a private instance method
                        // (getFoilBuffer + useTransparentGlint), inlined the same logic here since
                        // it's not accessible.
                        boolean useTransparentGlint = mc.gameRenderer.gameRenderState().useShaderTransparency()
                                && renderType.outputTarget() == net.minecraft.client.renderer.rendertype.OutputTarget.ITEM_ENTITY_TARGET;
                        RenderType foilRenderType = useTransparentGlint ? RenderTypes.glintTranslucent() : RenderTypes.glint();
                        itemBuffer(owners, buffers, foilRenderType).putBakedQuad(matrices.last(), quad, QUAD_INSTANCE);
                    }

                    itemBuffer(owners, buffers, renderType).putBakedQuad(matrices.last(), quad, QUAD_INSTANCE);
                }

                matrices.popPose();
            }

            for (Map.Entry<RenderType, BufferBuilder> entry : buffers.entrySet()) {
                MeshData mesh = entry.getValue().build();
                draw(entry.getKey(), mesh);
            }
        } finally {
            for (ByteBufferBuilder byteBufferBuilder : owners.values()) byteBufferBuilder.close();
        }
    }

    public static void renderScaledText(PoseStack matrices, String text, double x, double y, double z, int scale, boolean background, Color color) {
        Vec3 cam = mc.gameRenderer.mainCamera().position();
        float distance = (float) Math.sqrt(cam.distanceToSqr(x, y, z));
        float scaling = 0.0018f + (scale / 10000.0f) * distance;
        if (distance <= 8.0) scaling = 0.0245f;

        renderText(matrices, text, x, y, z, scaling, background, color);
    }

    public static void renderText(PoseStack matrices, String text, double x, double y, double z, float scaling, boolean background, Color color) {
        Vec3 cam = mc.gameRenderer.mainCamera().position();
        Vec3 vec3d = new Vec3(x - cam.x, y - cam.y, z - cam.z);

        matrices.pushPose();
        matrices.translate(vec3d.x, vec3d.y, vec3d.z);
        matrices.mulPose(mc.gameRenderer.mainCamera().rotation());
        matrices.scale(scaling, -scaling, scaling);

        if (background) Renderer3D.renderQuad(matrices, -EUClient.FONT_MANAGER.getWidth(text) / 2.0f - 2, -EUClient.FONT_MANAGER.getHeight() - 2, EUClient.FONT_MANAGER.getWidth(text) / 2.0f + 2, 1, new Color(0, 0, 0, 100));
        EUClient.FONT_MANAGER.drawTextWithShadow(matrices, text, -EUClient.FONT_MANAGER.getWidth(text) / 2, -EUClient.FONT_MANAGER.getHeight(), color);

        matrices.popPose();
    }

    public static void prepare() {
        QUADS = new ArrayList<>();
        DEBUG_LINES = new ArrayList<>();

        SHINE_QUADS = new ArrayList<>();
        SHINE_DEBUG_LINES = new ArrayList<>();

        SHADER_QUADS = new ArrayList<>();
        SHADER_DEBUG_LINES = new ArrayList<>();

        RENDERING = true;
    }

    public static void draw(List<VertexCollection> quads, List<VertexCollection> debugLines, boolean shine) {
        // PORT (26.1.2): GlStateManager / ShaderProgramKeys / BufferRenderer.drawWithGlobalProgram
        // are gone. Blend state now lives on the RenderPipeline; we submit meshes directly through
        // the debug RenderTypes (debugQuads = POSITION_COLOR/QUADS, lines() = line pipeline).
        // PORT (26.2): Tesselator (the auto-growing singleton buffer) is REMOVED -- vanilla's own
        // per-frame-dynamic drawers (WeatherEffectRenderer) now own a fresh ByteBufferBuilder sized
        // EXACTLY for the frame's vertex count, build, use, close. Confirmed per-frame realloc is
        // the sanctioned pattern, not a perf concern (cross-checked against Meteor Client's real
        // 26.2 port too) -- see docs/superpowers/specs/2026-08-20-port-26.2-vulkan-audit.md.
        if (!quads.isEmpty()) {
            int vertexCount = vertexCount(quads);
            try (ByteBufferBuilder byteBufferBuilder = ByteBufferBuilder.exactlySized(vertexCount * DefaultVertexFormat.POSITION_COLOR.getVertexSize())) {
                BufferBuilder buffer = new BufferBuilder(byteBufferBuilder, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_COLOR);
                for (VertexCollection collection : quads) collection.quad(buffer);

                MeshData mesh = buffer.build();
                draw(shine ? NO_DEPTH_QUADS_SHINE : NO_DEPTH_QUADS, mesh);
            }
        }

        if (!debugLines.isEmpty()) {
            // lines() expects POSITION_COLOR_NORMAL_LINE_WIDTH in LINES mode; vertices come in pairs.
            // PORT (26.2): real crash caught via runtime test -- BufferBuilder.endLastVertex()
            // (confirmed via real source) DUPLICATES every LINES-topology vertex into 2 physical
            // slots (the wide-line-quad trick a vertex shader extrudes), so the actual byte count
            // needed is 2x the raw endpoint count, not 1x. exactlySized undersizing by half threw
            // "Maximum capacity ... exceeded" as soon as any debug line was actually drawn.
            int vertexCount = vertexCount(debugLines) * 2;

            // PORT (26.2): dropped GL11.glHint(GL_LINE_SMOOTH_HINT)/glEnable(GL_LINE_SMOOTH) --
            // fixed-function GL2 line smoothing, meaningless (and a raw-GL crash risk under
            // Options.PreferredGraphicsApi.VULKAN, no bound GL context to call into) now that lines
            // are POSITION_COLOR_NORMAL_LINE_WIDTH quads a vertex shader extrudes -- smoothing is
            // whatever that shader/pipeline does, not this legacy toggle.
            try (ByteBufferBuilder byteBufferBuilder = ByteBufferBuilder.exactlySized(vertexCount * DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH.getVertexSize())) {
                BufferBuilder buffer = new BufferBuilder(byteBufferBuilder, PrimitiveTopology.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH);
                buildLines(buffer, debugLines);

                MeshData mesh = buffer.build();
                draw(shine ? NO_DEPTH_LINES_SHINE : NO_DEPTH_LINES, mesh);
            }
        }

        RenderSystem.getDevice();

        // draw() is called twice per frame now (once before RenderWorldEvent, once after --
        // see GameRendererMixin) so Post-phase callers (e.g. NameTagsModule's border, which needs
        // to run after text width is known) still get drawn this frame instead of being silently
        // dropped when prepare() wipes the list at the start of the next frame. Clear after each
        // pass so the first draw() call doesn't repaint stale entries during the second.
        quads.clear();
        debugLines.clear();
    }

    // ByteBufferBuilder.exactlySized needs the true vertex count up front (26.2 has no auto-growing
    // Tesselator to fall back on) -- sum each collection's own vertex count rather than assuming a
    // fixed arity per collection.
    static int vertexCount(List<VertexCollection> collections) {
        int count = 0;
        for (VertexCollection collection : collections) count += collection.vertices().length;
        return count;
    }

    // lines() expects POSITION_COLOR_NORMAL_LINE_WIDTH in LINES mode; vertices come in pairs and the
    // normal has to be the segment direction (the vertex shader extrudes the quad along it).
    public static void buildLines(BufferBuilder buffer, List<VertexCollection> debugLines) {
        List<Vertex> flat = new ArrayList<>();
        for (VertexCollection collection : debugLines) java.util.Collections.addAll(flat, collection.vertices());

        for (int i = 0; i + 1 < flat.size(); i += 2) {
            Vertex a = flat.get(i);
            Vertex b = flat.get(i + 1);
            Vector3f normal = new Vector3f(b.x - a.x, b.y - a.y, b.z - a.z);
            if (normal.lengthSquared() > 1.0e-6f) normal.normalize();
            else normal.set(0.0f, 1.0f, 0.0f);
            buffer.addVertex(a.matrix, a.x, a.y, a.z).setColor(a.color).setNormal(normal.x, normal.y, normal.z).setLineWidth(1.0f);
            buffer.addVertex(b.matrix, b.x, b.y, b.z).setColor(b.color).setNormal(normal.x, normal.y, normal.z).setLineWidth(1.0f);
        }
    }

    public static boolean isFrustumVisible(AABB box) {
        return mc.gameRenderer.mainCamera().getCullFrustum().isVisible(box);
    }

    private static Vec3 cameraTransform(Vec3 vec3d) {
        Vec3 camera = mc.gameRenderer.mainCamera().position();
        return new Vec3(vec3d.x - camera.x, vec3d.y - camera.y, vec3d.z - camera.z);
    }

    private static AABB cameraTransform(AABB box) {
        Vec3 camera = mc.gameRenderer.mainCamera().position();
        return new AABB(box.minX - camera.x, box.minY - camera.y, box.minZ - camera.z, box.maxX - camera.x, box.maxY - camera.y, box.maxZ - camera.z);
    }

    public record VertexCollection(Vertex... vertices) {
        public void quad(BufferBuilder buffer) {
            for (Vertex vertex : vertices) buffer.addVertex(vertex.matrix, vertex.x, vertex.y, vertex.z).setColor(vertex.color);
        }
    }

    public record Vertex(Matrix4f matrix, float x, float y, float z, int color) { }
}
