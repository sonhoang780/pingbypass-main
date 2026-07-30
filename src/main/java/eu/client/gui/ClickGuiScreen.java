package eu.client.gui;

import lombok.Getter;
import lombok.Setter;
import eu.client.EUClient;
import eu.client.gui.api.DescriptionFrame;
import eu.client.gui.api.PingBypassFrame;
import eu.client.gui.api.SearchFrame;
import eu.client.modules.Module;
import eu.client.modules.impl.core.ClickGuiModule;
import eu.client.gui.api.Button;
import eu.client.gui.api.Frame;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.graphics.Renderer2D;
import eu.client.utils.system.Timer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.Util;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.awt.*;
import java.util.ArrayList;

@Getter @Setter
public class ClickGuiScreen extends Screen {
    private final ArrayList<Frame> frames = new ArrayList<>();
    private final ArrayList<Button> buttons = new ArrayList<>();
    private final DescriptionFrame descriptionFrame;
    private final SearchFrame searchFrame;
    private final PingBypassFrame pingBypassFrame;

    private final Timer lineTimer = new Timer();
    private boolean showLine = false;
    private Color colorClipboard = null;

    public ClickGuiScreen() {
        super(Component.literal(EUClient.MOD_ID + "-click-gui"));

        int x = 6;
        for(Module.Category category : Module.Category.values()) {
            frames.add(new Frame(category, x, 3, 100, 13));
            x += 104;
        }

        this.pingBypassFrame = new PingBypassFrame(x, 3, 100, 13);
        this.descriptionFrame = new DescriptionFrame(x + 104, 3, 200, 13);
        this.searchFrame = new SearchFrame();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        if (lineTimer.hasTimeElapsed(400L)){
            showLine = !showLine;
            lineTimer.reset();
        }

        descriptionFrame.setDescription("");
        String query = searchFrame.getQuery();
        for(Frame frame : frames) frame.render(context, mouseX, mouseY, delta, query);
        pingBypassFrame.render(context, mouseX, mouseY, delta, query);

        descriptionFrame.render(context, mouseX, mouseY, delta);
        searchFrame.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        double mouseX = event.x(), mouseY = event.y();
        int button = event.button();
        for(Frame frame : frames) frame.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        pingBypassFrame.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x(), mouseY = event.y();
        int button = event.button();
        if (searchFrame.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        for (Frame frame : frames) {
            frame.mouseClicked(mouseX, mouseY, button);
        }
        pingBypassFrame.mouseClicked(mouseX, mouseY, button);

        descriptionFrame.mouseClicked(mouseX, mouseY, button);

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        double mouseX = event.x(), mouseY = event.y();
        int button = event.button();
        for (Frame frame : frames) {
            frame.mouseReleased(mouseX, mouseY, button);
        }
        pingBypassFrame.mouseReleased(mouseX, mouseY, button);

        descriptionFrame.mouseReleased(mouseX, mouseY, button);

        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        for (Frame frame : frames) {
            frame.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        pingBypassFrame.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);

        return this.getChildAt(mouseX, mouseY).filter(element -> element.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)).isPresent();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key(), scanCode = event.scancode(), modifiers = event.modifiers();
        // Check for Ctrl+F to toggle search
        boolean ctrl = InputConstants.isKeyDown(this.minecraft.getWindow(), Util.getPlatform() == Util.OS.OSX ? GLFW.GLFW_KEY_LEFT_SUPER : GLFW.GLFW_KEY_LEFT_CONTROL);

        if (ctrl && keyCode == GLFW.GLFW_KEY_F) {
            searchFrame.toggle();
            return true;
        }

        if (searchFrame.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        for (Frame frame : frames) {
            frame.keyPressed(keyCode, scanCode, modifiers);
        }
        pingBypassFrame.keyPressed(keyCode, scanCode, modifiers);
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        char chr = (char) event.codepoint();
        int modifiers = 0;
        if (searchFrame.charTyped(chr, modifiers)) {
            return true;
        }
        for (Frame frame : frames) {
            frame.charTyped(chr, modifiers);
        }
        pingBypassFrame.charTyped(chr, modifiers);
        return super.charTyped(event);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        if(EUClient.MODULE_MANAGER.getModule(ClickGuiModule.class).blur.getValue()) context.blurBeforeThisStratum();
        Renderer2D.renderQuad(context, 0, 0, this.width, this.height, new Color(10, 8, 18, 120));
    }

    @Override
    public void onClose() {
        searchFrame.setQuery("");
        searchFrame.setCursorIndex(0);
        searchFrame.setFocused(false);
        searchFrame.setVisible(false);
        super.onClose();
        EUClient.MODULE_MANAGER.getModule(ClickGuiModule.class).setToggled(false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public static Color getButtonColor(int index, int alpha) {
        Color color = EUClient.MODULE_MANAGER.getModule(ClickGuiModule.class).isRainbow() ? ColorUtils.getOffsetRainbow(index*10L) : EUClient.MODULE_MANAGER.getModule(ClickGuiModule.class).color.getColor();
        return ColorUtils.getColor(color, alpha);
    }
}
