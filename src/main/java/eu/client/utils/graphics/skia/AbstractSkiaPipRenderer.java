package eu.client.utils.graphics.skia;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.humbleui.skija.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

import java.util.HashMap;
import java.util.Map;

public abstract class AbstractSkiaPipRenderer<T extends SkiaPaintedState> extends PictureInPictureRenderer<T> {

    private static final int GL_RGBA8 = 0x8058;

    private DirectContext ctx;
    private SkiaGlState glState;
    private int fboId = -1, stencilRbo = -1;
    private int attachedGlId = -1, attachedW = -1, attachedH = -1;

    private static final class BorrowedImage {
        final Image image; final int glId;
        BorrowedImage(Image image, int glId) { this.image = image; this.glId = glId; }
    }
    private final Map<Object, BorrowedImage> borrowed = new HashMap<>();

    protected AbstractSkiaPipRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
    }

    @Override
    protected boolean textureIsReadyToBlit(T state) { return false; }

    @Override
    protected void renderToTexture(T state, PoseStack matrices) {
        GpuTextureView colorView = RenderSystem.outputColorTextureOverride;
        if (colorView == null) return;
        GpuTexture colorTex = colorView.texture();
        if (!(colorTex instanceof GlTexture glTex)) return;
        int glId = glTex.glId();
        if (glId <= 0) return;
        int pw = colorTex.getWidth(0), ph = colorTex.getHeight(0);
        if (pw <= 0 || ph <= 0) return;

        if (glState == null) glState = new SkiaGlState();
        glState.push();
        int savedFbo = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
        try {
            if (ctx == null) ctx = DirectContext.makeGL();
            ctx.resetAll();
            ensureFbo(glId, pw, ph);
            if (fboId == -1) return;

            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fboId);
            try (BackendRenderTarget rt = BackendRenderTarget.makeGL(pw, ph, 0, 8, fboId, GL_RGBA8);
                 Surface surface = Surface.wrapBackendRenderTarget(ctx, rt, SurfaceOrigin.BOTTOM_LEFT,
                     ColorType.RGBA_8888, ColorSpace.getSRGB(), null)) {
                if (surface == null) return;
                Canvas canvas = surface.getCanvas();
                canvas.clear(0);

                float scale = (float) Minecraft.getInstance().getWindow().getGuiScale();
                canvas.save();
                try {
                    canvas.scale(scale, scale);
                    canvas.translate(-state.x0(), -state.y0());
                    state.painter().accept(canvas);
                } finally {
                    canvas.restore();
                }
                ctx.flushAndSubmit(false);
            }
        } catch (Throwable t) {
            System.err.println("[" + getClass().getSimpleName() + "] paint error: " + t);
            t.printStackTrace();
        } finally {
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, savedFbo);
            glState.pop();
        }
    }

    private void ensureFbo(int glId, int w, int h) {
        if (fboId != -1 && attachedGlId == glId && attachedW == w && attachedH == h) return;
        destroyFbo();

        fboId = GL30C.glGenFramebuffers();
        stencilRbo = GL30C.glGenRenderbuffers();
        int saved = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fboId);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
            GL11C.GL_TEXTURE_2D, glId, 0);
        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, stencilRbo);
        GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, GL30C.GL_STENCIL_INDEX8, w, h);
        GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_STENCIL_ATTACHMENT,
            GL30C.GL_RENDERBUFFER, stencilRbo);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, saved);

        attachedGlId = glId; attachedW = w; attachedH = h;
    }

    private void destroyFbo() {
        if (fboId != -1) { GL30C.glDeleteFramebuffers(fboId); fboId = -1; }
        if (stencilRbo != -1) { GL30C.glDeleteRenderbuffers(stencilRbo); stencilRbo = -1; }
        attachedGlId = attachedW = attachedH = -1;
    }

    public Image borrowTexture(Identifier id) {
        if (ctx == null || id == null) return null;
        AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(id);
        if (tex == null) return null;
        GpuTexture gpu = tex.getTexture();
        if (!(gpu instanceof GlTexture glTex)) return null;
        int glId = glTex.glId();
        if (glId <= 0) return null;
        return borrowFromGlId(id, glId, gpu.getWidth(0), gpu.getHeight(0), false);
    }

    public Image borrowTexture(Object cacheKey, int glId, int w, int h, boolean opaque) {
        if (ctx == null || cacheKey == null || glId <= 0) return null;
        return borrowFromGlId(cacheKey, glId, w, h, opaque);
    }

    private Image borrowFromGlId(Object cacheKey, int glId, int w, int h, boolean opaque) {
        BorrowedImage cached = borrowed.get(cacheKey);
        if (cached != null && cached.glId == glId) return cached.image;
        if (cached != null) cached.image.close();

        GLTextureInfo info = new GLTextureInfo(GL11C.GL_TEXTURE_2D, glId, GL_RGBA8);
        try (BackendTexture bt = BackendTexture.makeGL(w, h, false, info)) {
            Image img = Image.borrowTextureFrom(ctx, bt, SurfaceOrigin.TOP_LEFT,
                ColorType.RGBA_8888, opaque ? ColorAlphaType.OPAQUE : ColorAlphaType.UNPREMUL, ColorSpace.getSRGB(), null);
            if (img == null) return null;
            borrowed.put(cacheKey, new BorrowedImage(img, glId));
            return img;
        }
    }

    @Override
    public void close() {
        super.close();
        for (BorrowedImage bi : borrowed.values()) {
            if (bi != null && bi.image != null) {
                try { bi.image.close(); } catch (Throwable ignored) {}
            }
        }
        borrowed.clear();
        destroyFbo();
        if (ctx != null) { ctx.close(); ctx = null; }
    }
}
