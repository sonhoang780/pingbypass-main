package eu.client.utils.graphics;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import eu.client.utils.IMinecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Two families of primitives live here after the 1.21.5+ render overhaul:
 *
 * <ul>
 *   <li><b>2D GUI</b> ({@link #renderQuad}, {@link #renderOutline}, {@link #renderGradient},
 *       {@link #renderSidewaysGradient}) — screen-space, submitted to the retained-mode
 *       {@link GuiGraphicsExtractor} via {@link GuiQuadRenderState}. The {@code GuiRenderer}
 *       batches every element through a single QUADS index buffer, so all GUI geometry is quads.</li>
 *   <li><b>World / HUD</b> ({@link #renderCircle}, {@link #renderTexture}, {@link #renderArrow},
 *       {@link #renderArrowOutline}) — take a real 3D {@link PoseStack} and draw immediately with
 *       {@link RenderType#draw(MeshData)}, which reads the active model-view/projection set up by
 *       the world-render mixin.</li>
 * </ul>
 */
public class Renderer2D implements IMinecraft {
    public static final Matrix4f LAST_PROJECTION_MATRIX = new Matrix4f();
    public static final Matrix4f LAST_MODEL_MATRIX = new Matrix4f();
    public static final Matrix4f LAST_WORLD_MATRIX = new Matrix4f();

    /** Cached per-texture render types for {@link #renderTexture}. */
    private static final Map<Identifier, RenderType> TEXTURE_TYPES = new HashMap<>();

    // ------------------------------------------------------------------
    // 2D GUI (retained-mode) primitives
    // ------------------------------------------------------------------

    public static void renderQuad(GuiGraphicsExtractor context, float left, float top, float right, float bottom, Color color) {
        int c = color.getRGB();
        submit(context,
                new float[]{left, left, right, right},
                new float[]{top, bottom, bottom, top},
                new int[]{c, c, c, c});
    }

    public static void renderGradient(GuiGraphicsExtractor context, float left, float top, float right, float bottom, Color startColor, Color endColor) {
        int s = startColor.getRGB();
        int e = endColor.getRGB();
        // vertical: top edge = start, bottom edge = end
        submit(context,
                new float[]{left, left, right, right},
                new float[]{top, bottom, bottom, top},
                new int[]{s, e, e, s});
    }

    public static void renderSidewaysGradient(GuiGraphicsExtractor context, float left, float top, float right, float bottom, Color startColor, Color endColor) {
        int s = startColor.getRGB();
        int e = endColor.getRGB();
        // horizontal: left edge = start, right edge = end
        submit(context,
                new float[]{left, left, right, right},
                new float[]{top, bottom, bottom, top},
                new int[]{s, s, e, e});
    }

    public static void renderOutline(GuiGraphicsExtractor context, float left, float top, float right, float bottom, Color color) {
        int c = color.getRGB();
        float[] xs = new float[16];
        float[] ys = new float[16];
        int[] cols = new int[16];
        quad(xs, ys, cols, 0, left, top, left + 0.5f, bottom, c);       // left edge
        quad(xs, ys, cols, 1, right - 0.5f, top, right, bottom, c);     // right edge
        quad(xs, ys, cols, 2, left, bottom - 0.5f, right, bottom, c);   // bottom edge
        quad(xs, ys, cols, 3, left, top, right, top + 0.5f, c);         // top edge
        submit(context, xs, ys, cols);
    }

    private static void submit(GuiGraphicsExtractor context, float[] xs, float[] ys, int[] cols) {
        context.guiRenderState.addGuiElement(new GuiQuadRenderState(
                new Matrix3x2f(context.pose()), xs, ys, cols, context.scissorStack.peek()));
    }

    /** Writes one axis-aligned quad (TL, BL, BR, TR winding) into the vertex arrays at quad slot {@code qi}. */
    private static void quad(float[] xs, float[] ys, int[] cols, int qi, float x0, float y0, float x1, float y1, int c) {
        int b = qi * 4;
        xs[b] = x0;     ys[b] = y0;         // top-left
        xs[b + 1] = x0; ys[b + 1] = y1;     // bottom-left
        xs[b + 2] = x1; ys[b + 2] = y1;     // bottom-right
        xs[b + 3] = x1; ys[b + 3] = y0;     // top-right
        cols[b] = cols[b + 1] = cols[b + 2] = cols[b + 3] = c;
    }

    // ------------------------------------------------------------------
    // World / HUD (immediate-mode) primitives
    // ------------------------------------------------------------------

    public static void renderCircle(PoseStack matrices, float x, float y, float radius, Color color) {
        Matrix4f matrix = matrices.last().pose();
        int c = color.getRGB();
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i <= 360; ++i) {
            buffer.addVertex(matrix,
                    (float) (x + Math.sin((double) i * Math.PI / 180.0) * (double) radius),
                    (float) (y + Math.cos((double) i * Math.PI / 180.0) * (double) radius), 0.0f).setColor(c);
        }
        MeshData mesh = buffer.build();
        if (mesh != null) RenderTypes.debugTriangleFan().draw(mesh);
    }

    public static void renderTexture(PoseStack matrices, float left, float top, float right, float bottom, Identifier identifier, Color color) {
        Matrix4f matrix = matrices.last().pose();
        int c = color.getRGB();
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        buffer.addVertex(matrix, left, top, 0.0f).setUv(0, 0).setColor(c);
        buffer.addVertex(matrix, left, bottom, 0.0f).setUv(0, 1).setColor(c);
        buffer.addVertex(matrix, right, bottom, 0.0f).setUv(1, 1).setColor(c);
        buffer.addVertex(matrix, right, top, 0.0f).setUv(1, 0).setColor(c);
        MeshData mesh = buffer.build();
        if (mesh != null) texturedType(identifier).draw(mesh);
    }

    public static void renderArrow(PoseStack matrices, float x, float y, float width, float height, Color color) {
        Matrix4f matrix = matrices.last().pose();
        int c = color.getRGB();
        // Filled arrowhead: fan out from the tip.
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        buffer.addVertex(matrix, x, y, 0.0f).setColor(c);
        buffer.addVertex(matrix, x - width, y + height, 0.0f).setColor(c);
        buffer.addVertex(matrix, x, y + height, 0.0f).setColor(c);
        buffer.addVertex(matrix, x + width, y + height, 0.0f).setColor(c);
        buffer.addVertex(matrix, x, y, 0.0f).setColor(c);
        MeshData mesh = buffer.build();
        if (mesh != null) RenderTypes.debugTriangleFan().draw(mesh);
    }

    public static void renderArrowOutline(PoseStack matrices, float x, float y, float width, float height, Color color) {
        Matrix4f matrix = matrices.last().pose();
        int c = color.getRGB();
        // Outline of the arrowhead, drawn as thin quads along each edge of the strip.
        float[] px = {x, x - width, x, x + width, x};
        float[] py = {y, y + height, y + height, y + height, y};
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < px.length - 1; i++) {
            segment(buffer, matrix, px[i], py[i], px[i + 1], py[i + 1], 0.5f, c);
        }
        MeshData mesh = buffer.build();
        if (mesh != null) RenderTypes.debugQuads().draw(mesh);
    }

    /** Emits a thin quad approximating the line segment (x1,y1)->(x2,y2) with half-width {@code w}. */
    private static void segment(BufferBuilder buffer, Matrix4f matrix, float x1, float y1, float x2, float y2, float w, int c) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len == 0.0f) return;
        float nx = -dy / len * w;
        float ny = dx / len * w;
        buffer.addVertex(matrix, x1 + nx, y1 + ny, 0.0f).setColor(c);
        buffer.addVertex(matrix, x1 - nx, y1 - ny, 0.0f).setColor(c);
        buffer.addVertex(matrix, x2 - nx, y2 - ny, 0.0f).setColor(c);
        buffer.addVertex(matrix, x2 + nx, y2 + ny, 0.0f).setColor(c);
    }

    private static RenderType texturedType(Identifier identifier) {
        return TEXTURE_TYPES.computeIfAbsent(identifier, id -> RenderType.create(
                "euclient_textured_" + id,
                RenderSetup.builder(RenderPipelines.GUI_TEXTURED).withTexture("Sampler0", id).createRenderSetup()));
    }

    // ------------------------------------------------------------------

    public static Vec3 project(Vec3 vec3d) {
        Vec3 camera = mc.getEntityRenderDispatcher().camera.position();
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);

        Vector3f target = new Vector3f();
        Vector4f transform = new Vector4f((float) (vec3d.x - camera.x), (float) (vec3d.y - camera.y), (float) (vec3d.z - camera.z), 1.0f).mul(LAST_WORLD_MATRIX);

        Matrix4f matrixProj = new Matrix4f(LAST_PROJECTION_MATRIX);
        Matrix4f matrixModel = new Matrix4f(LAST_MODEL_MATRIX);

        matrixProj.mul(matrixModel).project(transform.x(), transform.y(), transform.z(), viewport, target);

        return new Vec3(target.x / mc.getWindow().getGuiScale(), (mc.getWindow().getHeight() - target.y) / mc.getWindow().getGuiScale(), target.z);
    }
}
