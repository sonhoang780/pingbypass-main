package eu.client.gui.impl;

import eu.client.EUClient;
import eu.client.gui.ClickGuiScreen;
import eu.client.gui.api.Button;
import eu.client.gui.api.Frame;
import eu.client.settings.impl.ModeSetting;
import eu.client.utils.animations.Animation;
import eu.client.utils.animations.Easing;
import eu.client.utils.graphics.Renderer2D;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.ChatFormatting;
import net.minecraft.util.Mth;

import java.awt.*;

public class ModeButton extends Button {
    private final ModeSetting setting;
    private boolean open = false;

    private final Animation openAnim = new Animation(200, Easing.Method.EASE_OUT_CUBIC);
    private final Animation hoverAnim = new Animation(150, Easing.Method.EASE_OUT_CUBIC);
    private final Animation textAnim = new Animation(150, Easing.Method.EASE_OUT_CUBIC);

    // Value slide -- old mode label slides out to the left while the new one slides in from the
    // right, same idea as BooleanButton's fill sweep but for the value text instead of a color bar
    // (reported: switching Logic NCP<->Grim in SpeedMine, or any multi-choice setting, just
    // snap-changed the text with no transition at all). lastValue/valueChangeTime are plain fields
    // (not another Animation instance) because we need BOTH the outgoing and incoming strings on
    // screen at once during the sweep, not just a single interpolated scalar.
    private static final int VALUE_SLIDE_MS = 180;
    private static final int VALUE_SLIDE_DISTANCE = 10;
    private String lastValue;
    private String previousValue;
    private long valueChangeTime;

    public ModeButton(ModeSetting setting, Frame parent, int height) {
        super(setting, parent, height, setting.getDescription());
        this.setting = setting;
        this.lastValue = setting.getValue();
    }

    private float getOpenAmount() {
        return openAnim.get(open ? 1f : 0f);
    }

    @Override
    public void render(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        boolean hovered = isHovering(mouseX, mouseY);
        float hFactor = hoverAnim.get(hovered ? 1f : 0f);
        float tFactor = textAnim.get(hovered ? 1f : 0f);

        // Setting background
        Renderer2D.renderQuad(context, getX() + getPadding() + 1, getY(), getX() + getWidth() - getPadding() - 1, getY() + getParent().getHeight() - 1, new Color(0, 0, 0, 40));

        if (hFactor > 0.001f) {
            Color hoverColor = new Color(255, 255, 255, (int) (20 * hFactor));
            Renderer2D.renderQuad(context, getX() + getPadding() + 1, getY(), getX() + getWidth() - getPadding() - 1, getY() + getParent().getHeight() - 1, hoverColor);
        }

        float textX = getX() + getTextPadding() + 3 + (tFactor * 1.5f);
        EUClient.FONT_MANAGER.drawTextWithShadow(context, setting.getTag(), (int) textX, getY() + 2, Color.WHITE);

        String currentValue = setting.getValue();
        if (!currentValue.equals(lastValue)) {
            previousValue = lastValue;
            lastValue = currentValue;
            valueChangeTime = System.currentTimeMillis();
        }

        float slideT = valueChangeTime == 0 ? 1f : Easing.ease(Easing.toDelta(valueChangeTime, VALUE_SLIDE_MS), Easing.Method.EASE_OUT_CUBIC);
        int valueRight = getX() + getWidth() - getTextPadding() - 1;

        if (slideT < 1f && previousValue != null) {
            int outOffset = Math.round(slideT * VALUE_SLIDE_DISTANCE);
            int outAlpha = Math.round(255 * (1f - slideT));
            String outText = ChatFormatting.GRAY + previousValue;
            EUClient.FONT_MANAGER.drawTextWithShadow(context, outText, valueRight - EUClient.FONT_MANAGER.getWidth(outText) - outOffset, getY() + 2, new Color(255, 255, 255, outAlpha));
        }

        int inOffset = Math.round((1f - slideT) * VALUE_SLIDE_DISTANCE);
        int inAlpha = Math.round(255 * slideT);
        String inText = ChatFormatting.GRAY + currentValue;
        EUClient.FONT_MANAGER.drawTextWithShadow(context, inText, valueRight - EUClient.FONT_MANAGER.getWidth(inText) + inOffset, getY() + 2, new Color(255, 255, 255, inAlpha));

        float openAmount = getOpenAmount();
        if (openAmount > 0.001f) {
            int visibleRows = Mth.clamp(Math.round(setting.getModes().size() * openAmount), 0, setting.getModes().size());
            int i = 0;
            for (String s : setting.getModes()) {
                if (i >= visibleRows) break;
                EUClient.FONT_MANAGER.drawTextWithShadow(context, (setting.getValue().equals(s) ? "" : ChatFormatting.GRAY) + s, getX() + getTextPadding() + 2, getY() + getParent().getHeight() + i * getParent().getHeight() + 2, Color.WHITE);
                i++;
            }
        }
    }

    @Override
    public void mouseClicked(double mouseX, double mouseY, int button) {
        if(isHovering(mouseX, mouseY)) {
            if(button == 0) {
                int index = setting.getModes().indexOf(setting.getValue());
                if (index < setting.getModes().size() - 1) {
                    setting.setValue(setting.getModes().get(index + 1));
                } else {
                    setting.setValue(setting.getModes().get(0));
                }
                playClickSound();
            } else if(button == 1) {
                this.open = !this.open;
                playClickSound();
            }
        }

        if(this.open) {
            float openAmount = getOpenAmount();
            int visibleRows = Mth.clamp(Math.round(setting.getModes().size() * openAmount), 0, setting.getModes().size());
            for(int i = 0; i < visibleRows; i++) {
                String s = setting.getModes().get(i);
                int rowY = getY() + getParent().getHeight() + i * getParent().getHeight();
                boolean hovered = mouseX >= getX() + getPadding() && mouseX <= getX() + getWidth() - getPadding()
                        && mouseY >= rowY && mouseY < rowY + getParent().getHeight();
                if(hovered && button == 0) {
                    setting.setValue(s);
                    playClickSound();
                }
            }
        }
    }

    @Override
    public int getHeight() {
        return Math.round(getParent().getHeight() + (setting.getModes().size() * getParent().getHeight() * getOpenAmount()));
    }
}
