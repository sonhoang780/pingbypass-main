package eu.client.gui;

import eu.client.EUClient;
import eu.client.gui.api.Button;
import eu.client.gui.api.Frame;
import eu.client.gui.impl.HudElementButton;
import eu.client.managers.HudElementRegistry;
import eu.client.modules.impl.core.HUDEditorModule;
import eu.client.modules.impl.core.HUDModule;
import eu.client.settings.Setting;
import eu.client.settings.impl.CategorySetting;
import eu.client.settings.impl.PositionSetting;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.graphics.Renderer2D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.awt.*;
import java.util.List;
import java.util.Map;

/**
 * Left-click and drag a highlighted HUD element to reposition it; right-click toggles it on/off.
 * Dragging is clamped so the element's last-known bounding box can never leave the screen.
 * <p>
 * A docked column (top-left, same look/animation/shader as a ClickGui category -- built out of
 * the exact same {@link Frame}/{@link HudElementButton} machinery, see {@link eu.client.gui.api.ExpandableRow})
 * lists every registered HUD element so its OWN settings (not just enable/position) can be edited
 * without leaving this screen.
 */
public class HUDEditorScreen extends Screen {
    private String draggingElement = null;
    private float dragOffsetX, dragOffsetY;

    private final Frame elementsFrame;

    public HUDEditorScreen() {
        super(Component.literal(EUClient.MOD_ID + "-hud-editor"));

        // (3,3) (top-left corner) used to sit right on top of Watermark/Welcomer's own default
        // render position -- both fighting for the same screen corner. Docked lower/inward
        // instead, clear of every default HUD element position. Fractions of the GUI-scaled
        // window (not fixed pixels) so it lands in the same relative spot at any GUI scale.
        int guiWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int guiHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        int frameX = Math.round(guiWidth * 0.23f);
        int frameY = Math.round(guiHeight * 0.14f);

        elementsFrame = new Frame("HUD", List.of(), frameX, frameY, 110, 13);
        List<Setting> hudSettings = EUClient.MODULE_MANAGER.getModule(HUDModule.class).getSettings();
        for (HudElementRegistry.Element element : HudElementRegistry.getElements().values()) {
            // The "Enabled" BooleanSetting behind each category (e.g. HUDModule's own
            // watermarkCategory/watermark pair) IS element.enabled() -- already exposed as this
            // row's own left-click toggle (see HudElementButton.mouseClicked), so listing it
            // again as a child row is pure duplication. Same skip ModuleButton's constructor
            // already does for the same reason, just filtered here instead of by adjacency.
            List<Setting> ownSettings = element.category() == null ? List.of() : hudSettings.stream()
                    .filter(s -> s.getVisibility() instanceof CategorySetting.Visibility v && v.getValue() == element.category())
                    .filter(s -> !(s instanceof eu.client.settings.impl.BooleanSetting) || !s.getTag().equals("Enabled"))
                    .toList();
            elementsFrame.getButtons().add(new HudElementButton(element, ownSettings, elementsFrame, 13));
        }
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

        elementsFrame.render(context, mouseX, mouseY, delta);
    }

    /** Rough screen-space bounds of the elements column (header + whatever is currently revealed),
     *  so overlay drag-hit-testing below doesn't compete with the column for the same click. */
    private boolean insideElementsFrame(double mouseX, double mouseY) {
        return mouseX >= elementsFrame.getX() && mouseX <= elementsFrame.getX() + elementsFrame.getWidth()
                && mouseY >= elementsFrame.getY() && mouseY <= elementsFrame.getY() + elementsFrame.getTotalHeight() + 4;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x(), mouseY = event.y();

        if (insideElementsFrame(mouseX, mouseY)) {
            elementsFrame.mouseClicked(mouseX, mouseY, event.button());
            return true;
        }

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

        elementsFrame.mouseDragged(event.x(), event.y(), event.button(), deltaX, deltaY);
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingElement = null;
        elementsFrame.mouseReleased(event.x(), event.y(), event.button());
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (insideElementsFrame(mouseX, mouseY)) {
            elementsFrame.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
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
