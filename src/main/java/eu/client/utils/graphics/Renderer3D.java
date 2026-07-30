package eu.client.utils.graphics;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import eu.client.EUClient;
import eu.client.utils.IMinecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class Renderer3D implements IMinecraft {
    public static boolean RENDERING = false;

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
            if (mesh != null) RenderTypes.debugQuads().draw(mesh);
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
            if (mesh != null) RenderTypes.lines().draw(mesh);

            GL11.glDisable(GL11.GL_LINE_SMOOTH);
        }

        RenderSystem.getDevice();
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
