package eu.client.utils.graphics;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.TextureTarget;
import eu.client.utils.IMinecraft;

// SkyRenderer.renderStars() reads Minecraft.getInstance().gameRenderer.mainRenderTarget() DIRECTLY at call
// time (verified via .mcref decompiled source) -- it never goes through RenderSystem.
// outputColorTextureOverride the way ItemInHandRenderer/Font's batched draws do, so that proven
// redirect trick doesn't apply here. SkyRendererMixin instead @Redirects the createRenderPass(...)
// call INSIDE renderStars to point at this isolated target's own views -- true per-pixel isolation
// (only star geometry ever lands here, nothing else), no brightness/chroma threshold needed at all.
public class StarCapture implements IMinecraft {
    private static TextureTarget target;

    public static TextureTarget ensure() {
        int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
        if (target == null || target.width != w || target.height != h) {
            if (target != null) target.destroyBuffers();
            // PORT (26.2): TextureTarget gained a trailing GpuFormat param -- confirmed via real
            // 26.2 source, LevelRenderer's own entityOutlineTarget (same shape: labeled, useDepth
            // true) uses GpuFormat.RGBA8_UNORM.
            target = new TextureTarget("euclient_star_capture", w, h, true, GpuFormat.RGBA8_UNORM);
        }
        return target;
    }

    public static TextureTarget get() {
        return target;
    }
}
