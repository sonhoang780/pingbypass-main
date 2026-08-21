package eu.client.utils.graphics.skia;

// PORT (26.2): disabled entirely -- used com.mojang.blaze3d.textures.TextureFormat.RGBA8, removed
// in 26.2 (deferred -- see docs/superpowers/specs/2026-08-20-port-26.2-vulkan-audit.md). No
// callers left: GameRendererMixin's musichud$renderLiquidGlass (the only render() call site) and
// MusicHUDComponent's liquid-glass block (the only setWidget/setBlurOutside call site) were
// disabled alongside this. Real GPU pipeline body (blur-X/blur-Y/refraction passes, RenderPipeline
// setup) is gone -- see git history for the pre-disable version if re-deriving for 26.2.
public final class LiquidGlassHud {
    public static final LiquidGlassHud INSTANCE = new LiquidGlassHud();

    private LiquidGlassHud() {}

    public boolean isActive() { return false; }
    public void setBlurOutside(boolean value) {}
    public void render() {}
}
