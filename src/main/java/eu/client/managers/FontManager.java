package eu.client.managers;

import lombok.Getter;
import lombok.Setter;
import eu.client.EUClient;
import eu.client.modules.impl.core.FontModule;
import eu.client.utils.IMinecraft;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.font.FontRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.rendertype.RenderType;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;

@Getter @Setter
public class FontManager implements IMinecraft {
    private FontRenderer fontRenderer;

    private boolean useCustomFont() {
        FontModule module = EUClient.MODULE_MANAGER.getModule(FontModule.class);
        return module != null && module.isToggled() && module.customFont.getValue() && fontRenderer != null;
    }

    private boolean shadowEnabled() {
        return !EUClient.MODULE_MANAGER.getModule(FontModule.class).shadowMode.getValue().equalsIgnoreCase("None");
    }

    public void drawText(GuiGraphicsExtractor context, String text, int x, int y, Color color) {
        if (useCustomFont()) {
            fontRenderer.drawString(context, text, x, y, color.getRGB(), false);
        } else {
            context.text(mc.font, text, x, y, color.getRGB(), false);
        }
    }

    public void drawTextWithShadow(GuiGraphicsExtractor context, String text, int x, int y, Color color) {
        if (useCustomFont()) {
            if (shadowEnabled()) fontRenderer.drawString(context, text, x + getShadowOffset(), y + getShadowOffset(), color.getRGB(), true);
            fontRenderer.drawString(context, text, x, y, color.getRGB(), false);
        } else {
            context.text(mc.font, text, x, y, color.getRGB(), true);
        }
    }

    public void drawText(GuiGraphicsExtractor context, FormattedCharSequence text, int x, int y, Color color) {
        if (useCustomFont()) {
            fontRenderer.drawText(context, text, x, y, color.getRGB(), false);
        } else {
            context.text(mc.font, text, x, y, color.getRGB(), false);
        }
    }

    public void drawTextWithShadow(GuiGraphicsExtractor context, FormattedCharSequence text, int x, int y, Color color) {
        if (useCustomFont()) {
            if (shadowEnabled()) fontRenderer.drawText(context, text, x + getShadowOffset(), y + getShadowOffset(), color.getRGB(), true);
            fontRenderer.drawText(context, text, x, y, color.getRGB(), false);
        } else {
            context.text(mc.font, text, x, y, color.getRGB(), true);
        }
    }

    // PORT (26.2): MultiBufferSource removed entirely, and Font.drawInBatch (the old vanilla batch
    // API this called into) is GONE too, not just renamed -- replaced by Font.prepareText(...) ->
    // PreparedText.visit(GlyphVisitor), a per-glyph callback (real 26.2 source, read in full: see
    // net/minecraft/client/renderer/feature/TextFeatureRenderer.java's GlyphRenderer for vanilla's
    // own version of this exact pattern). No MultiBufferSource-equivalent buffer-source param
    // needed anymore -- own a small per-RenderType BufferBuilder here (growable ByteBufferBuilder,
    // glyph count isn't known upfront) and draw immediately once the whole string's been visited,
    // same immediate-mode pattern as Renderer3D.draw/EspShader.draw (see their PORT comments).
    public void drawTextWithShadow(PoseStack matrices, String text, int x, int y, Color color) {
        // Depth-less "see through" draw: the SEE_THROUGH DisplayMode (vanilla) and the GUI_TEXTURED
        // pipeline (custom font) both render without a depth test, so the old RenderSystem
        // enable/disableDepthTest wrapper (removed in the 1.21.5+ pipeline overhaul) is not needed.
        if (useCustomFont()) {
            if (shadowEnabled()) fontRenderer.drawString(matrices, text, x + getShadowOffset(), y + getShadowOffset(), color.getRGB(), true);
            fontRenderer.drawString(matrices, text, x, y, color.getRGB(), false);
        } else {
            Font.PreparedText prepared = mc.font.prepareText(text, x, y, color.getRGB(), true, 0);
            Map<RenderType, ByteBufferBuilder> owners = new HashMap<>();
            Map<RenderType, BufferBuilder> buffers = new HashMap<>();
            try {
                prepared.visit(new Font.GlyphVisitor() {
                    @Override
                    public void acceptRenderable(TextRenderable renderable) {
                        RenderType renderType = renderable.renderType(Font.DisplayMode.SEE_THROUGH);
                        BufferBuilder buffer = buffers.computeIfAbsent(renderType, rt -> {
                            ByteBufferBuilder byteBufferBuilder = new ByteBufferBuilder(256);
                            owners.put(rt, byteBufferBuilder);
                            return new BufferBuilder(byteBufferBuilder, rt.primitiveTopology(), rt.format());
                        });
                        renderable.render(matrices.last().pose(), buffer, LightCoordsUtil.FULL_BRIGHT, false);
                    }
                });

                for (Map.Entry<RenderType, BufferBuilder> entry : buffers.entrySet()) {
                    MeshData mesh = entry.getValue().build();
                    eu.client.utils.graphics.Renderer3D.draw(entry.getKey(), mesh);
                }
            } finally {
                for (ByteBufferBuilder byteBufferBuilder : owners.values()) byteBufferBuilder.close();
            }
        }
    }

