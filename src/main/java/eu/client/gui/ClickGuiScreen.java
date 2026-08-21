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
import eu.client.utils.animations.Animation;
import eu.client.utils.animations.Easing;
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

    // Whole-GUI open/close slide. EUClient.CLICK_GUI is a single persistent instance (reused via
    // mc.gui.setScreen(EUClient.CLICK_GUI) every open, never reconstructed), so the animation can't just
    // be "start fresh in the constructor" -- it's driven by this open/closing flag instead, and
    // Animation.get() smoothly re-targets on its own if the user re-opens mid-close.
    // 400f/220ms with EASE_OUT_CUBIC on BOTH directions put all the visible motion in the first
    // ~46ms (easeOutCubic hits 0.5 at t~=0.21) and left the rest happening off-screen -- looked
    // like no deceleration at all, especially on close (starts at max speed since ease-out
    // decelerates approaching 1, not useful when animating toward 0). Shorter distance keeps the
    // slow phase on-screen; longer duration makes it perceptible; per-direction easing (set in
    // requestClose/cancelClose below) makes closing actually start slow and speed up off-screen,
    // where OPENING keeps decelerating INTO place. Tune these three by eye, not further guessing.
    // 2026-08-21 FIX (reported: "cột module kéo lên còn nửa chừng đã đột ngột tắt"). The wave-
    // completion guard (waveTotalMs, see extractRenderState) was already mathematically exact, but
    // exact meant zero margin -- a slow frame landing right at the boundary could tick waveElapsed
    // past waveTotalMs mid-render before the last category's own eased curve visually settled to 0.
    // Simplest fix: give the whole wave more headroom (longer slide, more per-column delay) instead
    // of chasing frame-timing precision.
    private static final int SLIDE_DURATION_MS = 420;
    private static final float SLIDE_DISTANCE = 160f;
    private final Animation slideAnim = new Animation(SLIDE_DURATION_MS, Easing.Method.EASE_OUT_CUBIC);
    private boolean closing = false;

    // Requested: each category Frame slides down (and back up on close) staggered, wave-style,
    // instead of the whole bar moving as one rigid block. Reuses slideAnim's own startTime/easing
    // (retargeted by cancelClose()/requestClose() on open/close, same trigger as the global slide)
    // so a reopen mid-close restarts the wave cleanly, same as the global animation already does --
    // just computed per-frame with an index*stagger delay instead of one shared progress value.
    // Only the category Frames wave; descriptionFrame/searchFrame/pingBypassFrame keep the plain
    // global slide (see extractRenderState) since they aren't a row of repeated items.
    private static final int CATEGORY_STAGGER_MS = 45;

    // Deferred close: instead of removing the screen immediately, play the slide-up first and only
    // actually call mc.gui.setScreen(null) once it's finished (checked each frame in extractRenderState).
    public void requestClose() {
        closing = true;
        slideAnim.setEasing(Easing.Method.EASE_IN_CUBIC);
    }

    public void cancelClose() {
        closing = false;
        slideAnim.setEasing(Easing.Method.EASE_OUT_CUBIC);
    }

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

        float progress = slideAnim.get(closing ? 0f : 1f);

        // The LAST frame's wave doesn't finish until its own stagger delay has also elapsed on top
        // of the normal slide duration -- waiting on the plain global `progress` alone would remove
        // the screen (and cut the tail of the wave off) before the last category finished sliding.
        long waveElapsed = System.currentTimeMillis() - slideAnim.getStartTime();
        // +50ms cushion: a slow frame can tick waveElapsed past the exact boundary before the last
        // category's own eased curve visually reads as fully settled -- margin, not precision, is
        // what actually prevents the perceptible cutoff.
        long waveTotalMs = SLIDE_DURATION_MS + (long) Math.max(0, frames.size() - 1) * CATEGORY_STAGGER_MS + 50L;
        if (closing && progress <= 0.001f && waveElapsed >= waveTotalMs) {
            // Slide-up finished -- actually remove the screen now. This triggers onClose() on us,
            // which resets the search box and toggles ClickGuiModule off (a no-op if that's already
            // what closed us, e.g. the keybind path -- Module.setToggled() guards same-state calls).
            Minecraft.getInstance().gui.setScreen(null);
            return;
        }

        descriptionFrame.setDescription("");
        String query = searchFrame.getQuery();

        for (int i = 0; i < frames.size(); i++) {
            float frameProgress = categoryFrameProgress(i);
            context.pose().pushMatrix();
            context.pose().translate(0, (1f - frameProgress) * -SLIDE_DISTANCE);
            frames.get(i).render(context, mouseX, mouseY, delta, query);
            context.pose().popMatrix();
        }

        context.pose().pushMatrix();
        context.pose().translate(0, (1f - progress) * -SLIDE_DISTANCE);

        pingBypassFrame.render(context, mouseX, mouseY, delta, query);

        descriptionFrame.render(context, mouseX, mouseY, delta);
        searchFrame.render(context, mouseX, mouseY, delta);

        context.pose().popMatrix();
    }

    /** Same easing/duration/direction as slideAnim (`prev -> current`), delayed by
     *  index*CATEGORY_STAGGER_MS off its own startTime -- opening ramps 0->1 (visible-ness rising),
     *  closing ramps 1->0 (visible-ness falling), matching the global `progress` this mirrors. */
    private float categoryFrameProgress(int index) {
        long elapsed = System.currentTimeMillis() - slideAnim.getStartTime() - (long) index * CATEGORY_STAGGER_MS;
        float t = net.minecraft.util.Mth.clamp(elapsed / (float) SLIDE_DURATION_MS, 0f, 1f);
        float eased = Easing.ease(t, slideAnim.getEasing());
        return closing ? 1f - eased : eased;
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

        // Claim ESC ourselves instead of letting Screen's default handling close us instantly --
        // request the slide-up and let extractRenderState() finish the actual close once it's done.
        // Except while a BindButton is capturing a keypress: Esc there means "cancel this bind
        // capture", not "close the whole GUI" -- claiming it here unconditionally used to close
        // the GUI before BindButton.keyPressed ever got a chance to see the Esc at all. Fall
        // through to the normal frame dispatch below instead so it reaches the button.
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && !eu.client.gui.impl.BindButton.isAnyListening()) {
            requestClose();
            return true;
        }

        // While the slide-up close animation is playing, mc.gui.screen() is still this screen (not
        // null yet), so KeyboardMixin's `minecraft.gui.screen() == null` gate silently drops the bind
        // keypress meant to reopen -- the user has to press it a second time after the animation
        // finishes. Only handle the cancel-close case here (reopen mid-animation); don't also
        // treat the bind as an in-screen close toggle -- the opening press itself never reaches
        // this method (screen was still null when it fired, so it went through KeyboardMixin
        // instead), so there's no way to tell a genuine re-press here from the OS's key-repeat
        // for that same opening press, and treating repeats as new presses closes the GUI the
        // instant it opens.
        if (keyCode == EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.core.ClickGuiModule.class).getBind()) {
            if (closing) cancelClose();
            return true;
        }

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

        // Esc reaches here only via the bind-cancel fallthrough above (isAnyListening() was true).
        // vanilla Screen.keyPressed() unconditionally calls onClose() on Esc regardless of who
        // else handled it -- calling super here closed the GUI right after BindButton had just
        // cancelled the bind capture, undoing the whole point of the fallthrough. Esc is fully
        // handled by us either way (requestClose() above, or the bind-cancel branch in
        // BindButton.keyPressed reached via the frame dispatch just above), so never hand it to
        // vanilla's own close handling.
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) return true;
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

    // removed() (not onClose()) is the actual lifecycle hook Minecraft.setScreen() calls on the
    // outgoing screen no matter how it's being replaced -- onClose() is the opposite: a method
    // Screen subclasses call THEMSELVES to request a close, whose default impl just calls
    // setScreen(null). We never call onClose() (ESC and the bind key both drive requestClose()'s
    // deferred setScreen(null) directly, to play the slide-up first), so putting the toggle-off
    // here instead of onClose() is what actually runs when the screen goes away.
    @Override
    public void removed() {
        searchFrame.setQuery("");
        searchFrame.setCursorIndex(0);
        searchFrame.setFocused(false);
        searchFrame.setVisible(false);
        super.removed();
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
