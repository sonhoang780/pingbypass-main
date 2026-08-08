package eu.client.utils.graphics;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

import java.util.List;

// Real successor to the pre-port ModelRenderer.CustomVertexConsumer: wraps the REAL VertexConsumer
// a model is about to draw into (normal textured rendering is untouched -- every call forwards to
// `real`) and, alongside that, accumulates each quad's 4 addVertex(...) calls to also push a
// translucent fill copy + a wireframe line-loop into Renderer3D's no-depth-test collections (same
// through-walls infra AutoCrystal/BlockHighlight already use). Only the position matters for the
// capture -- setColor/setUv/etc from the model are ignored, same as the original always used the
// module's OWN fixed fill/outline colors rather than the model's per-vertex color.
//
// x/y/z received by addVertex(float,float,float) are already fully pose-transformed by the time
// they reach here (VertexConsumer.addVertex(Pose, x, y, z) is a default method that transforms via
// the matrix then calls straight back into this same instance's addVertex(x,y,z)) -- and since
// this renderer generation keeps all PoseStack coordinates camera-relative throughout, no manual
// "entity position minus camera" offset is needed here (unlike the pre-port version, which built
// its own from-scratch MatrixStack with no such convention and had to compute that offset itself).
public class ChamsVertexConsumer implements VertexConsumer {
    private static final Matrix4f IDENTITY = new Matrix4f();

    private final VertexConsumer real;
    private final boolean fill;
    private final int fillColor;
    private final boolean outline;
    private final int outlineColor;
    private final boolean shine;

    private final float[] xs = new float[4];
    private final float[] ys = new float[4];
    private final float[] zs = new float[4];
    private int i = 0;

    public ChamsVertexConsumer(VertexConsumer real, boolean fill, int fillColor, boolean outline, int outlineColor, boolean shine) {
        this.real = real;
        this.fill = fill;
        this.fillColor = fillColor;
        this.outline = outline;
        this.outlineColor = outlineColor;
        this.shine = shine;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        real.addVertex(x, y, z);

        xs[i] = x;
        ys[i] = y;
        zs[i] = z;
        i++;

        if (i == 4) {
            flushQuad();
            i = 0;
        }

        return this;
    }

    private void flushQuad() {
        List<Renderer3D.VertexCollection> quads = shine ? Renderer3D.SHINE_QUADS : Renderer3D.QUADS;
        List<Renderer3D.VertexCollection> lines = shine ? Renderer3D.SHINE_DEBUG_LINES : Renderer3D.DEBUG_LINES;

        if (fill) {
            quads.add(new Renderer3D.VertexCollection(
                    new Renderer3D.Vertex(IDENTITY, xs[0], ys[0], zs[0], fillColor),
                    new Renderer3D.Vertex(IDENTITY, xs[1], ys[1], zs[1], fillColor),
                    new Renderer3D.Vertex(IDENTITY, xs[2], ys[2], zs[2], fillColor),
                    new Renderer3D.Vertex(IDENTITY, xs[3], ys[3], zs[3], fillColor)));
        }

        if (outline) {
            // 4 separate 2-vertex segments (a line loop around the quad), same layout the
            // pre-port CustomVertexConsumer used -- Renderer3D.DEBUG_LINES draws each
            // VertexCollection as its own line primitive, not a connected strip.
            lines.add(new Renderer3D.VertexCollection(new Renderer3D.Vertex(IDENTITY, xs[0], ys[0], zs[0], outlineColor), new Renderer3D.Vertex(IDENTITY, xs[1], ys[1], zs[1], outlineColor)));
            lines.add(new Renderer3D.VertexCollection(new Renderer3D.Vertex(IDENTITY, xs[1], ys[1], zs[1], outlineColor), new Renderer3D.Vertex(IDENTITY, xs[2], ys[2], zs[2], outlineColor)));
            lines.add(new Renderer3D.VertexCollection(new Renderer3D.Vertex(IDENTITY, xs[2], ys[2], zs[2], outlineColor), new Renderer3D.Vertex(IDENTITY, xs[3], ys[3], zs[3], outlineColor)));
            lines.add(new Renderer3D.VertexCollection(new Renderer3D.Vertex(IDENTITY, xs[3], ys[3], zs[3], outlineColor), new Renderer3D.Vertex(IDENTITY, xs[0], ys[0], zs[0], outlineColor)));
        }
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        real.setColor(red, green, blue, alpha);
        return this;
    }

    @Override
    public VertexConsumer setColor(int argb) {
        real.setColor(argb);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        real.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        real.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        real.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        real.setNormal(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        real.setLineWidth(width);
        return this;
    }
}
