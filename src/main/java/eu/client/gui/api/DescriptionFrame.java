package eu.client.gui.api;

import lombok.Getter;
import lombok.Setter;
import eu.client.EUClient;
import eu.client.gui.ClickGuiScreen;
import eu.client.modules.impl.core.ClickGuiModule;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.graphics.Renderer2D;
import eu.client.utils.text.FormattingUtils;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.awt.*;
import java.util.List;

@Getter @Setter
public class DescriptionFrame {
    private String description = "";
    private int x, y, width, height, dragX = 0, dragY = 0, textPadding = 3;
    private boolean dragging = false;

    public DescriptionFrame(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public void render(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        if(dragging) {
            setX(mouseX - dragX);
            setY(mouseY - dragY);
        }

        // Header matching Frame.java
        Renderer2D.renderQuad(context, x, y, x + width, y + height, new Color(20, 20, 25, 200));
        Color accentColor = ClickGuiScreen.getButtonColor(y, 200);
        Renderer2D.renderQuad(context, x, y + height - 1, x + width, y + height, accentColor);
        EUClient.FONT_MANAGER.drawTextWithShadow(context, "Description", x + textPadding, y + 2, Color.WHITE);

        if(!description.isEmpty()) {
            List<String> wrappedText = FormattingUtils.wrapText(description, width - textPadding*2);
            ClickGuiModule clickGui = EUClient.MODULE_MANAGER.getModule(ClickGuiModule.class);
            boolean shaderFill = !clickGui.fillMode.getValue().equalsIgnoreCase("Default");
            int contentHeight = (wrappedText.size() * EUClient.FONT_MANAGER.getHeight()) + 4;

            if (shaderFill) {
                int alpha = Math.round(clickGui.neekeriOpacity.getValue().floatValue() / 100.0f * 255.0f);
                context.enableScissor(x, y + height, x + width, y + height + contentHeight);
                eu.client.utils.graphics.NeekeriFill.fill(context, x, y + height, width, contentHeight, alpha);
                context.disableScissor();
            } else {
                Color color = clickGui.color.getColor();
                Renderer2D.renderQuad(context, x, y + height, x + width, y + height + contentHeight, clickGui.isRainbow() ? new Color(0, 0, 0, 100) : new Color((int) (color.getRed()*0.3), (int) (color.getGreen()*0.3), (int) (color.getBlue()*0.3), 100));
            }

            int i = 0;
            for(String s : wrappedText) {
                EUClient.FONT_MANAGER.drawTextWithShadow(context, s, x + textPadding, y + height + 2 + (EUClient.FONT_MANAGER.getHeight()*i), Color.WHITE);
                i++;
            }
        }
    }

    public void mouseClicked(double mouseX, double mouseY, int button) {
        if(isHovering(mouseX, mouseY)) {
            if(button == 0) {
                dragging = true;
                dragX = (int) (mouseX - getX());
                dragY = (int) (mouseY - getY());
            }
        }
    }

    public void mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            dragging = false;
        }
    }

    public boolean isHovering(double mouseX, double mouseY) {
        return x <= mouseX && y <= mouseY && x + width > mouseX && y + height > mouseY;
    }
}
