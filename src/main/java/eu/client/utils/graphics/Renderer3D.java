package eu.client.utils.graphics;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import eu.client.EUClient;
import eu.client.mixins.accessors.ItemStackRenderStateAccessor;
import eu.client.mixins.accessors.LayerRenderStateAccessor;
import eu.client.mixins.accessors.RenderPipelinesAccessor;
import eu.client.utils.IMinecraft;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderSetup.OutlineProperty;
import net.minecraft.client.renderer.rendertype.RenderType;
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
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Renderer3D implements IMinecraft {
    public static boolean RENDERING = false;

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

    public static List<VertexCollection> QUADS = new ArrayList<>();
    public static List<VertexCollection> DEBUG_LINES = new ArrayList<>();

    public static List<VertexCollection> SHINE_QUADS = new ArrayList<>();
    public static List<VertexCollection> SHINE_DEBUG_LINES = new ArrayList<>();

    public static void renderBox(PoseStack matrices, AABB box, Color color) {
        renderGradientBox(matrices, box, color, color);
    }

    public static void renderGradientBox(PoseStack matrices, AABB box, Color startColor, Color endColor) {
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

            return RenderType.create(translucent ? "euclient_no_depth_item_translucent" : "euclient_no_depth_item_cutout",
                    RenderSetup.builder(pipelineBuilder.build())
                            .withTexture("Sampler0", t)
                            .useLightmap()
                            .affectsCrumbling()
                            .setOutline(OutlineProperty.AFFECTS_OUTLINE)
                            .createRenderSetup());
        });
    }

    public static void renderItem(PoseStack matrices, ItemStack stack, ItemOwner owner, MultiBufferSource.BufferSource vertexConsumers) {
        if (!RENDERING || stack.isEmpty()) return;

        ITEM_RENDER_STATE.clear();
        mc.getItemModelResolver().updateForTopItem(ITEM_RENDER_STATE, stack, ItemDisplayContext.GUI, mc.level, owner, 0);

        ItemStackRenderStateAccessor stateAccessor = (ItemStackRenderStateAccessor) ITEM_RENDER_STATE;
        QUAD_INSTANCE.setLightCoords(LightCoordsUtil.FULL_BRIGHT);
        QUAD_INSTANCE.setOverlayCoords(OverlayTexture.NO_OVERLAY);

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
                    vertexConsumers.getBuffer(ItemFeatureRenderer.getFoilRenderType(renderType, true)).putBakedQuad(matrices.last(), quad, QUAD_INSTANCE);
                }

                vertexConsumers.getBuffer(renderType).putBakedQuad(matrices.last(), quad, QUAD_INSTANCE);
            }

            matrices.popPose();
        }

        // Do NOT endBatch() here -- this runs once per queued item. The foil/glint render types are
        // translucent and depend on being sorted against the rest of the frame's translucent geometry;
        // flushing them piecemeal per item (a different subset each call) made the sort order flicker
        // frame to frame. GameRendererMixin.renderWorld$swap already does a single endBatch() once all
        // nametags for the frame have been queued -- let that flush everything together, same as text.
    }

    public static void renderScaledText(PoseStack matrices, String text, double x, double y, double z, int scale, boolean background, Color color) {
        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        float distance = (float) Math.sqrt(cam.distanceToSqr(x, y, z));
        float scaling = 0.0018f + (scale / 10000.0f) * distance;
        if (distance <= 8.0) scaling = 0.0245f;

        renderText(matrices, text, x, y, z, scaling, background, color);
    }

    public static void renderText(PoseStack matrices, String text, double x, double y, double z, float scaling, boolean background, Color color) {
        MultiBufferSource.BufferSource vertexConsumers = mc.renderBuffers().bufferSource();

        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        Vec3 vec3d = new Vec3(x - cam.x, y - cam.y, z - cam.z);

        matrices.pushPose();
        matrices.translate(vec3d.x, vec3d.y, vec3d.z);
        matrices.mulPose(mc.gameRenderer.getMainCamera().rotation());
        matrices.scale(scaling, -scaling, scaling);

        if (background) Renderer3D.renderQuad(matrices, -EUClient.FONT_MANAGER.getWidth(text) / 2.0f - 2, -EUClient.FONT_MANAGER.getHeight() - 2, EUClient.FONT_MANAGER.getWidth(text) / 2.0f + 2, 1, new Color(0, 0, 0, 100));
        EUClient.FONT_MANAGER.drawTextWithShadow(matrices, text, -EUClient.FONT_MANAGER.getWidth(text) / 2, -EUClient.FONT_MANAGER.getHeight(), vertexConsumers, color);

        matrices.popPose();
    }

    public static void prepare() {
        QUADS = new ArrayList<>();
        DEBUG_LINES = new ArrayList<>();

        SHINE_QUADS = new ArrayList<>();
        SHINE_DEBUG_LINES = new ArrayList<>();

        RENDERING = true;
    }

    public static void draw(List<VertexCollection> quads, List<VertexCollection> debugLines, boolean shine) {
        // PORT (26.1.2): GlStateManager / ShaderProgramKeys / BufferRenderer.drawWithGlobalProgram
        // are gone. Blend state now lives on the RenderPipeline; we submit meshes directly through
        // the debug RenderTypes (debugQuads = POSITION_COLOR/QUADS, lines() = line pipeline).
        if (!quads.isEmpty()) {
            BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (VertexCollection collection : quads) collection.quad(buffer);

            MeshData mesh = buffer.build();
            if (mesh != null) NO_DEPTH_QUADS.draw(mesh);
        }

        if (!debugLines.isEmpty()) {
            // lines() expects POSITION_COLOR_NORMAL_LINE_WIDTH in LINES mode; vertices come in pairs.
            BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH);
            List<Vertex> flat = new ArrayList<>();
            for (VertexCollection collection : debugLines) java.util.Collections.addAll(flat, collection.vertices());

            GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
            GL11.glEnable(GL11.GL_LINE_SMOOTH);

            for (int i = 0; i + 1 < flat.size(); i += 2) {
                Vertex a = flat.get(i);
                Vertex b = flat.get(i + 1);
                Vector3f normal = new Vector3f(b.x - a.x, b.y - a.y, b.z - a.z);
                if (normal.lengthSquared() > 1.0e-6f) normal.normalize();
                else normal.set(0.0f, 1.0f, 0.0f);
                buffer.addVertex(a.matrix, a.x, a.y, a.z).setColor(a.color).setNormal(normal.x, normal.y, normal.z).setLineWidth(1.0f);
                buffer.addVertex(b.matrix, b.x, b.y, b.z).setColor(b.color).setNormal(normal.x, normal.y, normal.z).setLineWidth(1.0f);
            }

            MeshData mesh = buffer.build();
            if (mesh != null) NO_DEPTH_LINES.draw(mesh);

            GL11.glDisable(GL11.GL_LINE_SMOOTH);
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

    public static boolean isFrustumVisible(AABB box) {
        return mc.gameRenderer.getMainCamera().getCullFrustum().isVisible(box);
    }

    private static Vec3 cameraTransform(Vec3 vec3d) {
        Vec3 camera = mc.gameRenderer.getMainCamera().position();
        return new Vec3(vec3d.x - camera.x, vec3d.y - camera.y, vec3d.z - camera.z);
    }

    private static AABB cameraTransform(AABB box) {
        Vec3 camera = mc.gameRenderer.getMainCamera().position();
        return new AABB(box.minX - camera.x, box.minY - camera.y, box.minZ - camera.z, box.maxX - camera.x, box.maxY - camera.y, box.maxZ - camera.z);
    }

    public record VertexCollection(Vertex... vertices) {
        public void quad(BufferBuilder buffer) {
            for (Vertex vertex : vertices) buffer.addVertex(vertex.matrix, vertex.x, vertex.y, vertex.z).setColor(vertex.color);
        }
    }

    public record Vertex(Matrix4f matrix, float x, float y, float z, int color) { }
}
