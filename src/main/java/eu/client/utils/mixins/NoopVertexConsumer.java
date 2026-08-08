package eu.client.utils.mixins;

import com.mojang.blaze3d.vertex.VertexConsumer;

// Discards everything -- used by OutlineBufferSourceMixin to silently skip an outline draw for a
// RenderType that doesn't support one, instead of letting OutlineBufferSource.getBuffer() throw
// IllegalStateException("Can't render an outline for this rendertype!") and abort mid-flush.
public enum NoopVertexConsumer implements VertexConsumer {
    INSTANCE;

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        return this;
    }

    @Override
    public VertexConsumer setColor(int color) {
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        return this;
    }
}