    public void drawTextWithOutline(GuiGraphicsExtractor context, String text, int x, int y, Color color, Color outlineColor) {
        if (useCustomFont()) {
            fontRenderer.drawString(context, FontRenderer.stripControlCodes(text), x + 0.5f, y - 0.5f, outlineColor.getRGB(), false);
            fontRenderer.drawString(context, FontRenderer.stripControlCodes(text), x - 0.5f, y + 0.5f, outlineColor.getRGB(), false);
            fontRenderer.drawString(context, FontRenderer.stripControlCodes(text), x + 0.5f, y + 0.5f, outlineColor.getRGB(), false);
            fontRenderer.drawString(context, FontRenderer.stripControlCodes(text), x - 0.5f, y - 0.5f, outlineColor.getRGB(), false);

            fontRenderer.drawString(context, text, x, y, color.getRGB(), false);
        } else {
            // 8x outline replaces the removed Font.drawWithOutline; draw the outline copies then the
            // primary glyphs via GuiGraphicsExtractor so the text stays in the retained GUI batch.
            for (int xo = -1; xo <= 1; xo++) {
                for (int yo = -1; yo <= 1; yo++) {
                    if (xo != 0 || yo != 0) {
                        context.text(mc.font, text, x + xo, y + yo, outlineColor.getRGB(), false);
                    }
                }
            }
            context.text(mc.font, text, x, y, color.getRGB(), false);
        }
    }

    public void drawRainbowString(GuiGraphicsExtractor context, String string, int x, int y, long offset) {
        MutableComponent builder = Component.empty();

        int[] i = {0};
        Component.literal(string).getVisualOrderText().accept((index, style, codePoint) -> {
            MutableComponent text = Component.empty();

            if (style.getColor() == null) {
                long index1 = (long) i[0] * offset;
                Color color = ColorUtils.getOffsetRainbow(index1);
                text.append(Component.literal(String.valueOf(Character.toChars(codePoint))).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(color.getRGB()))));
            } else {
                text.append(Component.literal(String.valueOf(Character.toChars(codePoint))).setStyle(style));
            }

            builder.append(text);
            i[0]++;

            return true;
        });

        EUClient.FONT_MANAGER.drawTextWithShadow(context, builder.getVisualOrderText(), x, y, Color.WHITE);
    }

    public int getWidth(String text) {
        if (useCustomFont()) {
            // WidthOffset must NOT leak into the reported width -- with Global on, Font.width()
            // (TextRendererMixin) IS this method, and vanilla uses that measured width for every
            // layout decision (chat suggestion ghost-text position, tooltip/popup sizing, centered
            // labels...), all computed against where the glyphs are ACTUALLY drawn (submitGlyphs,
            // which never applied this offset). Baking a fake few pixels into just the measurement
            // desynced every one of those from the real rendered text, compounding worse the more
            // times a widget re-measures a growing substring (reported: suggestion text drifting
            // further right than the typed text it's supposed to continue). WidthOffset is a
            // render-position nudge, not a real width delta -- it never belonged here.
            return (int) fontRenderer.getTextWidth(text);
        } else {
            return mc.font.width(text);
        }
    }

    public int getHeight() {
        if (useCustomFont()) {
            return (int) fontRenderer.getHeight() + EUClient.MODULE_MANAGER.getModule(FontModule.class).heightOffset.getValue().intValue();
        } else {
            return mc.font.lineHeight;
        }
    }

    public float getShadowOffset() {
        if (EUClient.MODULE_MANAGER.getModule(FontModule.class).shadowMode.getValue().equalsIgnoreCase("None")) {
            return 0.0f;
        } else if (EUClient.MODULE_MANAGER.getModule(FontModule.class).shadowMode.getValue().equalsIgnoreCase("Custom")) {
            return EUClient.MODULE_MANAGER.getModule(FontModule.class).shadowOffset.getValue().floatValue();
        } else {
            return 1.0f;
        }
    }

    public boolean hasFont(String name) {
        for (String fontName : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) if (fontName.equalsIgnoreCase(name)) return true;
        return false;
    }
}
