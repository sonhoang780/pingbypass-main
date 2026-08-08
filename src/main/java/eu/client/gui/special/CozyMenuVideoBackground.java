package eu.client.gui.special;

import com.mojang.blaze3d.platform.NativeImage;
import eu.client.mixins.accessors.NativeImageAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.IntBuffer;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Streams the Cozy menu's real video background (docs/menu-style-ideas.md follow-up: user
 * supplied 206801.mp4, decoded via ffmpeg into 1500 individual full-res 1920x1080 JPEG frames
 * under assets/euclient/textures/menu/cozy_bg/, 25fps native / 60s loop, ~294MB total).
 * <p>
 * 1500 live GPU textures at 1920x1080 would be ~12GB of VRAM -- completely infeasible to just
 * preload. Instead this keeps exactly ONE {@link DynamicTexture} registered under a single fixed
 * {@link Identifier} for the whole screen's lifetime, and swaps its CPU-side pixel buffer
 * ({@link DynamicTexture#setPixels}) + reuploads ({@link DynamicTexture#upload}) whenever
 * playback advances to a new source frame -- the GPU texture object itself is never recreated.
 * <p>
 * Decoding a 1920x1080 JPEG (~5-15ms) on the render thread would risk stalling the menu below
 * the "still smooth at 120fps" ask (a 120fps frame budget is only 8.3ms). Decoding instead
 * happens on a dedicated background thread; {@link #render} just polls for a finished result and
 * uploads whatever's ready. If decode falls behind real time (slow disk/CPU), frames are dropped
 * rather than queued -- playback stays in sync with the wall clock instead of catching up in slow
 * motion, the same tradeoff any real video player makes under load.
 */
public class CozyMenuVideoBackground {
    private static final int FRAME_COUNT = 1500;
    private static final int FPS = 25;
    private static final int WIDTH = 1920, HEIGHT = 1080;
    private static final Identifier TEXTURE_ID = Identifier.fromNamespaceAndPath("euclient", "menu/cozy_bg");

    // Not final/not created once: MainMenuScreen instances get REUSED as the "back" target for
    // every sub-screen it opens (SelectWorldScreen/OptionsScreen/etc all pass `this`), and
    // vanilla calls removed() on it every time it stops being the active screen -- including
    // temporarily, while one of those child screens is up, not just on final disposal. close()
    // (called from removed()) used to shutdownNow() this unconditionally, so navigating back
    // ("Done" on Options) resumed rendering on a screen whose decoder was permanently dead,
    // and the next frame-advance's submit() threw RejectedExecutionException -- crashed the
    // whole renderer. Recreating lazily here means a temporary removed()/render() cycle just
    // respawns the thread instead of crashing.
    private ExecutorService decoder;
    private final AtomicReference<NativeImage> decoded = new AtomicReference<>();
    private volatile boolean decoding = false;
    private volatile int pendingFrame = -1;

    private DynamicTexture texture;
    private int uploadedFrame = -1;
    private long startTime = -1L;

    public void render(GuiGraphicsExtractor context, int width, int height) {
        if (startTime < 0L) startTime = System.currentTimeMillis();

        long elapsedMs = System.currentTimeMillis() - startTime;
        int frame = (int) ((elapsedMs / 1000.0 * FPS) % FRAME_COUNT);

        if (frame != uploadedFrame && frame != pendingFrame && !decoding) {
            pendingFrame = frame;
            decoding = true;
            ensureDecoder().submit(() -> decodeFrame(frame));
        }

        NativeImage ready = decoded.getAndSet(null);
        if (ready != null) {
            if (texture == null) {
                texture = new DynamicTexture(() -> "euclient/cozy_bg", ready);
                Minecraft.getInstance().getTextureManager().register(TEXTURE_ID, texture);
            } else {
                texture.setPixels(ready);
                texture.upload();
            }
            uploadedFrame = pendingFrame;
            decoding = false;
        }

        // First frame hasn't finished decoding yet -- nothing to draw this call, caller's own
        // fallback (sky gradient etc.) should already be underneath/instead.
        if (texture == null) return;

        // The 10-arg blit() overload reuses draw width/height AS the sample-region size too --
        // fine when drawing a texture at its native size, wrong here: GUI-scaled screen size
        // (e.g. 960x496) is smaller than the source frame (1920x1080), so that overload sampled
        // only a matching top-left CORNER of the texture instead of the whole thing stretched to
        // fit. Explicit srcWidth/srcHeight = the full 1920x1080 source region regardless of draw
        // size fixes it.
        context.blit(RenderPipelines.GUI_TEXTURED, TEXTURE_ID, 0, 0, 0, 0, width, height, WIDTH, HEIGHT, WIDTH, HEIGHT, -1);
    }

    private ExecutorService ensureDecoder() {
        if (decoder == null || decoder.isShutdown()) {
            decoder = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "euclient-cozy-bg-decode");
                thread.setDaemon(true);
                return thread;
            });
        }
        return decoder;
    }

    private void decodeFrame(int frame) {
        Identifier resource = Identifier.fromNamespaceAndPath("euclient", String.format("textures/menu/cozy_bg/frame_%04d.jpg", frame));
        Optional<Resource> handle = Minecraft.getInstance().getResourceManager().getResource(resource);
        if (handle.isEmpty()) {
            decoding = false;
            if (!loggedFailure) {
                loggedFailure = true;
                org.slf4j.LoggerFactory.getLogger("EUClient/CozyMenu").error("[CozyMenu] resource not found: {}", resource);
            }
            return;
        }

        // NativeImage.read() validates a real PNG header (PngInfo.validateHeader) before ever
        // reaching STB decode -- it does NOT sniff/support JPEG despite Format.supportedByStb()
        // suggesting otherwise, throws "Bad PNG Signature" on every one of these frames. Decode
        // via javax.imageio (built into the JDK, handles JPEG fine) into a BufferedImage instead,
        // then copy pixels into a NativeImage by hand -- the exact same technique GlyphMap already
        // uses to turn an AWT-rendered BufferedImage into a NativeImage for its glyph atlas.
        try (InputStream stream = handle.get().open()) {
            BufferedImage buffered = ImageIO.read(stream);
            if (buffered == null) throw new java.io.IOException("ImageIO returned null (unrecognized format)");
            decoded.set(toNativeImage(buffered));
        } catch (Exception e) {
            decoding = false;
            if (!loggedFailure) {
                loggedFailure = true;
                org.slf4j.LoggerFactory.getLogger("EUClient/CozyMenu").error("[CozyMenu] frame decode failed for {}", resource, e);
            }
        }
    }

    private static NativeImage toNativeImage(BufferedImage buffered) {
        int w = buffered.getWidth(), h = buffered.getHeight();
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        IntBuffer buffer = MemoryUtil.memIntBuffer(((NativeImageAccessor) (Object) image).getPointer(), w * h);

        // BufferedImage.getRGB packs 0xAARRGGBB; NativeImage's RGBA8 layout wants the R byte in
        // the LOWEST byte of the int (little-endian 0xAABBGGRR) -- same byte-swap GlyphMap does
        // per-pixel via ColorModel, but getRGB's bulk fetch is one native call instead of a
        // manual raster walk, which matters here at 1920x1080 * 25/sec instead of a one-off atlas.
        int[] argb = buffered.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            int a = p >>> 24, r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            buffer.put(i, (a << 24) | (b << 16) | (g << 8) | r);
        }

        return image;
    }

    private volatile boolean loggedFailure = false;

    public boolean isReady() {
        return texture != null;
    }

    public void close() {
        if (decoder != null) decoder.shutdownNow();
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(TEXTURE_ID);
            texture.close();
            texture = null;
        }
        decoding = false;
        uploadedFrame = -1;
        pendingFrame = -1;
        startTime = -1L;
    }
}
