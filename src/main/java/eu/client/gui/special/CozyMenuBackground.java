package eu.client.gui.special;

import com.mojang.blaze3d.vertex.PoseStack;
import eu.client.utils.graphics.Renderer2D;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The "Cozy" menu style's atmospheric background (docs/menu-style-ideas.md) -- the user's own
 * rainy-night video (see {@link CozyMenuVideoBackground}) as the base layer, with drifting embers
 * and mouse parallax layered on top, matching the Claude Design mockup's overlay structure
 * (video underneath, particle/glow divs above it). A flat sky gradient covers the ~1-2 frames
 * before the video's first frame finishes its async decode, so there's no black flash on open.
 * <p>
 * Embers are a real GPU box-blur ({@link CozyGlowCapture}), not the flat quads Renderer2D's
 * retained GUI path is limited to -- see CozyGlowCapture's own doc comment for why that needs
 * immediate-mode drawing.
 */
public class CozyMenuBackground {
    private static final Color SKY_TOP = new Color(9, 5, 3);
    private static final Color SKY_BOTTOM = new Color(35, 22, 12);
    private static final Color EMBER = new Color(255, 150, 70);

    private final CozyMenuVideoBackground video = new CozyMenuVideoBackground();
    private final CozyGlowCapture emberGlow = new CozyGlowCapture();
    private final PoseStack immediatePose = new PoseStack();

    private int cachedWidth = -1, cachedHeight = -1;
    private final List<Ember> embers = new ArrayList<>();

    // Eased toward the real cursor position every frame (same "+= (target - current) * factor"
    // smoothing MainMenuScreen.Entry already uses for its hover fade) so the parallax drifts
    // instead of snapping to the cursor.
    private float mx, my;

    public void render(GuiGraphicsExtractor context, int width, int height, int mouseX, int mouseY) {
        if (width != cachedWidth || height != cachedHeight) {
            generateEmbers(width);
            cachedWidth = width;
            cachedHeight = height;
        }

        float targetMx = (mouseX / (float) Math.max(1, width)) - 0.5f;
        float targetMy = (mouseY / (float) Math.max(1, height)) - 0.5f;
        mx += (targetMx - mx) * 0.08f;
        my += (targetMy - my) * 0.08f;

        if (!video.isReady()) Renderer2D.renderGradient(context, 0, 0, width, height, SKY_TOP, SKY_BOTTOM);
        video.render(context, width, height);

        renderEmbers(context, width, height, mx * 24, my * 16);

        // Vignette -- darken the top and bottom edges so the video reads as receding into
        // fog/night instead of a flat rectangle, and so the menu panel (drawn on top, left side)
        // has something darker to sit on.
        Renderer2D.renderGradient(context, 0, 0, width, height * 0.18f, new Color(3, 2, 1, 140), new Color(3, 2, 1, 0));
        Renderer2D.renderGradient(context, 0, height * 0.75f, width, height, new Color(3, 2, 1, 0), new Color(3, 2, 1, 160));
    }

    public void close() {
        video.close();
        emberGlow.close();
    }

    private void renderEmbers(GuiGraphicsExtractor context, int width, int height, float offsetX, float offsetY) {
        emberGlow.beginCapture(width, height);
        double t = System.currentTimeMillis() / 1000.0;

        for (Ember e : embers) {
            double progress = ((t + e.delay) % e.duration) / e.duration;
            float x = (float) (e.leftFrac * width + e.driftPx * progress) + offsetX;
            float y = (float) (height * 1.05 - progress * height * 1.3) + offsetY;

            // Fade in over the first tenth, hold, fade out over the last tenth -- matches the
            // mockup's rise keyframe (opacity 0 -> op -> op -> 0 at the 0/10/90/100% marks).
            float fade = progress < 0.1f ? (float) (progress / 0.1) : progress > 0.9f ? (float) ((1.0 - progress) / 0.1) : 1f;
            float alpha = (float) e.opacity * fade;
            if (alpha <= 0.01f) continue;

            float half = (float) e.size / 2f;
            Color c = new Color(EMBER.getRed(), EMBER.getGreen(), EMBER.getBlue(), (int) (alpha * 255));
            Renderer2D.renderImmediateQuad(immediatePose, x - half, y - half, x + half, y + half, c);
        }

        emberGlow.endCapture();
        emberGlow.blurAndComposite(context, width, height);
    }

    private void generateEmbers(int width) {
        embers.clear();

        int count = Math.max(20, width / 32);
        for (int i = 0; i < count; i++) {
            embers.add(new Ember(
                    Math.random(),
                    2 + Math.random() * 3,
                    9 + Math.random() * 7,
                    -Math.random() * 12,
                    (Math.random() - 0.5) * 60,
                    0.35 + Math.random() * 0.5));
        }
    }

    private record Ember(double leftFrac, double size, double duration, double delay, double driftPx, double opacity) {}
}
