package eu.client.gui;

import eu.client.EUClient;
import eu.client.gui.api.Button;
import eu.client.gui.api.Frame;
import eu.client.gui.api.ExpandableRow;
import eu.client.gui.impl.HudElementButton;
import eu.client.gui.impl.CategoryButton;
import eu.client.gui.impl.BooleanButton;
import eu.client.gui.impl.NumberButton;
import eu.client.gui.impl.BindButton;
import eu.client.gui.impl.ModeButton;
import eu.client.gui.impl.WhitelistButton;
import eu.client.gui.impl.StringButton;
import eu.client.gui.impl.ColorButton;
import eu.client.managers.HudElementRegistry;
import eu.client.modules.impl.core.HUDEditorModule;
import eu.client.modules.impl.core.HUDModule;
import eu.client.settings.Setting;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.CategorySetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.settings.impl.BindSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.WhitelistSetting;
import eu.client.settings.impl.StringSetting;
import eu.client.settings.impl.ColorSetting;
import eu.client.settings.impl.PositionSetting;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.graphics.Renderer2D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.network.chat.Component;

import java.awt.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

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

        int guiWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int guiHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        int frameX = Math.round(guiWidth * 0.23f);
        int frameY = Math.round(guiHeight * 0.14f);

        elementsFrame = new Frame("HUD", List.of(), frameX, frameY, 110, 13);
        
        HUDModule hudModule = EUClient.MODULE_MANAGER.getModule(HUDModule.class);
        List<Setting> hudSettings = hudModule.getSettings();
        Set<CategorySetting> boundCategories = new HashSet<>();

        // 1. Thêm các HUD Elements có thể kéo thả
        for (HudElementRegistry.Element element : HudElementRegistry.getElements().values()) {
            if (element.category() != null) {
                boundCategories.add(element.category());
            }
            List<Setting> ownSettings = element.category() == null ? List.of() : hudSettings.stream()
                    .filter(s -> s.getVisibility() instanceof CategorySetting.Visibility v && v.getValue() == element.category())
                    .filter(s -> !(s instanceof eu.client.settings.impl.BooleanSetting) || !s.getTag().equals("Enabled"))
                    .toList();
            elementsFrame.getButtons().add(new HudElementButton(element, ownSettings, elementsFrame, 13));
        }

        // 2. Thêm các Category còn lại (Potions, Color,...) và tự động gom nhóm setting con của chúng
        for (Setting setting : hudSettings) {
            if (setting instanceof CategorySetting cat && !boundCategories.contains(cat)) {
                // Lọc ra các setting thuộc Category này
                List<Setting> catSettings = hudSettings.stream()
                        .filter(s -> s.getVisibility() instanceof CategorySetting.Visibility v && v.getValue() == cat)
                        .toList();
                
                elementsFrame.getButtons().add(new CustomCategoryButton(cat, catSettings, elementsFrame, 13));
            }
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
            
            // Tạo Box mặc định cho các mục đang TẮT để người chơi có thể định vị trước khi bật
            if (bounds == null) {
                float ox = element.offset().getX();
                float oy = element.offset().getY();
                bounds = new float[]{ox, oy, ox + 60, oy + 15};
            }

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
            
            if (bounds == null) {
                float ox = element.offset().getX();
                float oy = element.offset().getY();
                bounds = new float[]{ox, oy, ox + 60, oy + 15};
            }
            
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
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key(), scanCode = event.scancode(), modifiers = event.modifiers();
        elementsFrame.keyPressed(keyCode, scanCode, modifiers);
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && !eu.client.gui.impl.BindButton.isAnyListening()) {
            onClose();
            return true;
        }
        // Esc reach here only if isAnyListening() was true. Return true so vanilla doesn't process it.
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) return true;
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        char chr = (char) event.codepoint();
        int modifiers = 0;
        elementsFrame.charTyped(chr, modifiers);
        return super.charTyped(event);
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
    private static class CustomCategoryButton extends CategoryButton implements eu.client.gui.api.ExpandableRow {
        private final List<Button> childButtons = new java.util.ArrayList<>();
        private final CategorySetting category;
        private int revealHeight = 0;

        public CustomCategoryButton(CategorySetting category, List<Setting> settings, Frame parent, int height) {
            super(category, null, parent, height);
            this.category = category;
            for (Setting setting : settings) {
                if (setting instanceof BooleanSetting s) childButtons.add(new BooleanButton(s, parent, height));
                else if (setting instanceof NumberSetting s) childButtons.add(new NumberButton(s, parent, height));
                else if (setting instanceof CategorySetting s) childButtons.add(new CategoryButton(s, null, parent, height));
                else if (setting instanceof BindSetting s) childButtons.add(new BindButton(s, parent, height));
                else if (setting instanceof ModeSetting s) childButtons.add(new ModeButton(s, parent, height));
                else if (setting instanceof WhitelistSetting s) childButtons.add(new WhitelistButton(s, parent, height));
                else if (setting instanceof StringSetting s) childButtons.add(new StringButton(s, parent, height));
                else if (setting instanceof ColorSetting s) childButtons.add(new ColorButton(s, parent, height));
            }
        }

        @Override
        public String getRowName() { return category.getTag(); }

        @Override
        public boolean isOpen() { return category.isOpen(); }

        @Override
        public float getOpenAmount() { return category.getOpenAmount(); }

        @Override
        public List<Button> getButtons() { return childButtons; }

        @Override
        public void setRevealHeight(int height) { this.revealHeight = height; }

        @Override
        public void setSearchQuery(String query) { }

        @Override
        public void render(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
            super.render(context, mouseX, mouseY, delta);

            if (revealHeight > 0) {
                int clipTop = getY() + getHeight();
                context.enableScissor(getX(), clipTop, getX() + getWidth(), clipTop + revealHeight);
                for (Button button : childButtons) {
                    if (!button.isVisible()) continue;
                    button.render(context, mouseX, mouseY, delta);
                }
                context.disableScissor();
            }
        }

        @Override
        public void mouseClicked(double mouseX, double mouseY, int button) {
            super.mouseClicked(mouseX, mouseY, button);
            if (category.isOpen()) {
                for (Button b : childButtons) {
                    if (!b.isVisible()) continue;
                    b.mouseClicked(mouseX, mouseY, button);
                }
            }
        }

        @Override
        public void mouseReleased(double mouseX, double mouseY, int button) {
            super.mouseReleased(mouseX, mouseY, button);
            if (category.isOpen()) {
                for (Button b : childButtons) {
                    if (!b.isVisible()) continue;
                    b.mouseReleased(mouseX, mouseY, button);
                }
            }
        }

        @Override
        public void mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
            if (category.isOpen()) {
                for (Button b : childButtons) {
                    if (!b.isVisible()) continue;
                    b.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
                }
            }
        }

        @Override
        public void keyPressed(int keyCode, int scanCode, int modifiers) {
            super.keyPressed(keyCode, scanCode, modifiers);
            if (category.isOpen()) {
                for (Button b : childButtons) {
                    if (!b.isVisible()) continue;
                    b.keyPressed(keyCode, scanCode, modifiers);
                }
            }
        }

        @Override
        public void charTyped(char chr, int modifiers) {
            super.charTyped(chr, modifiers);
            if (category.isOpen()) {
                for (Button b : childButtons) {
                    if (!b.isVisible()) continue;
                    b.charTyped(chr, modifiers);
                }
            }
        }
    }
}