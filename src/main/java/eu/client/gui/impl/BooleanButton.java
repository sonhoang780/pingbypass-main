package eu.client.gui.impl;

import eu.client.EUClient;
import eu.client.gui.ClickGuiScreen;
import eu.client.gui.api.Button;
import eu.client.gui.api.Frame;
import eu.client.settings.impl.BooleanSetting;
import eu.client.utils.animations.Animation;
import eu.client.utils.animations.Easing;
import eu.client.utils.graphics.Renderer2D;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.ChatFormatting;

import java.awt.*;

public class BooleanButton extends Button {
    private final BooleanSetting setting;
    private final Animation fillAnim;
    private final Animation hoverAnim;
    private final Animation textAnim;

    public BooleanButton(BooleanSetting setting, Frame parent, int height) {
        super(setting, parent, height, setting.getDescription());
        this.setting = setting;
        float start = setting.getValue() ? 1f : 0f;
        this.fillAnim = new Animation(start, start, 180, Easing.Method.EASE_OUT_CUBIC);
        this.hoverAnim = new Animation(150, Easing.Method.EASE_OUT_CUBIC);
        this.textAnim = new Animation(start, start, 150, Easing.Method.EASE_OUT_CUBIC);
    }

    @Override
    public void render(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        boolean hovered = isHovering(mouseX, mouseY);
        float hFactor = hoverAnim.get(hovered ? 1f : 0f);
        float fFactor = fillAnim.get(setting.getValue() ? 1f : 0f);
        float tFactor = textAnim.get((setting.getValue() || hovered) ? 1f : 0f);

        // Setting background
        Renderer2D.renderQuad(context, getX() + getPadding() + 1, getY(), getX() + getWidth() - getPadding() - 1, getY() + getHeight() - 1, new Color(0, 0, 0, 40));

        // Hover highlight
        if (hFactor > 0.001f) {
            Color hoverColor = new Color(255, 255, 255, (int) (20 * hFactor));
            Renderer2D.renderQuad(context, getX() + getPadding() + 1, getY(), getX() + getWidth() - getPadding() - 1, getY() + getHeight() - 1, hoverColor);
        }

        // Active fill with animation
        if (fFactor > 0.001f) {
            int fillRight = getX() + getPadding() + 1 + Math.round((getWidth() - (getPadding() + 1) * 2) * fFactor);
            Color accentColor = ClickGuiScreen.getButtonColor(getY(), Math.round(100 * fFactor));
            Renderer2D.renderQuad(context, getX() + getPadding() + 1, getY(), fillRight, getY() + getHeight() - 1, accentColor);
        }

        // Text animation (thụt ra thụt vào theo chuẩn Shoreline)
        float textX = getX() + getTextPadding() + 3 + (tFactor * 1.5f);
        EUClient.FONT_MANAGER.drawTextWithShadow(context, (setting.getValue() ? "" : ChatFormatting.GRAY) + setting.getTag(), (int) textX, getY() + 2, Color.WHITE);
    }

    @Override
    public void mouseClicked(double mouseX, double mouseY, int button) {
        if(isHovering(mouseX, mouseY) && button == 0) {
            setting.setValue(!setting.getValue());
            playClickSound();
        }
    }
}
