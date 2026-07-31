package eu.client.gui;

import eu.client.EUClient;
import eu.client.managers.HudElementRegistry;
import eu.client.modules.impl.core.HUDEditorModule;
import eu.client.settings.impl.PositionSetting;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.graphics.Renderer2D;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.awt.*;
import java.util.Map;

/**
 * Left-click and drag a highlighted HUD element to reposition it; right-click toggles it on/off.
 * Dragging is clamped so the element's last-known bounding box can never leave the screen.
 */
public class HUDEditorScreen extends Screen {
    private String draggingElement = null;
    private float dragOffsetX, dragOffsetY;

    public HUDEditorScreen() {
        super(Component.literal(EUClient.MOD_ID + "-hud-editor"));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Intentionally no dimming -- the whole point is to see the real HUD while positioning it.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        for (Map.Entry<String, HudElementRegistry.Element> entry : HudElementRegistry.getElements().entrySet()) {
            String name = entry.getKey();
            HudElementRegistry.Element element = entry.getValue();
            float[] bounds = HudElementRegistry.getBounds(name);
            if (bounds == null) continue;

            boolean hovering = mouseX >= bounds[0] && mouseX <= bounds[2] && mouseY >= bounds[1] && mouseY <= bounds[3];
            boolean enabled = element.enabled().getValue();
            boolean dragging = name.equals(draggingElement);

            Color accent = enabled ? ColorUtils.getGlobalColor() : new Color(150, 150, 150);
            int fill = dragging ? 70 : hovering ? 45 : 25;

            Renderer2D.renderQuad(context, bounds[0] - 1, bounds[1] - 1, bounds[2] + 1, bounds[3] + 1, new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), fill));
            Renderer2D.renderOutline(context, bounds[0] - 1, bounds[1] - 1, bounds[2] + 1, bounds[3] + 1, new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), enabled ? 220 : 120));

            if (hovering || dragging) {
                String label = name + (enabled ? "" : " (disabled)");
                float labelX = bounds[0];
                float labelY = bounds[1] - EUClient.FONT_MANAGER.getHeight() - 2;
                Renderer2D.renderQuad(context, labelX - 1, labelY - 1, labelX + EUClient.FONT_MANAGER.getWidth(label) + 1, labelY + EUClient.FONT_MANAGER.getHeight(), new Color(0, 0, 0, 180));
                EUClient.FONT_MANAGER.drawTextWithShadow(context, label, (int) labelX, (int) labelY, accent);
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x(), mouseY = event.y();

        for (Map.Entry<String, HudElementRegistry.Element> entry : HudElementRegistry.getElements().entrySet()) {
            String name = entry.getKey();
            HudElementRegistry.Element element = entry.getValue();
            float[] bounds = HudElementRegistry.getBounds(name);
            if (bounds == null) continue;
            if (mouseX < bounds[0] || mouseX > bounds[2] || mouseY < bounds[1] || mouseY > bounds[3]) continue;

            if (event.button() == 1) {
                element.enabled().setValue(!element.enabled().getValue());
            } else if (event.button() == 0) {
                draggingElement = name;
                dragOffsetX = (float) mouseX - element.offset().getX();
                dragOffsetY = (float) mouseY - element.offset().getY();
            }
            return true;
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (draggingElement != null) {
            HudElementRegistry.Element element = HudElementRegistry.getElements().get(draggingElement);
            if (element != null) {
                PositionSetting offset = element.offset();
                offset.set((float) event.x() - dragOffsetX, (float) event.y() - dragOffsetY);
                HudElementRegistry.clamp(draggingElement, this.width, this.height);
            }
            return true;
        }

        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingElement = null;
        return super.mouseReleased(event);
    }

    @Override
    public void onClose() {
        super.onClose();
        EUClient.MODULE_MANAGER.getModule(HUDEditorModule.class).setToggled(false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
