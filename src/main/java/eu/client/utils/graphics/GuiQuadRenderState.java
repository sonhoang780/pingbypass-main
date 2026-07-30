package eu.client.utils.graphics;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

/**
 * A retained-mode GUI element holding a flat list of quads with float-precision coordinates.
 * Since 1.21.5 the GUI is retained-mode: geometry is submitted to
 * {@code GuiRenderState.addGuiElement} and batched by the {@code GuiRenderer}, which builds a
 * single QUADS sequential index buffer for every draw. Every vertex group must therefore be a
 * quad (4 vertices, wound top-left, bottom-left, bottom-right, top-right so quad-index expansion
 * produces two triangles).
 *
 * <p>{@code xs}/{@code ys} are the per-vertex screen coordinates and {@code cols} the per-vertex
 * ARGB colors; all three have length {@code 4 * quadCount}. When {@code us}/{@code vs} are
 * non-null the element is textured: the {@code GUI_TEXTURED} pipeline (POSITION_TEX_COLOR) is used
 * with the supplied {@link TextureSetup}, and every quad in the element shares that one texture.
 * Colored (untextured) elements use the {@code GUI} pipeline (POSITION_COLOR).
 */
public record GuiQuadRenderState(
        RenderPipeline pipeline,
        TextureSetup textureSetup,
        Matrix3x2fc pose,
        float[] xs,
        float[] ys,
        int[] cols,
        float @Nullable [] us,
        float @Nullable [] vs,
        @Nullable ScreenRectangle scissorArea,
        @Nullable ScreenRectangle bounds
) implements GuiElementRenderState {

    /** Colored (untextured) quads on the GUI pipeline. */
    public GuiQuadRenderState(
            final Matrix3x2fc pose,
            final float[] xs,
            final float[] ys,
            final int[] cols,
            final @Nullable ScreenRectangle scissorArea
    ) {
        this(RenderPipelines.GUI, TextureSetup.noTexture(), pose, xs, ys, cols, null, null, scissorArea,
                computeBounds(xs, ys, pose, scissorArea));
    }

    /** Textured quads on the GUI_TEXTURED pipeline; all quads share {@code textureSetup}. */
    public GuiQuadRenderState(
            final TextureSetup textureSetup,
            final Matrix3x2fc pose,
            final float[] xs,
            final float[] ys,
            final int[] cols,
            final float[] us,
            final float[] vs,
            final @Nullable ScreenRectangle scissorArea
    ) {
        this(RenderPipelines.GUI_TEXTURED, textureSetup, pose, xs, ys, cols, us, vs, scissorArea,
                computeBounds(xs, ys, pose, scissorArea));
    }

    @Override
    public void buildVertices(final VertexConsumer consumer) {
        if (this.us != null && this.vs != null) {
            for (int i = 0; i < this.xs.length; i++) {
                consumer.addVertexWith2DPose(this.pose, this.xs[i], this.ys[i]).setUv(this.us[i], this.vs[i]).setColor(this.cols[i]);
            }
        } else {
            for (int i = 0; i < this.xs.length; i++) {
                consumer.addVertexWith2DPose(this.pose, this.xs[i], this.ys[i]).setColor(this.cols[i]);
            }
        }
    }

    private static @Nullable ScreenRectangle computeBounds(
            final float[] xs, final float[] ys, final Matrix3x2fc pose, final @Nullable ScreenRectangle scissorArea
    ) {
        if (xs.length == 0) {
            return scissorArea;
        }
        float minX = xs[0], maxX = xs[0], minY = ys[0], maxY = ys[0];
        for (int i = 1; i < xs.length; i++) {
            if (xs[i] < minX) minX = xs[i];
            if (xs[i] > maxX) maxX = xs[i];
            if (ys[i] < minY) minY = ys[i];
            if (ys[i] > maxY) maxY = ys[i];
        }
        int x0 = (int) Math.floor(minX);
        int y0 = (int) Math.floor(minY);
        int x1 = (int) Math.ceil(maxX);
        int y1 = (int) Math.ceil(maxY);
        ScreenRectangle bounds = new ScreenRectangle(x0, y0, x1 - x0, y1 - y0).transformMaxBounds(pose);
        return scissorArea != null ? scissorArea.intersection(bounds) : bounds;
    }
}
