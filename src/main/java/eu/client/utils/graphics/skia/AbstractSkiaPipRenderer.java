package eu.client.utils.graphics.skia;

// PORT (26.2): disabled entirely -- extended vanilla PictureInPictureRenderer&lt;T&gt;, whose ctor
// takes MultiBufferSource.BufferSource; MultiBufferSource is removed in 26.2 (category #1,
// deferred -- see docs/superpowers/specs/2026-08-20-port-26.2-vulkan-audit.md). No callers left
// (only subclass was MusicHudPipRenderer, also disabled).
public abstract class AbstractSkiaPipRenderer {
}