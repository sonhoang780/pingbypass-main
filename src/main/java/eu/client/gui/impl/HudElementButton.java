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

// One row of HUDEditorScreen's settings column -- a HUD element (Watermark, Coordinates, ...)
// instead of a Module, but otherwise the exact same shape as ModuleButton (left-click toggles
// the element on/off, right-click expands its own settings, same shader row-fill): see
// ExpandableRow's own doc for why this is a sibling of ModuleButton rather than a Module-backed
// subclass of it.
@Getter @Setter
public class HudElementButton extends Button implements ExpandableRow {
    private final HudElementRegistry.Element element;
    // Fallback open/animation state, used only when this element has no CategorySetting (its
    // settings list is empty -- nothing to reveal either way). Elements WITH a category delegate
    // isOpen()/getOpenAmount() straight to element.category() instead of tracking their own copy:
    // every child setting's own visibility is `new CategorySetting.Visibility(thatCategory)`,
    // which gates on the CATEGORY's `open`/openAnim, not on any state this button might keep
    // locally. A separate local `open` flag here would flip visually but never actually reveal
    // any child row -- exactly the reported "right-click does nothing" bug: the click WAS
    // registering, it was just toggling a flag nothing downstream ever read.
    // Lombok's blanket @Getter/@Setter is excluded here -- isOpen() is written by hand above
    // (delegates to element.category() when present) and nothing outside this class needs to
    // set `open` directly (only the fallback mouseClicked branch below, in-class).
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

    /** @param settings this element's own settings (already filtered to its CategorySetting), or empty if it has none. */
    public HudElementButton(HudElementRegistry.Element element, List<Setting> settings, Frame parent, int height) {
        super(parent, height, element.name());
        this.element = element;
        float start = element.enabled().getValue() ? 1f : 0f;
        this.fillAnim = new Animation(start, start, 180, Easing.Method.EASE_OUT_QUAD);

        // Same dispatch as ModuleButton's constructor -- kept in sync deliberately rather than
        // shared, since ModuleButton's version also has to skip the CategorySetting's own folded-
        // in "Enabled" row (not applicable here: `settings` is already one category's children,
        // never the CategorySetting itself).
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

    @Override
    public void render(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        if (this.isHovering(mouseX, mouseY) && EUClient.CLICK_GUI.getDescriptionFrame().getDescription().isEmpty())
            EUClient.CLICK_GUI.getDescriptionFrame().setDescription(this.getDescription());

        Color bgColor = isHovering(mouseX, mouseY) ? new Color(255, 255, 255, 15) : new Color(0, 0, 0, 0);
        if (bgColor.getAlpha() > 0) {
            Renderer2D.renderQuad(context, getX() + getPadding(), getY(), getX() + getWidth() - getPadding(), getY() + getHeight() - 1, bgColor);
        }

        // Row fill -- identical shader/flat-fill behavior to ModuleButton's own (ShadersModule's
        // NeekeriFill patterns via ClickGuiModule.fillMode, see that class's own doc).
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
        if (isHovering(mouseX, mouseY)) {
            if (button == 0) {
                element.enabled().setValue(!element.enabled().getValue());
                playClickSound();
            } else if (button == 1) {
                if (element.category() != null) {
                    // Same toggle CategoryButton itself uses (setting.setOpen(!setting.isOpen()))
                    // -- this is the ONLY thing that actually makes child settings visible, since
                    // their own Visibility checks element.category().getOpenAmount(), not this
                    // button's local state. See isOpen()/getOpenAmount()'s own doc above.
                    element.category().setOpen(!element.category().isOpen());
                } else {
                    open = !open;
                    openAnim.setEasing(open ? Easing.Method.EASE_OUT_QUAD : Easing.Method.EASE_IN_QUAD);
                }
                playClickSound();
            }
        }

        // 2026-08-15 FIX (reported: settings visible after right-click, but clicking them does
        // nothing). This used to read the raw `open` field, which the category branch above
        // never touches (it toggles element.category().open instead) -- so this stayed false
        // forever for every element with a category, and no click ever reached the child
        // buttons even though they were correctly rendering. isOpen() is the one source of truth
        // (delegates to element.category() when present, see its own doc) -- use it here too.
        if (isOpen()) {
            for (Button b : buttons) {
                if (!b.isVisible()) continue;
                b.mouseClicked(mouseX, mouseY, button);
            }
        }
    }

    @Override
    public void mouseReleased(double mouseX, double mouseY, int button) {
        for (Button b : buttons) b.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        for (Button b : buttons) b.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
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
