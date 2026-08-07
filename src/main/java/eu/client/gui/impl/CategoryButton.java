package eu.client.gui.impl;

import eu.client.EUClient;
import eu.client.gui.ClickGuiScreen;
import eu.client.gui.api.Button;
import eu.client.gui.api.Frame;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.CategorySetting;
import eu.client.utils.graphics.Renderer2D;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.awt.*;

public class CategoryButton extends Button {
    private final CategorySetting setting;
    // Non-null for categories that pair with an "Enabled" toggle (e.g. HUDModule's
    // watermarkCategory/watermark) -- left-click flips this directly instead of expanding, right-
    // click still expands/collapses to edit the rest of the category's settings. Categories with
    // no such pairing (plain grouping, e.g. "Targets") keep the old either-click-expands behavior.
    private final BooleanSetting enableSetting;

    public CategoryButton(CategorySetting setting, BooleanSetting enableSetting, Frame parent, int height) {
        super(setting, parent, height, setting.getDescription());
        this.setting = setting;
        this.enableSetting = enableSetting;
    }

    @Override
    public void render(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Setting background
        Renderer2D.renderQuad(context, getX() + getPadding() + 1, getY(), getX() + getWidth() - getPadding() - 1, getY() + getHeight() - 1, new Color(0, 0, 0, 40));

        Color accentColor = ClickGuiScreen.getButtonColor(getY(), 180);
        if (enableSetting != null && enableSetting.getValue()) {
            // Full-strength left bar (matches BooleanButton's own "on" indicator) when this
            // category's Enabled toggle is on -- otherwise the thinner page-header accent line.
            Renderer2D.renderQuad(context, getX() + getPadding() + 1, getY() + 1, getX() + getPadding() + 3, getY() + getHeight() - 2, ClickGuiScreen.getButtonColor(getY(), 220));
        } else {
            Renderer2D.renderQuad(context, getX() + getPadding() + 1, getY() + 2, getX() + getPadding() + 2, getY() + getHeight() - 3, accentColor);
        }

        EUClient.FONT_MANAGER.drawTextWithShadow(context, setting.getTag(), getX() + getTextPadding() + 4, getY() + 2, accentColor);
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
