package eu.client.gui.impl;

import eu.client.EUClient;
import eu.client.gui.ClickGuiScreen;
import eu.client.gui.api.Button;
import eu.client.gui.api.Frame;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.CategorySetting;
import eu.client.utils.animations.Animation;
import eu.client.utils.animations.Easing;
import eu.client.utils.graphics.Renderer2D;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.awt.*;

public class CategoryButton extends Button {
    private final CategorySetting setting;
    private final BooleanSetting enableSetting;
    // Was a flat instant color/text swap (reported: "tắt bật mấy cái Place, Break, BasePlace vẫn
    // không có animation gì hết") -- same left-to-right fill sweep BooleanButton already has, just
    // never ported here. Starts at the settled value so opening the GUI doesn't animate.
    private final Animation fillAnim;

    public CategoryButton(CategorySetting setting, BooleanSetting enableSetting, Frame parent, int height) {
        super(setting, parent, height, setting.getDescription());
        this.setting = setting;
        this.enableSetting = enableSetting;
        float start = enableSetting != null && enableSetting.getValue() ? 1f : 0f;
        this.fillAnim = new Animation(start, start, 180, Easing.Method.EASE_OUT_CUBIC);
    }

    @Override
    public void render(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Setting background
        Renderer2D.renderQuad(context, getX() + getPadding() + 1, getY(), getX() + getWidth() - getPadding() - 1, getY() + getHeight() - 1, new Color(0, 0, 0, 40));

        float fFactor = fillAnim.get(enableSetting != null && enableSetting.getValue() ? 1f : 0f);
        if (fFactor > 0.001f) {
            int fillRight = getX() + getPadding() + 1 + Math.round((getWidth() - (getPadding() + 1) * 2) * fFactor);
            Color accentColor = ClickGuiScreen.getButtonColor(getY(), Math.round(100 * fFactor));
            Renderer2D.renderQuad(context, getX() + getPadding() + 1, getY(), fillRight, getY() + getHeight() - 1, accentColor);
        }

        Color textColor = (enableSetting != null && enableSetting.getValue()) ? Color.WHITE : ClickGuiScreen.getButtonColor(getY(), 180);
        EUClient.FONT_MANAGER.drawTextWithShadow(context, setting.getTag(), getX() + getTextPadding() + 4, getY() + 2, textColor);
        EUClient.FONT_MANAGER.drawTextWithShadow(context, setting.isOpen() ? "-" : "+", getX() + getWidth() - getTextPadding() - 1 - EUClient.FONT_MANAGER.getWidth(setting.isOpen() ? "-" : "+"), getY() + 2, Color.WHITE);
    }

    @Override
    public void mouseClicked(double mouseX, double mouseY, int button) {
        if (!isHovering(mouseX, mouseY)) return;

        if (enableSetting != null && button == 0) {
            enableSetting.setValue(!enableSetting.getValue());
            playClickSound();
        } else if (button == 1 || (enableSetting == null && button == 0)) {
            setting.setOpen(!setting.isOpen());
            playClickSound();
        }
    }
}
