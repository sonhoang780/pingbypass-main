package eu.client.gui.impl;

import eu.client.EUClient;
import eu.client.gui.ClickGuiScreen;
import eu.client.gui.api.Button;
import eu.client.gui.api.Frame;
import eu.client.settings.impl.BindSetting;
import eu.client.utils.animations.Animation;
import eu.client.utils.animations.Easing;
import eu.client.utils.graphics.Renderer2D;
import eu.client.utils.input.KeyboardUtils;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.ChatFormatting;
import org.lwjgl.glfw.GLFW;

import java.awt.*;

public class BindButton extends Button {
    private final BindSetting setting;
    private boolean listening = false;
    private final Animation hoverAnim = new Animation(150, Easing.Method.EASE_OUT_CUBIC);
    private final Animation listenAnim = new Animation(150, Easing.Method.EASE_OUT_CUBIC);

    private static int listenersActive = 0;

    public static boolean isAnyListening() {
        return listenersActive > 0;
    }

    public BindButton(BindSetting setting, Frame parent, int height) {
        super(setting, parent, height, setting.getDescription());
        this.setting = setting;
    }

    @Override
    public void render(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        boolean hovered = isHovering(mouseX, mouseY);
        float hFactor = hoverAnim.get(hovered ? 1f : 0f);
        float lFactor = listenAnim.get(listening ? 1f : 0f);

        // Setting background
        Renderer2D.renderQuad(context, getX() + getPadding() + 1, getY(), getX() + getWidth() - getPadding() - 1, getY() + getHeight() - 1, new Color(0, 0, 0, 40));

        if (hFactor > 0.001f || lFactor > 0.001f) {
            int alpha = Math.min(255, (int) (20 * hFactor + 50 * lFactor));
            Color highlight = listening ? ClickGuiScreen.getButtonColor(getY(), alpha) : new Color(255, 255, 255, alpha);
            Renderer2D.renderQuad(context, getX() + getPadding() + 1, getY(), getX() + getWidth() - getPadding() - 1, getY() + getHeight() - 1, highlight);
        }

        String tag = setting.getMode().equals("Bind") ? setting.getTag() : setting.getMode();
        float textX = getX() + getTextPadding() + 3 + (hFactor * 1.5f);
        EUClient.FONT_MANAGER.drawTextWithShadow(context, tag, (int) textX, getY() + 2, Color.WHITE);

        String bind = listening ? "..." : KeyboardUtils.getKeyName(setting.getValue());
        Color bindColor = listening ? Color.YELLOW : Color.WHITE;
        int bindX = getX() + getWidth() - getTextPadding() - 1 - EUClient.FONT_MANAGER.getWidth(bind) - (int) (lFactor * 2f);
        EUClient.FONT_MANAGER.drawTextWithShadow(context, (listening ? ChatFormatting.YELLOW : ChatFormatting.GRAY) + bind, bindX, getY() + 2, bindColor);
    }

    @Override
    public void mouseClicked(double mouseX, double mouseY, int button) {
        if (!isHovering(mouseX, mouseY)) return;

        if (listening) {
            if (button >= 0 && button <= 4) {
                setting.setValue(-button - 1);
                stopListening();
            }
            return;
        }

        if (button == 0) {
            startListening();
            playClickSound();
        } else if (button == 1) {
            cycleMode();
            playClickSound();
        }
    }

    @Override
    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!listening) return;

        if (keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE || keyCode == GLFW.GLFW_KEY_ESCAPE) {
            setting.setValue(0);
            stopListening();
            return;
        }

        setting.setValue(keyCode);
        stopListening();
    }

    public void keyPressed(int key) {
        keyPressed(key, 0, 0);
    }

    private void startListening() {
        listening = true;
        listenersActive++;
    }

    private void stopListening() {
        if (listening) {
            listening = false;
            listenersActive = Math.max(0, listenersActive - 1);
        }
    }

    private void cycleMode() {
        switch (setting.getMode()) {
            case "Bind" -> setting.setMode("Hold");
            case "Hold" -> setting.setMode("ReverseHold");
            case "ReverseHold" -> setting.setMode("Bind");
        }
    }
}
