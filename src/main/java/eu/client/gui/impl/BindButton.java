package eu.client.gui.impl;

import eu.client.EUClient;
import eu.client.gui.api.Button;
import eu.client.gui.api.Frame;
import eu.client.settings.impl.BindSetting;
import eu.client.utils.graphics.Renderer2D;
import eu.client.utils.input.KeyboardUtils;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.ChatFormatting;
import org.lwjgl.glfw.GLFW;

import java.awt.*;

public class BindButton extends Button {
    private final BindSetting setting;
    private boolean listening = false;

    // ClickGuiScreen claims Esc for its own close-request before any button ever sees a
    // keyPressed call -- while a BindButton is listening, Esc has to cancel the bind capture
    // instead, so ClickGuiScreen checks this before deciding whether to close.
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
        // Setting background
        Renderer2D.renderQuad(context, getX() + getPadding() + 1, getY(), getX() + getWidth() - getPadding() - 1, getY() + getHeight() - 1, new Color(0, 0, 0, 40));

        // The tag itself shows the mode (Hold/ReverseHold) in place of "Bind" once set -- not as
        // a suffix next to the key -- so the label always reads as "what this bind currently
        // does" rather than "Bind: F (Hold)".
        String tag = setting.getMode().equals("Bind") ? setting.getTag() : setting.getMode();
        EUClient.FONT_MANAGER.drawTextWithShadow(context, tag, getX() + getTextPadding() + 3, getY() + 2, Color.WHITE);

        String bind = listening ? "..." : KeyboardUtils.getKeyName(setting.getValue());
        EUClient.FONT_MANAGER.drawTextWithShadow(context, ChatFormatting.GRAY + bind, getX() + getWidth() - getTextPadding() - 1 - EUClient.FONT_MANAGER.getWidth(bind), getY() + 2, Color.WHITE);
    }

    @Override
    public void mouseClicked(double mouseX, double mouseY, int button) {
        if (!isHovering(mouseX, mouseY)) return;

        // While listening, any mouse button (including the one that started listening, if the
        // user meant to bind to that button itself) assigns it as the bind.
        if (listening) {
            if (button >= 0 && button <= 4) {
                setting.setValue(-button - 1);
                stopListening();
            }
            return;
        }

        if (button == 0) {
            // Left click: start listening for a new key/button. Press Esc while listening to
            // clear the bind back to None instead.
            listening = true;
            listenersActive++;
            playClickSound();
        } else if (button == 1) {
            // Right click: cycle Bind -> Hold -> ReverseHold -> Bind.
            setting.cycleMode();
            playClickSound();
        }
    }

    @Override
    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        if(listening) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_DELETE) {
                setting.setValue(0);
            } else {
                setting.setValue(keyCode);
            }
            stopListening();
        }
    }

    private void stopListening() {
        listening = false;
        listenersActive--;
    }
}
