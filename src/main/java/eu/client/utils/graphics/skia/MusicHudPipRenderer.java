package eu.client.utils.graphics.skia;

// PORT (26.2): disabled entirely -- extended AbstractSkiaPipRenderer, whose vanilla base
// (PictureInPictureRenderer) took a MultiBufferSource.BufferSource ctor arg; MultiBufferSource
// is removed in 26.2 (category #1, deferred -- see
// docs/superpowers/specs/2026-08-20-port-26.2-vulkan-audit.md). No callers left: GameRendererMixin's
// musichud$registerSkiaPip (the only constructor call site) and MusicHUDComponent's
// paintThumbnail/paintIcon (the only ACTIVE readers) were disabled alongside this.
public final class MusicHudPipRenderer {
}
