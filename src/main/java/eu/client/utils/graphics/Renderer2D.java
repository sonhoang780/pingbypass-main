package eu.client.utils.graphics;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
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
 *       {@link Renderer3D#draw(RenderType, MeshData)}, which reads the active model-view/projection set up by
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

    // Immediate-mode counterpart to renderQuad() -- that one is retained (queues state for
    // GuiRenderer to batch-flush later in the frame), which is normally exactly what you want,
    // but is unusable for anything that has to land in a scratch RenderTarget THIS instant (e.g.
    // CozyGlowCapture's blur-capture window) since the override that redirects output would
    // already be cleared by the time a retained draw actually flushes.
    // PORT (26.2): Tesselator removed -- own a ByteBufferBuilder sized exactly for the fixed vertex
    // count each of these emits (no auto-growing singleton to lean on anymore). See Renderer3D.draw's
    // comment for the vanilla-confirmed reasoning (WeatherEffectRenderer does the same per-call).
    public static void renderImmediateQuad(PoseStack matrices, float left, float top, float right, float bottom, Color color) {
        Matrix4f matrix = matrices.last().pose();
        int c = color.getRGB();
        try (ByteBufferBuilder byteBufferBuilder = ByteBufferBuilder.exactlySized(4 * DefaultVertexFormat.POSITION_COLOR.getVertexSize())) {
            BufferBuilder buffer = new BufferBuilder(byteBufferBuilder, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_COLOR);
            buffer.addVertex(matrix, left, top, 0.0f).setColor(c);
            buffer.addVertex(matrix, left, bottom, 0.0f).setColor(c);
            buffer.addVertex(matrix, right, bottom, 0.0f).setColor(c);
            buffer.addVertex(matrix, right, top, 0.0f).setColor(c);
            MeshData mesh = buffer.build();
            Renderer3D.draw(RenderTypes.debugQuads(), mesh);
        }
    }

    public static void renderCircle(PoseStack matrices, float x, float y, float radius, Color color) {
        Matrix4f matrix = matrices.last().pose();
        int c = color.getRGB();
        try (ByteBufferBuilder byteBufferBuilder = ByteBufferBuilder.exactlySized(361 * DefaultVertexFormat.POSITION_COLOR.getVertexSize())) {
            BufferBuilder buffer = new BufferBuilder(byteBufferBuilder, PrimitiveTopology.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
            for (int i = 0; i <= 360; ++i) {
                buffer.addVertex(matrix,
                        (float) (x + Math.sin((double) i * Math.PI / 180.0) * (double) radius),
                        (float) (y + Math.cos((double) i * Math.PI / 180.0) * (double) radius), 0.0f).setColor(c);
            }
            MeshData mesh = buffer.build();
            Renderer3D.draw(RenderTypes.debugTriangleFan(), mesh);
        }
    }

    public static void renderTexture(PoseStack matrices, float left, float top, float right, float bottom, Identifier identifier, Color color) {
        Matrix4f matrix = matrices.last().pose();
        int c = color.getRGB();
        try (ByteBufferBuilder byteBufferBuilder = ByteBufferBuilder.exactlySized(4 * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize())) {
            BufferBuilder buffer = new BufferBuilder(byteBufferBuilder, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            buffer.addVertex(matrix, left, top, 0.0f).setUv(0, 0).setColor(c);
            buffer.addVertex(matrix, left, bottom, 0.0f).setUv(0, 1).setColor(c);
            buffer.addVertex(matrix, right, bottom, 0.0f).setUv(1, 1).setColor(c);
            buffer.addVertex(matrix, right, top, 0.0f).setUv(1, 0).setColor(c);
            MeshData mesh = buffer.build();
            Renderer3D.draw(texturedType(identifier), mesh);
        }
    }

    public static void renderArrow(GuiGraphicsExtractor context, float x, float y, float width, float height, Color color) {
        int c = color.getRGB();
        float notchY = y + height * 0.75f;
        // Two triangles forming an arrowhead
        float[] xs = new float[]{x, x - width, x, x,   x, x, x + width, x};
        float[] ys = new float[]{y, y + height, notchY, y,   y, notchY, y + height, y};
        int[] cols = new int[]{c, c, c, c,   c, c, c, c};
        submit(context, xs, ys, cols);
    }

    public static void renderArrowOutline(GuiGraphicsExtractor context, float x, float y, float width, float height, Color color) {
        int c = color.getRGB();
        float notchY = y + height * 0.75f;
        float[] xs = new float[16];
        float[] ys = new float[16];
        int[] cols = new int[16];
        quadLine(xs, ys, cols, 0, x, y, x - width, y + height, 0.5f, c);
        quadLine(xs, ys, cols, 1, x - width, y + height, x, notchY, 0.5f, c);
        quadLine(xs, ys, cols, 2, x, notchY, x + width, y + height, 0.5f, c);
        quadLine(xs, ys, cols, 3, x + width, y + height, x, y, 0.5f, c);
        submit(context, xs, ys, cols);
    }

    private static void quadLine(float[] xs, float[] ys, int[] cols, int qi, float x1, float y1, float x2, float y2, float halfWidth, int c) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-4f) return;
        float nx = -dy / len * halfWidth;
        float ny = dx / len * halfWidth;
        int b = qi * 4;
        xs[b] = x1 + nx;     ys[b] = y1 + ny;
        xs[b + 1] = x1 - nx; ys[b + 1] = y1 - ny;
        xs[b + 2] = x2 - nx; ys[b + 2] = y2 - ny;
        xs[b + 3] = x2 + nx; ys[b + 3] = y2 + ny;
        cols[b] = cols[b + 1] = cols[b + 2] = cols[b + 3] = c;
    }

    public static void renderArrow(PoseStack matrices, float x, float y, float width, float height, Color color) {
        Matrix4f matrix = matrices.last().pose();
        int c = color.getRGB();
        // Filled arrowhead: fan out from the tip.
        try (ByteBufferBuilder byteBufferBuilder = ByteBufferBuilder.exactlySized(5 * DefaultVertexFormat.POSITION_COLOR.getVertexSize())) {
            BufferBuilder buffer = new BufferBuilder(byteBufferBuilder, PrimitiveTopology.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
            buffer.addVertex(matrix, x, y, 0.0f).setColor(c);
            buffer.addVertex(matrix, x - width, y + height, 0.0f).setColor(c);
            buffer.addVertex(matrix, x, y + height, 0.0f).setColor(c);
            buffer.addVertex(matrix, x + width, y + height, 0.0f).setColor(c);
            buffer.addVertex(matrix, x, y, 0.0f).setColor(c);
            MeshData mesh = buffer.build();
            Renderer3D.draw(RenderTypes.debugTriangleFan(), mesh);
        }
    }

    public static void renderArrowOutline(PoseStack matrices, float x, float y, float width, float height, Color color) {
        Matrix4f matrix = matrices.last().pose();
        int c = color.getRGB();
        // Outline of the arrowhead, drawn as thin quads along each edge of the strip -- 4 segments,
        // 4 verts each (segment() below).
        float[] px = {x, x - width, x, x + width, x};
        float[] py = {y, y + height, y + height, y + height, y};
        try (ByteBufferBuilder byteBufferBuilder = ByteBufferBuilder.exactlySized((px.length - 1) * 4 * DefaultVertexFormat.POSITION_COLOR.getVertexSize())) {
            BufferBuilder buffer = new BufferBuilder(byteBufferBuilder, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (int i = 0; i < px.length - 1; i++) {
                segment(buffer, matrix, px[i], py[i], px[i + 1], py[i + 1], 0.5f, c);
            }
            MeshData mesh = buffer.build();
            Renderer3D.draw(RenderTypes.debugQuads(), mesh);
        }
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
        Vec3 camera = mc.gameRenderer.mainCamera().position();
        // PORT (26.2): raw GL11.glGetIntegerv(GL_VIEWPORT) call -- under a Vulkan-backend window
        // (Options.PreferredGraphicsApi.VULKAN) there's no bound GL context at all, so any raw
        // LWJGL GL11 call crashes on a null function pointer. mc.getWindow() already carries the
        // same framebuffer pixel dimensions GL_VIEWPORT would've reported (origin always (0,0)
        // here), backend-agnostic.
        int[] viewport = {0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight()};

        Vector3f target = new Vector3f();
        Vector4f transform = new Vector4f((float) (vec3d.x - camera.x), (float) (vec3d.y - camera.y), (float) (vec3d.z - camera.z), 1.0f).mul(LAST_WORLD_MATRIX);

        Matrix4f matrixProj = new Matrix4f(LAST_PROJECTION_MATRIX);
        Matrix4f matrixModel = new Matrix4f(LAST_MODEL_MATRIX);

        matrixProj.mul(matrixModel).project(transform.x(), transform.y(), transform.z(), viewport, target);

        return new Vec3(target.x / mc.getWindow().getGuiScale(), (mc.getWindow().getHeight() - target.y) / mc.getWindow().getGuiScale(), target.z);
    }
}
