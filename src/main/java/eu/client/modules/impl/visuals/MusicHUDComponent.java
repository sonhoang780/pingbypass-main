package eu.client.modules.impl.visuals;

import com.mojang.blaze3d.platform.NativeImage;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackState;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.RenderOverlayEvent;
import eu.client.events.impl.TickEvent;
import eu.client.managers.HudElementRegistry;

import eu.client.modules.impl.miscellaneous.PlayMusicModule;

import eu.client.utils.color.ColorUtils;
import eu.client.utils.graphics.skia.MusicHudPipRenderer;
import eu.client.utils.graphics.skia.MusicHudPipState;
import eu.client.utils.graphics.skia.SkiaFontHelper;
import io.github.humbleui.skija.*;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

public class MusicHUDComponent implements eu.client.utils.IMinecraft {
    public static MusicHUDComponent INSTANCE;


    private static final int BAR_COUNT = 15;
    private static final int THUMB_W = 55;
    private static final int THUMB_H = 55;
    private static final int HUD_HEIGHT = 70;
    private static final Identifier VINYL_TEX = Identifier.fromNamespaceAndPath("musichud", "vinyl.png");
    private static final Identifier PLAY_ICON = Identifier.fromNamespaceAndPath("musichud", "play.png");
    private static final Identifier PAUSE_ICON = Identifier.fromNamespaceAndPath("musichud", "pause-button.png");
    private static final Identifier PREV_ICON = Identifier.fromNamespaceAndPath("musichud", "previous.png");
    private static final Identifier NEXT_ICON = Identifier.fromNamespaceAndPath("musichud", "next-button.png");

    private String currentTrackId = "";
    private String displayTitle = "Not playing";
    private String displayAuthor = "";

    private DynamicTexture activeThumbTexture = null;
    private Identifier activeThumbId = null;

    private final float[] barHeights = new float[BAR_COUNT];
    private float smoothedAmp = 0f;
    private float waveProgress = 1.0f;
    private double waveX = 0;
    private double waveY = 0;

    private boolean hoverPlay = false;
    private boolean hoverPrev = false;
    private boolean hoverNext = false;
    private boolean hoverProgress = false;

    private boolean wasMouseDown = false;
    private double animW = 195.0;
    private float smoothDiskRotation = 0f;
    private long lastRenderTime = 0;

    private Font skiaFontTitle;
    private Font skiaFontAuthor;
    private Font skiaFontBody;

    private record PaintState(
            double x, double y, double hudW, double hudH,
            int thumbX, int thumbY, int thumbW, int thumbH,
            boolean notPlaying, boolean useDisk, boolean useUltra, float radius,
            Identifier activeThumbId, float diskRotationDeg,
            double contentX, double barsX, double barsY, double barsW,
            String titleText, String authorText, String timeText,
            double timeX, double timeY,
            double btnPrevX, double btnPlayX, double btnNextX, double btnY, int iconSz,
            boolean hoverPrev, boolean hoverPlay, boolean hoverNext,
            double progX, double progY, double pBarX, double pBarTop, double pBarW, double filledW,
            boolean hoverProgress, Color accent, Identifier playPauseIcon,
            boolean liquidGlass, boolean gpuBlur, boolean enableGlow
    ) {}

    private PaintState paintState = null;

