package eu.client.gui.impl;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import eu.client.EUClient;
import eu.client.gui.ClickGuiScreen;
import eu.client.gui.api.Button;
import eu.client.gui.api.ExpandableRow;
import eu.client.gui.api.Frame;
import eu.client.managers.HudElementRegistry;
import eu.client.settings.Setting;
import eu.client.settings.impl.*;
import eu.client.utils.animations.Animation;
import eu.client.utils.animations.Easing;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.graphics.Renderer2D;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

@Getter @Setter
public class HudElementButton extends Button implements ExpandableRow {
    private final HudElementRegistry.Element element;
    
    @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private boolean open = false;
    private final ArrayList<Button> buttons = new ArrayList<>();
    private String searchQuery = "";

    private final Animation openAnim = new Animation(180, Easing.Method.EASE_OUT_QUAD);
    private int revealHeight = 0;
    private final Animation fillAnim;

    @Override
    public boolean isOpen() {
        return element.category() != null ? element.category().isOpen() : open;
    }

    @Override
    public float getOpenAmount() {
        return element.category() != null ? element.category().getOpenAmount() : openAnim.get(open ? 1f : 0f);
    }

    @Override
    public String getRowName() {
        return element.name();
    }

    public HudElementButton(HudElementRegistry.Element element, List<Setting> settings, Frame parent, int height) {
        super(parent, height, element.name());
        this.element = element;
        float start = element.enabled().getValue() ? 1f : 0f;
        this.fillAnim = new Animation(start, start, 180, Easing.Method.EASE_OUT_QUAD);

        for (Setting setting : settings) {
            if (setting instanceof BooleanSetting s) buttons.add(new BooleanButton(s, parent, height));
            else if (setting instanceof NumberSetting s) buttons.add(new NumberButton(s, parent, height));
            else if (setting instanceof CategorySetting s) buttons.add(new CategoryButton(s, null, parent, height));
            else if (setting instanceof BindSetting s) buttons.add(new BindButton(s, parent, height));
            else if (setting instanceof ModeSetting s) buttons.add(new ModeButton(s, parent, height));
            else if (setting instanceof WhitelistSetting s) buttons.add(new WhitelistButton(s, parent, height));
            else if (setting instanceof StringSetting s) buttons.add(new StringButton(s, parent, height));
            else if (setting instanceof ColorSetting s) buttons.add(new ColorButton(s, parent, height));
        }
    }

    // Đồng bộ vị trí vật lý của thẻ con để nhận diện vùng bấm (hitbox) chính xác
    private void updateChildBounds() {
        int currentY = getY() + getHeight();
        for (Button b : buttons) {
            b.setX(getX());
            b.setY(currentY);
            if (b.isVisible()) {
                currentY += b.getHeight();
            }
        }
    }

    @Override
    public void render(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        updateChildBounds();

        if (this.isHovering(mouseX, mouseY) && EUClient.CLICK_GUI.getDescriptionFrame().getDescription().isEmpty())
            EUClient.CLICK_GUI.getDescriptionFrame().setDescription(this.getDescription());

        Color bgColor = isHovering(mouseX, mouseY) ? new Color(255, 255, 255, 15) : new Color(0, 0, 0, 0);
        if (bgColor.getAlpha() > 0) {
            Renderer2D.renderQuad(context, getX() + getPadding(), getY(), getX() + getWidth() - getPadding(), getY() + getHeight() - 1, bgColor);
        }

        float fillProgress = fillAnim.get(element.enabled().getValue() ? 1f : 0f);
        if (fillProgress > 0.001f) {
            eu.client.modules.impl.core.ClickGuiModule clickGui = EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.core.ClickGuiModule.class);
            boolean shaderFill = !clickGui.fillMode.getValue().equalsIgnoreCase("Default");
            int fillRight = getX() + getPadding() + Math.round((getWidth() - getPadding() * 2) * fillProgress);
            if (shaderFill) {
                int alpha = Math.round(clickGui.neekeriOpacity.getValue().floatValue() / 100.0f * 255.0f);
                context.enableScissor(getX() + getPadding(), getY(), fillRight, getY() + getHeight() - 1);
                eu.client.utils.graphics.NeekeriFill.fill(context, getX() + getPadding(), getY(), getWidth() - getPadding() * 2, getHeight() - 1, alpha);
                context.disableScissor();
            } else {
                Color fillColor = ColorUtils.getColor(clickGui.color.getColor(), 90);
                Renderer2D.renderQuad(context, getX() + getPadding(), getY(), fillRight, getY() + getHeight() - 1, fillColor);
            }
        }

        Color separator = ClickGuiScreen.getButtonColor(getY(), 60);
        Renderer2D.renderQuad(context, getX() + getPadding(), getY() + getHeight() - 1, getX() + getWidth() - getPadding(), getY() + getHeight(), separator);

        int textX = getX() + getTextPadding() + (element.enabled().getValue() ? 1 : 0);
        int textY = getY() + 2;
        String prefix = element.enabled().getValue() ? "" : net.minecraft.ChatFormatting.GRAY.toString();
        EUClient.FONT_MANAGER.drawTextWithShadow(context, prefix + element.name(), textX, textY, Color.WHITE);

        if (revealHeight > 0) {
            int clipTop = getY() + getHeight();
            context.enableScissor(getX(), clipTop, getX() + getWidth(), clipTop + revealHeight);
            for (Button button : buttons) {
                if (!button.isVisible()) continue;
                button.render(context, mouseX, mouseY, delta);
                if (button.isHovering(mouseX, mouseY) && EUClient.CLICK_GUI.getDescriptionFrame().getDescription().isEmpty())
                    EUClient.CLICK_GUI.getDescriptionFrame().setDescription(button.getDescription());
            }
            context.disableScissor();
        }
    }

    @Override
    public void mouseClicked(double mouseX, double mouseY, int button) {
        updateChildBounds();

        if (isHovering(mouseX, mouseY)) {
            if (button == 0) {
                element.enabled().setValue(!element.enabled().getValue());
                playClickSound();
            } else if (button == 1) {
                if (element.category() != null) {
                    element.category().setOpen(!element.category().isOpen());
                } else {
                    open = !open;
                    openAnim.setEasing(open ? Easing.Method.EASE_OUT_QUAD : Easing.Method.EASE_IN_QUAD);
                }
                playClickSound();
            }
        }

        if (isOpen()) {
            for (Button b : buttons) {
                if (!b.isVisible()) continue;
                b.mouseClicked(mouseX, mouseY, button); // Cho phép nhận cả click trái lẫn click phải (chỉnh giá trị số)
            }
        }
    }

    @Override
    public void mouseReleased(double mouseX, double mouseY, int button) {
        updateChildBounds();
        if (isOpen()) {
            for (Button b : buttons) {
                if (!b.isVisible()) continue;
                b.mouseReleased(mouseX, mouseY, button);
            }
        }
    }

    @Override
    public void mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        updateChildBounds();
        if (isOpen()) {
            for (Button b : buttons) {
                if (!b.isVisible()) continue;
                b.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
            }
        }
    }

    @Override
    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isOpen()) for (Button b : buttons) {
            if (!b.isVisible()) continue;
            b.keyPressed(keyCode, scanCode, modifiers);
        }
    }

    @Override
    public void charTyped(char chr, int modifiers) {
        if (isOpen()) for (Button b : buttons) {
            if (!b.isVisible()) continue;
            b.charTyped(chr, modifiers);
        }
    }
}