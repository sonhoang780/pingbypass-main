package eu.client.utils.font;

import com.google.common.base.Preconditions;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import lombok.SneakyThrows;
import eu.client.EUClient;
import eu.client.modules.impl.core.FontModule;
import eu.client.utils.IMinecraft;
import eu.client.utils.graphics.GuiQuadRenderState;
import eu.client.utils.graphics.Renderer3D;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.joml.Matrix4f;

import java.awt.*;
import java.io.Closeable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Custom bitmap-font renderer. Since the 1.21.5+ render overhaul text drawn in the two contexts
 * must be submitted differently, mirroring the {@code Renderer2D} split:
 *
 * <ul>
 *   <li><b>GUI</b> ({@link #drawText(GuiGraphicsExtractor, FormattedCharSequence, float, float, int, boolean)}):
 *       the GUI is retained-mode and no ortho projection is bound during {@code extractRenderState},
 *       so each glyph page is submitted as a <em>textured</em> {@link GuiQuadRenderState}
 *       (POSITION_TEX_COLOR on {@code GUI_TEXTURED}, one {@link TextureSetup} per atlas page). This
 *       keeps custom-font text in correct z-order with the rest of the GUI batch.</li>
 *   <li><b>World / HUD nametags</b> ({@link #drawText(PoseStack, FormattedCharSequence, float, float, int, boolean)}):
 *       take a real 3D {@link PoseStack} and draw immediately via {@link RenderType#draw(MeshData)},
 *       which reads the model-view/projection already set on the render system.</li>
 * </ul>
 *
 * <p>Both paths share {@link #layoutGlyphs} which walks the text once, grouping glyphs by their
 * owning {@link GlyphMap} (atlas page) and packing the per-glyph ARGB color.
 */
public class FontRenderer implements Closeable, IMinecraft {
    private final ObjectList<GlyphMap> maps = new ObjectArrayList<>();

    /** Cached per-atlas render types for the immediate (world/nametag) path. */
    private static final Map<Identifier, RenderType> WORLD_TYPES = new HashMap<>();

    private Font[] fonts;

    private final float size;
    private final int charsPerPage;
    private final int padding;

    private int multiplier = 0;
    private int previousGameScale = -1;
    private boolean initialized;

    private final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();

    public FontRenderer(Font[] fonts, float size) {
        this(fonts, size, 256, 5);
    }

    public FontRenderer(Font[] fonts, float size, int charactersPerPage, int paddingBetweenCharacters) {
        Preconditions.checkArgument(size > 0, "size <= 0");
        Preconditions.checkArgument(fonts.length > 0, "fonts.length <= 0");
        Preconditions.checkArgument(charactersPerPage > 4, "Unreasonable charactersPerPage count");
        Preconditions.checkArgument(paddingBetweenCharacters > 0, "paddingBetweenCharacters <= 0");

        this.size = size;
        this.charsPerPage = charactersPerPage;
        this.padding = paddingBetweenCharacters;

        init(fonts, size);
    }

    // ------------------------------------------------------------------
    // GUI (retained-mode) path
    // ------------------------------------------------------------------

    public void drawString(GuiGraphicsExtractor context, String text, float x, float y, int color, boolean dropShadow) {
        drawText(context, Component.literal(text).getVisualOrderText(), x, y, color, dropShadow);
    }

    public void drawText(GuiGraphicsExtractor context, FormattedCharSequence text, float x, float y, int color, boolean dropShadow) {
        submitGlyphs(context.guiRenderState, new Matrix3x2f(context.pose()), context.scissorStack.peek(), text, x, y, color, dropShadow);
    }

    // Shared by the two GUI entry points: drawText(GuiGraphicsExtractor, ...) above (our own
    // ClickGui/HUD text, called with useCustomFont() already true) and GuiRenderStateMixin (the
    // "Global" vanilla-wide hook -- GuiGraphicsExtractor.text(...) AND ActiveTextCollector's real
    // implementation, i.e. chat and every vanilla Screen widget/tooltip, both funnel into
    // GuiRenderState.addText(GuiTextRenderState) as their one common choke point, verified via
    // javap; TextRendererMixin's Font.drawInBatch hook never fires for either since neither calls
    // it -- drawInBatch is world-space/direct text only in this renderer generation).
    public void submitGlyphs(GuiRenderState guiRenderState, Matrix3x2fc pose, ScreenRectangle scissor, FormattedCharSequence text, float x, float y, int color, boolean dropShadow) {
        ensureScale();

        if (EUClient.MODULE_MANAGER != null && EUClient.MODULE_MANAGER.getModule(FontModule.class).isToggled()) {
            x += EUClient.MODULE_MANAGER.getModule(FontModule.class).xOffset.getValue().floatValue();
            y += EUClient.MODULE_MANAGER.getModule(FontModule.class).yOffset.getValue().floatValue();
        }

        Map<GlyphMap, ObjectList<DrawEntry>> pages = layoutGlyphs(text, color, dropShadow, new float[1]);
        if (pages.isEmpty()) return;

        float inv = 1.0f / this.multiplier;

        for (Map.Entry<GlyphMap, ObjectList<DrawEntry>> entry : pages.entrySet()) {
            GlyphMap map = entry.getKey();
            List<DrawEntry> entries = entry.getValue();
            int n = entries.size();

            float[] xs = new float[n * 4];
            float[] ys = new float[n * 4];
            float[] us = new float[n * 4];
            float[] vs = new float[n * 4];
            int[] cols = new int[n * 4];

            int qi = 0;
            for (DrawEntry d : entries) {
                Glyph glyph = d.toDraw();

                float u1 = (float) glyph.u() / map.getWidth();
                float v1 = (float) glyph.v() / map.getHeight();
                float u2 = (float) (glyph.u() + glyph.width()) / map.getWidth();
                float v2 = (float) (glyph.v() + glyph.height()) / map.getHeight();

                float gx0 = x + d.atX() * inv;
                float gy0 = y + d.atY() * inv;
                float gx1 = x + (d.atX() + glyph.width()) * inv;
                float gy1 = y + (d.atY() + glyph.height()) * inv;

                int b = qi * 4;
                // top-left, bottom-left, bottom-right, top-right (keystone winding)
                xs[b] = gx0;     ys[b] = gy0;     us[b] = u1;     vs[b] = v1;
                xs[b + 1] = gx0; ys[b + 1] = gy1; us[b + 1] = u1; vs[b + 1] = v2;
                xs[b + 2] = gx1; ys[b + 2] = gy1; us[b + 2] = u2; vs[b + 2] = v2;
                xs[b + 3] = gx1; ys[b + 3] = gy0; us[b + 3] = u2; vs[b + 3] = v1;
                cols[b] = cols[b + 1] = cols[b + 2] = cols[b + 3] = d.argb();
                qi++;
            }

            TextureSetup ts = TextureSetup.singleTexture(map.getTexture().getTextureView(), map.getTexture().getSampler());
            guiRenderState.addGuiElement(new GuiQuadRenderState(ts, new Matrix3x2f(pose), xs, ys, cols, us, vs, scissor));
        }
    }

    // ------------------------------------------------------------------
    // World / HUD nametag (immediate-mode) path
    // ------------------------------------------------------------------

    public void drawString(PoseStack matrices, String text, float x, float y, int color, boolean dropShadow) {
        drawText(matrices, Component.literal(text).getVisualOrderText(), x, y, color, dropShadow);
    }

    public void drawText(PoseStack matrices, FormattedCharSequence text, float x, float y, int color, boolean dropShadow) {
        ensureScale();

        if (EUClient.MODULE_MANAGER != null && EUClient.MODULE_MANAGER.getModule(FontModule.class).isToggled()) {
            x += EUClient.MODULE_MANAGER.getModule(FontModule.class).xOffset.getValue().floatValue();
            y += EUClient.MODULE_MANAGER.getModule(FontModule.class).yOffset.getValue().floatValue();
        }

        Map<GlyphMap, ObjectList<DrawEntry>> pages = layoutGlyphs(text, color, dropShadow, new float[1]);
        if (pages.isEmpty()) return;

        matrices.pushPose();
        matrices.translate(x, y, 0);
        matrices.scale(1.0f / this.multiplier, 1.0f / this.multiplier, 1.0f);
        Matrix4f matrix = matrices.last().pose();

        for (Map.Entry<GlyphMap, ObjectList<DrawEntry>> entry : pages.entrySet()) {
            GlyphMap map = entry.getKey();

            // PORT (26.2): Tesselator removed, own a ByteBufferBuilder sized exactly for this
            // page's glyph count -- see Renderer3D.draw's comment for the vanilla-confirmed pattern.
            try (ByteBufferBuilder byteBufferBuilder = ByteBufferBuilder.exactlySized(entry.getValue().size() * 4 * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize())) {
                BufferBuilder buffer = new BufferBuilder(byteBufferBuilder, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
                for (DrawEntry d : entry.getValue()) {
                    Glyph glyph = d.toDraw();

                    float u1 = (float) glyph.u() / map.getWidth();
                    float v1 = (float) glyph.v() / map.getHeight();
                    float u2 = (float) (glyph.u() + glyph.width()) / map.getWidth();
                    float v2 = (float) (glyph.v() + glyph.height()) / map.getHeight();

                    buffer.addVertex(matrix, d.atX(), d.atY() + glyph.height(), 0).setUv(u1, v2).setColor(d.argb());
                    buffer.addVertex(matrix, d.atX() + glyph.width(), d.atY() + glyph.height(), 0).setUv(u2, v2).setColor(d.argb());
                    buffer.addVertex(matrix, d.atX() + glyph.width(), d.atY(), 0).setUv(u2, v1).setColor(d.argb());
                    buffer.addVertex(matrix, d.atX(), d.atY(), 0).setUv(u1, v1).setColor(d.argb());
                }

                MeshData mesh = buffer.build();
                Renderer3D.draw(worldTexturedType(map.getTextureId()), mesh);
            }
        }

        matrices.popPose();
    }

    // ------------------------------------------------------------------
    // Shared glyph layout
    // ------------------------------------------------------------------

    /**
     * Walks {@code text} once, grouping glyphs by their owning atlas page and packing per-glyph
     * ARGB (glyph rgb from the style, alpha from the top-level color). {@code outWidth[0]} receives
     * the total advance in atlas (pre-scale) pixels.
     */
    private Map<GlyphMap, ObjectList<DrawEntry>> layoutGlyphs(FormattedCharSequence text, int color, boolean dropShadow, float[] outWidth) {
        if ((color & -67108864) == 0) color |= -16777216;
        if (dropShadow) color = (color & 16579836) >> 2 | color & -16777216;

        final int baseAlpha = (color >> 24) & 0xFF;
        final int baseRed = (color >> 16) & 0xFF;
        final int baseGreen = (color >> 8) & 0xFF;
        final int baseBlue = color & 0xFF;

        Map<GlyphMap, ObjectList<DrawEntry>> pages = new LinkedHashMap<>();
        float[] currentX = {0};

        text.accept((index, style, codePoint) -> {
            char[] chars = Character.toChars(codePoint);

            int r = baseRed, g = baseGreen, b = baseBlue;
            if (style.getColor() != null) {
                int rgb = style.getColor().getValue();
                if ((rgb & -67108864) == 0) rgb |= -16777216;
                if (dropShadow) rgb = (rgb & 16579836) >> 2 | rgb & -16777216;
                r = (rgb >> 16) & 0xFF;
                g = (rgb >> 8) & 0xFF;
                b = rgb & 0xFF;
            }
            int argb = (baseAlpha << 24) | (r << 16) | (g << 8) | b;

            for (char character : chars) {
                Glyph glyph = locateGlyph(character);
                if (glyph != null) {
                    if (glyph.value() != ' ') {
                        pages.computeIfAbsent(glyph.parent(), k -> new ObjectArrayList<>())
                                .add(new DrawEntry(currentX[0], 0, argb, glyph));
                    }
                    currentX[0] += glyph.width();
                }
            }

            return true;
        });

        outWidth[0] = currentX[0];
        return pages;
    }

    private static RenderType worldTexturedType(Identifier id) {
        return WORLD_TYPES.computeIfAbsent(id, i -> RenderType.create(
                "euclient_font_" + i,
                RenderSetup.builder(RenderPipelines.GUI_TEXTURED).withTexture("Sampler0", i).createRenderSetup()));
    }

    private void ensureScale() {
        if (((int) mc.getWindow().getGuiScale()) != this.previousGameScale) {
            close();
            init(this.fonts, this.size);
        }
    }

    public float getTextWidth(String text) {
        char[] characters = stripControlCodes(text).toCharArray();
        float width = 0;

        for (char ch : characters) {
            Glyph glyph = locateGlyph(ch);
            if (glyph != null) {
                width += glyph.width() / (float) this.multiplier;
            }
        }

        return Math.max(width, 0);
    }

    public float getTextWidth(FormattedCharSequence text) {
        float[] dimensions = new float[2];
        text.accept((index, style, codePoint) -> {
            char character = (char) codePoint;

            Glyph glyph = locateGlyph(character);
            if (glyph != null) {
                dimensions[0] += (float) glyph.width() / (float) this.multiplier;
            }

            return true;
        });

        return Math.max(dimensions[0], dimensions[1]);
    }

    public float getHeight() {
        Glyph glyph = locateGlyph('A');
        if (glyph != null) return glyph.height() / (float) this.multiplier;
        return 0.0f;
    }

    public static String stripControlCodes(String text) {
        char[] chars = text.toCharArray();
        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < chars.length; i++) {
            char character = chars[i];
            if (character == '§') {
                i++;
                continue;
            }

            builder.append(character);
        }

        return builder.toString();
    }

    private void init(Font[] fonts, float sizePx) {
        if (initialized) throw new IllegalStateException("Double call to init()");
        LOCK.writeLock().lock();

        try {
            this.previousGameScale = (int) mc.getWindow().getGuiScale();
            this.multiplier = this.previousGameScale;
            this.fonts = new Font[fonts.length];

            for (int i = 0; i < fonts.length; i++) {
                this.fonts[i] = fonts[i].deriveFont(sizePx * this.multiplier);
            }

            initialized = true;
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    private Glyph locateGlyph(char glyph) {
        LOCK.readLock().lock();
        try {
            for (GlyphMap map : maps) {
                if (map.contains(glyph)) {
                    return map.getGlyph(glyph);
                }
            }
        } finally {
            LOCK.readLock().unlock();
        }

        int base = charsPerPage * (int) Math.floor((double) glyph / (double) charsPerPage);
        GlyphMap map = new GlyphMap(this.fonts, (char) base, (char) (base + charsPerPage), padding);
        LOCK.writeLock().lock();

        try {
            map.generate();
            maps.add(map);
        } finally {
            LOCK.writeLock().unlock();
        }

        return map.getGlyph(glyph);
    }

    @SneakyThrows @Override
    public void close() {
        LOCK.writeLock().lock();
        try {
            for (GlyphMap map : maps) map.destroy();
            maps.clear();

            initialized = false;
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    public record DrawEntry(float atX, float atY, int argb, Glyph toDraw) { }
}