    public MusicHUDComponent() {
        INSTANCE = this;
        eu.client.EUClient.EVENT_HANDLER.subscribe(this);
    }





    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (!eu.client.modules.impl.core.HUDModule.INSTANCE.musicHud.getValue()) return;
    }

    private void ensureFonts() {
        if (skiaFontTitle == null) {
            skiaFontTitle = SkiaFontHelper.createFont(null, 13.0f);
        }
        if (skiaFontAuthor == null) {
            skiaFontAuthor = SkiaFontHelper.createFont(null, 10.0f);
        }
        if (skiaFontBody == null) {
            skiaFontBody = SkiaFontHelper.createFont(null, 11.0f);
        }
    }

    private void triggerWave(double x, double y) {
        this.waveX = x;
        this.waveY = y;
        this.waveProgress = 0.0f;
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderOverlayEvent event) {
        if ((!eu.client.modules.impl.core.HUDModule.INSTANCE.musicHud.getValue()) || mc.player == null) return;

        ensureFonts();
        GuiGraphicsExtractor context = event.getContext();

        double x = eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudPosition.getX();
        double y = eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudPosition.getY();

        AudioTrack track = PlayMusicModule.getCurrentTrack();
        boolean notPlaying = (track == null);

        double fullWidth = THUMB_W + 10 + 130.0;
        if (track != null) {
            String rawTitle = track.getInfo().title != null ? track.getInfo().title : "Unknown Title";
            String rawAuthor = track.getInfo().author != null ? track.getInfo().author : "Unknown Artist";
            float tw = SkiaFontHelper.measureTextWidth(skiaFontTitle, rawTitle);
            float aw = SkiaFontHelper.measureTextWidth(skiaFontAuthor, rawAuthor);
            double needed = Math.max(tw, aw) + THUMB_W + 28.0;
            fullWidth = Math.max(fullWidth, needed);
        }

        double targetW = eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudCompactMode.getValue() ? (THUMB_W + 10 + 130.0) : fullWidth;
        animW += (targetW - animW) * 0.12;

        double hudW = Math.max(animW, THUMB_W + 20);
        double hudH = HUD_HEIGHT;

        boolean useUltra = eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudUltraDisk.getValue() && eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudDisk.getValue() && !notPlaying;
        int currentThumbW = useUltra ? eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudDiskSize.getValue().intValue() : THUMB_W;
        int currentThumbH = useUltra ? eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudDiskSize.getValue().intValue() : THUMB_H;
        int thumbX = (int) x + (useUltra ? 0 : 8);
        int thumbY = (int) y + (useUltra ? 0 : (HUD_HEIGHT - currentThumbH) / 2);

        long now = System.currentTimeMillis();
        float deltaSec = lastRenderTime > 0 ? (now - lastRenderTime) / 1000f : 0.016f;
        lastRenderTime = now;

        if (track != null && !PlayMusicModule.isPlayerPaused()) {
            smoothDiskRotation += 120.0f * deltaSec;
            if (smoothDiskRotation >= 360.0f) smoothDiskRotation -= 360.0f;
        }

        if (track != null && !track.getIdentifier().equals(currentTrackId)) {
            currentTrackId = track.getIdentifier();
            displayTitle = track.getInfo().title != null ? track.getInfo().title : "Unknown Title";
            displayAuthor = track.getInfo().author != null ? track.getInfo().author : "Unknown Artist";
            fetchThumbnail(currentTrackId);
        }

        Color accent = eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudColor.getColor();

        double contentX = x + THUMB_W + 14;
        double contentW = Math.max(10, hudW - THUMB_W - 20);

        String titleText = clipText(skiaFontTitle, displayTitle, (float) contentW - 5);
        String authorText = clipText(skiaFontAuthor, displayAuthor, (float) contentW - 5);

        String timeText = "";
        double timeX = x + hudW - 10, timeY = y + 20;
        double prog = 0.0;
        if (track != null) {
            long pos = track.getPosition();
            long dur = track.getDuration();
            if (dur > 0) prog = (double) pos / dur;
            timeText = formatTime(pos) + " / " + formatTime(dur);
        }

        double btnY = y + HUD_HEIGHT - 24;
        int iconSz = 12;
        double btnPrevX = contentX + 2;
        double btnPlayX = contentX + 20;
        double btnNextX = contentX + 38;

        double pBarX = contentX + 56;
        double pBarW = Math.max(10, contentW - 60);
        double pBarTop = y + HUD_HEIGHT - 19;
        double filledW = Math.max(2, pBarW * Math.clamp(prog, 0.0, 1.0));

        Identifier playPauseIcon = (track != null && !PlayMusicModule.isPlayerPaused()) ? PAUSE_ICON : PLAY_ICON;

        handleMouseInteraction(x, y, hudW, hudH, btnPrevX, btnPlayX, btnNextX, btnY, iconSz, pBarX, pBarTop, pBarW, track);

        float radius = 8.0f;
        boolean isLiquidGlass = "LiquidGlass".equalsIgnoreCase(eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudBgMode.getValue());
        boolean isBlur = "Blur".equalsIgnoreCase(eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudBgMode.getValue());
        boolean enableGlow = true;
        boolean gpuBlur = false;

        float scale = (float) mc.getWindow().getGuiScale();
        try {
            context.blurBeforeThisStratum();
        } catch (IllegalStateException ignored) {}

        if (isLiquidGlass) {
            int fbH = mc.getMainRenderTarget().height;
            eu.client.utils.graphics.skia.LiquidGlassHud.INSTANCE.setWidget((float) x, (float) y, (float) hudW, (float) hudH, radius, scale, fbH);
            eu.client.utils.graphics.skia.LiquidGlassHud.INSTANCE.setBlurOutside(false);
            gpuBlur = true;
        } else if (isBlur) {
            float blurVal = eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudBlurIntensity.getValue().floatValue();
            gpuBlur = blurVal > 0.5f;
            if (gpuBlur) {
                eu.client.utils.graphics.skia.SkijaBackdropBlur.INSTANCE.setWidget((float) x, (float) y, (float) hudW, (float) hudH, radius, scale, blurVal, 0.35f);
                eu.client.utils.graphics.skia.SkijaBackdropBlur.INSTANCE.setBlurOutside(false);
            }
        }

        paintState = new PaintState(
                x, y, hudW, hudH,
                thumbX, thumbY, currentThumbW, currentThumbH,
                notPlaying, eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudDisk.getValue(), useUltra, radius,
                activeThumbId, smoothDiskRotation,
                contentX, contentX, y, contentW,
                titleText, authorText, timeText,
                timeX, timeY,
                btnPrevX, btnPlayX, btnNextX, btnY, iconSz,
                hoverPrev, hoverPlay, hoverNext,
                contentX, y + HUD_HEIGHT - 10, pBarX, pBarTop, pBarW, filledW,
                hoverProgress, accent, playPauseIcon,
                isLiquidGlass, gpuBlur, enableGlow
        );

        double extentW = useUltra ? currentThumbW : hudW;
        double extentH = useUltra ? currentThumbH : hudH;

        float margin = 40f;
        int x0 = (int) Math.floor(x - margin), y0 = (int) Math.floor(y - margin);
        int x1 = (int) Math.ceil(x + extentW + margin), y1 = (int) Math.ceil(y + extentH + margin);

        context.guiRenderState.addPicturesInPictureState(new MusicHudPipState(this::paintSkia, x0, y0, x1, y1));
        HudElementRegistry.reportBounds("MusicHUD", 0, 0, (float) extentW, (float) extentH);
    }

    private void handleMouseInteraction(double x, double y, double hudW, double hudH,
                                        double btnPrevX, double btnPlayX, double btnNextX, double btnY, int iconSz,
                                        double pBarX, double pBarTop, double pBarW, AudioTrack track) {
        if (mc.screen == null) return;
        double scale = mc.getWindow().getGuiScale();
        double mx = mc.mouseHandler.xpos() / scale;
        double my = mc.mouseHandler.ypos() / scale;

        hoverPrev = (mx >= btnPrevX && mx <= btnPrevX + iconSz && my >= btnY && my <= btnY + iconSz);
        hoverPlay = (mx >= btnPlayX && mx <= btnPlayX + iconSz && my >= btnY && my <= btnY + iconSz);
        hoverNext = (mx >= btnNextX && mx <= btnNextX + iconSz && my >= btnY && my <= btnY + iconSz);
        hoverProgress = (mx >= pBarX && mx <= pBarX + pBarW && my >= pBarTop - 3 && my <= pBarTop + 8);

        long win = mc.getWindow().handle();
        boolean mouseDown = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean justClicked = mouseDown && !wasMouseDown;
        wasMouseDown = mouseDown;

        if (justClicked) {
            if (hoverPlay && PlayMusicModule.INSTANCE != null) {
                PlayMusicModule.setPausedExternal(!PlayMusicModule.isPlayerPaused());
                triggerWave(btnPlayX + iconSz / 2.0, btnY + iconSz / 2.0);
            } else if (hoverPrev && PlayMusicModule.INSTANCE != null) {
                PlayMusicModule.INSTANCE.previousBtn.setValue(true);
                triggerWave(btnPrevX + iconSz / 2.0, btnY + iconSz / 2.0);
            } else if (hoverNext && PlayMusicModule.INSTANCE != null) {
                PlayMusicModule.INSTANCE.nextBtn.setValue(true);
                triggerWave(btnNextX + iconSz / 2.0, btnY + iconSz / 2.0);
            } else if (hoverProgress && track != null && pBarW > 0) {
                double ratio = Math.clamp((mx - pBarX) / pBarW, 0.0, 1.0);
                PlayMusicModule.seekTo((long) (track.getDuration() * ratio));
            }
        }
    }

    private void paintSkia(Canvas canvas) {
        PaintState p = paintState;
        if (p == null) return;

        if (p.notPlaying) {
            paintBackgroundPanel(canvas);
            try (Paint sq = new Paint()) {
                sq.setColor(new Color(255, 255, 255, 60).getRGB());
                sq.setMode(PaintMode.STROKE);
                sq.setStrokeWidth(1.0f);
                sq.setAntiAlias(true);
                canvas.drawRRect(RRect.makeXYWH((float) p.thumbX, (float) p.thumbY, (float) p.thumbW, (float) p.thumbH, 6f), sq);
            }
            if (skiaFontBody != null) {
                try (Paint tp = new Paint()) {
                    tp.setColor(0xFFFFFFFF);
                    tp.setAntiAlias(true);
                    float bx = (float) p.x + THUMB_W + 18, by = (float) (p.y + p.hudH / 2.0 + 4);
                    canvas.drawString("Not playing", bx, by, skiaFontBody, tp);
                }
            }
            return;
        }

        if (!p.useUltra) {
            paintBackgroundPanel(canvas);
        }

        paintThumbnail(canvas, p.useDisk, p.activeThumbId, p.thumbX, p.thumbY, p.thumbW, p.thumbH, p.diskRotationDeg);

        if (!p.useUltra) {
            paintBars(canvas, p.barsX, p.barsY, p.barsW, p.accent);
            paintTitleAuthor(canvas, p.contentX, p.y, p.titleText, p.authorText, p.accent);

            if (p.timeText != null && skiaFontBody != null) {
                float tw = SkiaFontHelper.measureTextWidth(skiaFontBody, p.timeText);
                float bx = (float) p.timeX - tw, by = (float) p.timeY + 7;
                try (Paint tp = new Paint()) {
                    tp.setColor(0xFFBBBBBB);
                    tp.setAntiAlias(true);
                    canvas.drawString(p.timeText, bx, by, skiaFontBody, tp);
                }
            }

            paintControlsAndProgress(canvas, p);
        }
    }

    private void paintBackgroundPanel(Canvas canvas) {
        PaintState p = paintState;
        if (p == null) return;
        float fx = (float) p.x, fy = (float) p.y, fw = (float) p.hudW, fh = (float) p.hudH;
        RRect panel = RRect.makeXYWH(fx, fy, fw, fh, p.radius);

        if (p.liquidGlass) {
            canvas.save();
            canvas.clipRRect(panel, ClipMode.INTERSECT, true);

            try (Paint tintPaint = new Paint()) {
                tintPaint.setColor(new Color(150, 205, 255, 10).getRGB());
                tintPaint.setAntiAlias(true);
                canvas.drawRRect(panel, tintPaint);
            }
            try (Shader radShader = Shader.makeRadialGradient(
                    fx + fw * 0.5f, fy + fh * 0.45f, Math.max(fw, fh) * 0.85f,
                    new int[]{0x0CFFFFFF, 0x04FFFFFF, 0x14000000}, new float[]{0f, 0.55f, 1f});
                 Paint radPaint = new Paint()) {
                radPaint.setShader(radShader);
                radPaint.setAntiAlias(true);
                canvas.drawRRect(panel, radPaint);
            }
            float gcx = fx + p.radius * 1.6f, gcy = fy + p.radius * 1.2f;
            try (Shader glint = Shader.makeRadialGradient(
                    gcx, gcy, Math.max(8f, p.radius * 2.4f),
                    new int[]{0x40FFFFFF, 0x00FFFFFF}, new float[]{0f, 1f});
                 Paint gp = new Paint()) {
                gp.setShader(glint);
                gp.setAntiAlias(true);
                canvas.drawRect(Rect.makeXYWH(fx, fy, fw, fh), gp);
            }
            canvas.restore();

            if (p.enableGlow && p.accent != null) {
                int ac = (0x22 << 24) | (p.accent.getRGB() & 0x00FFFFFF);
                try (Paint glowPaint = new Paint();
                     ImageFilter glowFilter = ImageFilter.makeDropShadowOnly(0, 0, 16f, 16f, ac)) {
                    glowPaint.setImageFilter(glowFilter);
                    glowPaint.setAntiAlias(true);
                    canvas.drawRRect(panel, glowPaint);
                }
            }
            try (Paint rim = new Paint()) {
                rim.setColor(new Color(200, 225, 255, 36).getRGB());
                rim.setMode(PaintMode.STROKE);
                rim.setStrokeWidth(0.8f);
                rim.setAntiAlias(true);
                canvas.drawRRect(RRect.makeXYWH(fx + 0.5f, fy + 0.5f, fw - 1f, fh - 1f, p.radius), rim);
            }
            return;
        }

        if (p.enableGlow && p.accent != null) {
            try (Paint glowPaint = new Paint();
                 MaskFilter glowBlur = MaskFilter.makeBlur(FilterBlurMode.OUTER, 8f)) {
                glowPaint.setColor(p.accent.getRGB());
                glowPaint.setMode(PaintMode.STROKE);
                glowPaint.setStrokeWidth(2.5f);
                glowPaint.setMaskFilter(glowBlur);
                glowPaint.setAntiAlias(true);
                canvas.drawRRect(panel, glowPaint);
            }
        }
        if (!p.gpuBlur) {
            try (Paint bgPaint = new Paint()) {
                bgPaint.setColor(new Color(15, 15, 15, 150).getRGB());
                bgPaint.setAntiAlias(true);
                canvas.drawRRect(panel, bgPaint);
            }
        }
        try (Paint strokePaint = new Paint()) {
            strokePaint.setColor(new Color(255, 255, 255, 60).getRGB());
            strokePaint.setMode(PaintMode.STROKE);
            strokePaint.setStrokeWidth(1.0f);
            strokePaint.setAntiAlias(true);
            canvas.drawRRect(panel, strokePaint);
        }
    }

    private void paintThumbnail(Canvas canvas, boolean useDisk, Identifier activeThumbId,
                                int tx, int ty, int tw, int th, float diskRotationDeg) {
        MusicHudPipRenderer r = MusicHudPipRenderer.ACTIVE;
        if (activeThumbId != null && r != null) {
            Image thumbImg = r.borrowTexture(activeThumbId);
            if (thumbImg == null) return;
            Rect srcFull = Rect.makeXYWH(0, 0, thumbImg.getWidth(), thumbImg.getHeight());

            if (useDisk) {
                float centerX = tx + tw / 2.0f, centerY = ty + th / 2.0f;
                Image vinylImg = r.borrowTexture(VINYL_TEX);

                canvas.save();
                canvas.translate(centerX, centerY);
                canvas.rotate(diskRotationDeg);
                canvas.translate(-centerX, -centerY);
                try (Paint imgPaint = new Paint()) {
                    imgPaint.setAntiAlias(true);
                    if (vinylImg != null) {
                        Rect vSrc = Rect.makeXYWH(0, 0, vinylImg.getWidth(), vinylImg.getHeight());
                        canvas.drawImageRect(vinylImg, vSrc, Rect.makeXYWH(tx, ty, tw, th), SamplingMode.LINEAR, imgPaint, true);
                    }
                    float coreScale = 0.45f;
                    int coreW = (int) (tw * coreScale), coreH = (int) (th * coreScale);
                    Rect coreDst = Rect.makeXYWH(tx + (tw - coreW) / 2f, ty + (th - coreH) / 2f, coreW, coreH);
                    canvas.drawImageRect(thumbImg, srcFull, coreDst, SamplingMode.LINEAR, imgPaint, true);
                }
                canvas.restore();
            } else {
                try (Paint imgPaint = new Paint()) {
                    imgPaint.setAntiAlias(true);
                    canvas.drawImageRect(thumbImg, srcFull, Rect.makeXYWH(tx, ty, tw, th), SamplingMode.LINEAR, imgPaint, true);
                }
            }
        } else {
            try (Paint bg = new Paint()) {
                bg.setColor(new Color(30, 30, 35, 200).getRGB());
                bg.setAntiAlias(true);
                canvas.drawRRect(RRect.makeXYWH(tx, ty, tw, th, 6f), bg);
            }
            if (skiaFontBody != null) {
                String note = "♪";
                float nw = SkiaFontHelper.measureTextWidth(skiaFontBody, note);
                try (Paint notePaint = new Paint()) {
                    notePaint.setColor(new Color(150, 150, 150, 200).getRGB());
                    notePaint.setAntiAlias(true);
                    canvas.drawString(note, tx + (tw - nw) / 2f, ty + (th + 8) / 2f, skiaFontBody, notePaint);
                }
            }
        }
    }

    private void paintBars(Canvas canvas, double contentX, double y, double contentW, Color accentColor) {
        float audioAmp = PlayMusicModule.currentAmplitude;
        if (audioAmp > smoothedAmp) smoothedAmp += (audioAmp - smoothedAmp) * 0.9f;
        else smoothedAmp += (audioAmp - smoothedAmp) * 0.08f;
        long tick = System.currentTimeMillis();

        float barW = (float) ((contentW - 10) / BAR_COUNT - 1.0f);
        if (barW < 1.0f) barW = 1.0f;

        int alphaVal = (int) Math.max(0, Math.min(255, eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudBarAlpha.getValue().intValue()));
        int barColor = new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), alphaVal).getRGB();

        float maxBarH = HUD_HEIGHT - 26f;
        int fixedBottom = (int) (y + HUD_HEIGHT - 22);

        for (int i = 0; i < BAR_COUNT; i++) {
            double combined = Math.abs(Math.sin(tick / (60.0 + i * 5.5) + i) + Math.cos(tick / (90.0 - i * 4.0) - i * 0.5)) * 0.4 + 0.6;
            double bell = Math.sin(Math.PI * (i / (double) (BAR_COUNT - 1)));
            float targetH = (float) (2.0 + combined * maxBarH * bell * smoothedAmp * 0.7);
            if (targetH > maxBarH) targetH = maxBarH;
            if (PlayMusicModule.isPlayerPaused()) targetH = 2.0f;

            barHeights[i] += (targetH - barHeights[i]) * 0.7f;

            float bx = (float) (contentX + i * (barW + 1.0f));
            float by = (float) (fixedBottom - barHeights[i]);
            float bh = fixedBottom - by;

            if (eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudGradientBars.getValue()) {
                int topColor = new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), Math.max(0, alphaVal - 140)).getRGB();
                try (Shader grad = Shader.makeLinearGradient(bx, by, bx, fixedBottom, new int[]{topColor, barColor}, null);
                     Paint p = new Paint()) {
                    p.setShader(grad);
                    canvas.drawRect(Rect.makeXYWH(bx, by, barW, bh), p);
                }
            } else {
                try (Paint p = new Paint()) {
                    p.setColor(barColor);
                    canvas.drawRect(Rect.makeXYWH(bx, by, barW, bh), p);
                }
            }
        }
    }

    private void paintTitleAuthor(Canvas canvas, double x, double y, String title, String author, Color accent) {
        if (skiaFontTitle == null || skiaFontAuthor == null) return;
        float titleY = (float) y + 16f, authorY = (float) y + 29f;

        try (Paint tp = new Paint()) {
            tp.setAntiAlias(true);
            tp.setColor(0xFFFFFFFF);
            canvas.drawString(title, (float) x, titleY, skiaFontTitle, tp);
            tp.setColor(accent.getRGB());
            canvas.drawString(author, (float) x, authorY, skiaFontAuthor, tp);
        }

        if (eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudTextBloom.getValue()) {
            int bloomAlpha = 45;
            int titleBloom = (bloomAlpha << 24) | 0xFFFFFF;
            int artistBloom = (bloomAlpha << 24) | (accent.getRGB() & 0xFFFFFF);
            int[][] offsets = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
            try (Paint p = new Paint()) {
                p.setAntiAlias(true);
                for (int[] off : offsets) {
                    p.setColor(titleBloom);
                    canvas.drawString(title, (float) x + off[0], titleY + off[1], skiaFontTitle, p);
                    p.setColor(artistBloom);
                    canvas.drawString(author, (float) x + off[0], authorY + off[1], skiaFontAuthor, p);
                }
            }
        }
    }

    private void paintControlsAndProgress(Canvas canvas, PaintState p) {
        paintIcon(canvas, PREV_ICON, p.btnPrevX, p.btnY, p.iconSz);
        paintIcon(canvas, p.playPauseIcon, p.btnPlayX, p.btnY, p.iconSz);
        paintIcon(canvas, NEXT_ICON, p.btnNextX, p.btnY, p.iconSz);

        if (waveProgress < 1.0f) {
            waveProgress += 0.05f;
            float wr = waveProgress * 22.0f;
            int wa = (int) ((1.0f - waveProgress) * 160.0f);
            try (Paint wp = new Paint()) {
                wp.setColor((wa << 24) | (p.accent.getRGB() & 0x00FFFFFF));
                wp.setMode(PaintMode.STROKE);
                wp.setStrokeWidth(2.0f);
                wp.setAntiAlias(true);
                canvas.drawCircle((float) waveX, (float) waveY, wr, wp);
            }
        }

        try (Paint bgP = new Paint()) {
            bgP.setColor(new Color(0, 0, 0, 120).getRGB());
            canvas.drawRRect(RRect.makeXYWH((float) p.pBarX, (float) p.pBarTop, (float) p.pBarW, 4f, 2f), bgP);
        }
        try (Paint fgP = new Paint()) {
            fgP.setColor(p.hoverProgress ? 0xFFFFFFFF : p.accent.getRGB());
            fgP.setAntiAlias(true);
            canvas.drawRRect(RRect.makeXYWH((float) p.pBarX, (float) p.pBarTop, (float) p.filledW, 4f, 2f), fgP);
        }
    }

    private void paintIcon(Canvas canvas, Identifier id, double x, double y, int size) {
        MusicHudPipRenderer r = MusicHudPipRenderer.ACTIVE;
        Image img = r != null ? r.borrowTexture(id) : null;
        if (img == null) return;
        try (Paint paint = new Paint()) {
            paint.setAntiAlias(true);
            canvas.drawImageRect(img, Rect.makeXYWH(0, 0, img.getWidth(), img.getHeight()),
                    Rect.makeXYWH((float) x, (float) y, size, size), SamplingMode.LINEAR, paint, true);
        }
    }

    private String clipText(Font font, String text, float maxWidth) {
        if (font == null || text == null) return text;
        try {
            if (SkiaFontHelper.measureTextWidth(font, text) <= maxWidth) return text;
            String ellipsis = "…";
            int cpCount = text.codePointCount(0, text.length());
            while (cpCount > 1 && SkiaFontHelper.measureTextWidth(font, text + ellipsis) > maxWidth) {
                cpCount--;
                text = text.substring(0, text.offsetByCodePoints(0, cpCount));
            }
            return text + ellipsis;
        } catch (Throwable t) {
            return text;
        }
    }

    private String formatTime(long ms) {
        long sec = ms / 1000;
        long m = sec / 60;
        long s = sec % 60;
        return String.format("%02d:%02d", m, s);
    }

    private void fetchThumbnail(String videoId) {
        if (videoId == null || videoId.isEmpty()) return;
        CompletableFuture.runAsync(() -> {
            try {
                String[] urls = {
                        "https://img.youtube.com/vi/" + videoId + "/mqdefault.jpg",
                        "https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg",
                        "https://img.youtube.com/vi/" + videoId + "/default.jpg"
                };
                for (String urlStr : urls) {
                    try {
                        URL url = new URL(urlStr);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                        conn.setConnectTimeout(2000);
                        if (conn.getResponseCode() == 200) {
                            InputStream in = conn.getInputStream();
                            BufferedImage img = ImageIO.read(in);
                            in.close(); conn.disconnect();
                            if (img != null) {
                                ByteArrayOutputStream out = new ByteArrayOutputStream();
                                ImageIO.write(img, "png", out);
                                NativeImage nativeImg = NativeImage.read(new ByteArrayInputStream(out.toByteArray()));
                                Minecraft.getInstance().execute(() -> {
                                    if (activeThumbTexture != null) {
                                        activeThumbTexture.close();
                                    }
                                    activeThumbTexture = new DynamicTexture(() -> "musichud_" + videoId, nativeImg);
                                    activeThumbId = Identifier.fromNamespaceAndPath("musichud", "thumb_" + videoId.toLowerCase().replaceAll("[^a-z0-9_.-]", ""));
                                    Minecraft.getInstance().getTextureManager().register(activeThumbId, activeThumbTexture);
                                });
                                return;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        });
    }
}
