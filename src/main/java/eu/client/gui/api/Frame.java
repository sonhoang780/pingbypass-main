package eu.client.gui.api;

import lombok.Getter;
import lombok.Setter;
import eu.client.EUClient;
import eu.client.gui.ClickGuiScreen;
import eu.client.gui.impl.WhitelistButton;
import eu.client.modules.Module;
import eu.client.gui.impl.ModuleButton;
import eu.client.modules.impl.core.ClickGuiModule;
import eu.client.utils.graphics.Renderer2D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

@Getter @Setter
public class Frame {
    private final Module.Category category;
    // Set instead of category when this Frame is built via the (headerLabel, buttons) constructor
    // -- a column of ExpandableRow buttons not backed by ModuleManager/Module.Category at all
    // (HUDEditorScreen's settings column). getHeaderText() picks whichever is set.
    private final String headerLabel;
    private int x, y, width, height, totalHeight, dragX = 0, dragY = 0, textPadding = 3;
    public boolean open = true, dragging = false;
    private final ArrayList<Button> buttons = new ArrayList<>();

    // SmoothScroll: content (everything below the header) slides on scrollOffset instead of the
    // whole frame (header included) being dragged around by mouseScrolled -- that's what let a
    // category's own header scroll off past the top of the screen with nothing stopping it.
    // scrollVelocity gives it inertia (impulse per scroll tick, damped every frame instead of an
    // instant jump); once scrollOffset is clamped outside [minScroll, maxScroll] a spring force
    // pulls it back, and impulses landing while already out of bounds get damped (rubber-band
    // resistance) so overscroll stretches instead of flying off -- "kéo hết cỡ, lộ nền category,
    // nhả ra thì nảy lại". Bounds are computed off *last* frame's totalHeight (one-frame lag,
    // imperceptible) since this frame's isn't known until after buttons are laid out.
    private float scrollOffset = 0f, scrollVelocity = 0f;
    private static final float SCROLL_FRICTION = 0.85f;
    private static final float SCROLL_SPRING = 0.06f;
    private static final float SCROLL_OVERSCROLL_RESISTANCE = 0.30f;
    private static final float SCROLL_MAX_STRETCH = 18f;

    // Category open/close (right-click the header) had zero animation -- content just vanished/
    // appeared instantly on the `open` flip. Same slide-reveal pattern as CategorySetting/
    // ModuleButton: scissor-clip the content area to fullContentHeight * openAmount instead of
    // gating the whole layout+render block on the raw boolean. Seeded to (1,1) since `open`
    // defaults to true -- else the first read plays a phantom open animation (same class of bug
    // fixed earlier for BooleanSetting.openAnim).
    private final eu.client.utils.animations.Animation openAnim = new eu.client.utils.animations.Animation(1f, 1f, 200, eu.client.utils.animations.Easing.Method.EASE_OUT_QUAD);

    public Frame(Module.Category category, int x, int y, int width, int height) {
        this.category = category;
        this.headerLabel = null;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;

        for(Module module : EUClient.MODULE_MANAGER.getModules(category)) buttons.add(new ModuleButton(module, this, height));
    }

    // Same Frame -- same header/border/scroll/open-close animation, same per-row shader fill --
    // just pre-built ExpandableRow buttons instead of one ModuleButton per Module.Category member.
    // See ExpandableRow's own doc for why this didn't need a ModuleButton subclass instead.
    public Frame(String headerLabel, List<Button> prebuiltButtons, int x, int y, int width, int height) {
        this.category = null;
        this.headerLabel = headerLabel;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;

        this.buttons.addAll(prebuiltButtons);
    }

    private String getHeaderText() {
        return category != null ? category.getName() : headerLabel;
    }

    public void render(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        render(context, mouseX, mouseY, delta, "");
    }

    public void render(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, String searchQuery) {
        if(dragging) {
            setX(mouseX - dragX);
            setY(mouseY - dragY);
        }

        boolean searching = searchQuery != null && !searchQuery.isEmpty();

        this.totalHeight = height;

        // Layout runs at scrollOffset=0 first (real Y positions, unshifted) so totalHeight below
        // is THIS frame's actual content height -- bounds computed off it can never lag behind
        // what just got laid out. The old order (bounds off last frame's stale totalHeight, THEN
        // layout with that scrollOffset) under/over-shot whenever content height changed between
        // frames (closing a panel, search filtering, a panel still mid-animation growing), letting
        // scrollOffset drift outside what the CURRENT content actually supports -- content
        // rendering above the header, or the bottom-most row never reachable. Shifted into place
        // by scrollOffset at the very end instead.
        //
        // Layout always runs (even while closed/animating) so revealHeight below has a real
        // fullContentHeight to scale against -- only the SCISSOR further down decides what's
        // actually visible.
        {
            totalHeight += 1;
            for(Button button : buttons) {
                // Filter by search query
                if(searching && button instanceof ExpandableRow row) {
                    if(!row.getRowName().toLowerCase().contains(searchQuery.toLowerCase())) {
                        button.setVisible(false);
                        continue;
                    }
                    button.setVisible(true);
                    row.setSearchQuery(searchQuery); // Pass search query for highlighting
                } else if(!searching && button instanceof ExpandableRow row) {
                    button.setVisible(true);
                    row.setSearchQuery(""); // Clear search query
                }

                if(!button.isVisible()) continue;

                button.setX(x);
                button.setY(y + totalHeight);
                totalHeight += button.getHeight();

                if(button instanceof ExpandableRow row) {
                    // Rows sit at their FULL, un-scaled Y offsets (never compressed) -- what used
                    // to shrink every row's spacing continuously while still drawing full-size
                    // text crammed multiple rows into an ever-shrinking gap right before fully
                    // closed ("chữ rít vào nhau"). A later fix instead hid whole rows in discrete
                    // steps to dodge the overlap, but that turned the slide into a chunky pop-in
                    // ("như 30fps, không có gia tốc") since the easing curve was still evaluated
                    // continuously but only ever visible at row-count granularity.
                    // Now: rows keep full spacing, and the row's own render() scissor-clips them
                    // to revealHeight (continuous, same eased openAmount curve as before) -- the
                    // panel genuinely slides open/closed pixel-by-pixel with full acceleration,
                    // and a row can never show more of itself than the clip allows, so nothing
                    // overlaps.
                    float openAmount = row.getOpenAmount();
                    java.util.List<Button> settingButtons = row.getButtons();
                    float childOffset = 0f;
                    float fullHeight = 0f;
                    for (Button b : settingButtons) {
                        b.getSetting().getVisibility().update();
                        boolean visible = b.getSetting().getVisibility().isVisible();
                        b.setVisible(visible);
                        if (!visible) continue;

                        // A setting gated behind a CategorySetting (the collapsible "+" sub-pages,
                        // e.g. AutoCrystal's Attack/Place/Misc/...) scales by ITS OWN open
                        // animation too, same nested reveal-while-growing effect as the module
                        // panel itself.
                        float categoryScale = 1f;
                        if (b.getSetting().getVisibility() instanceof eu.client.settings.impl.CategorySetting.Visibility categoryVisibility) {
                            categoryScale = categoryVisibility.getValue().getOpenAmount();
                        } else if (b.getSetting().getVisibility() instanceof eu.client.settings.impl.BooleanSetting.Visibility booleanVisibility) {
                            categoryScale = booleanVisibility.getOpenAmount();
                        }
                        float rowHeight = b.getHeight() * categoryScale;

                        b.setX(x);
                        b.setY(y + totalHeight + Math.round(childOffset));
                        childOffset += rowHeight;
                        fullHeight += rowHeight;
                    }

                    // 2026-08-15: totalHeight (and therefore maxScroll a few lines down, AND every
                    // button laid out below this one) used to advance by the ANIMATED revealHeight
                    // (fullHeight * openAmount) instead of the module's FINAL target height -- so
                    // while a module's settings were still sliding open (180ms), the scroll bounds
                    // and every row below it were BOTH still growing toward their final position on
                    // the same frame-by-frame curve. Reported live: opening a long module (e.g.
                    // KillAura) pushes a bottom-of-list module (Surround) down, but you can't scroll
                    // far enough to reach it until the open animation finishes catching up -- "kéo
                    // lên không kịp", a moving target you also can't out-scroll. Layout now commits
                    // to the module's FINAL target height immediately (isOpen(), the settled boolean,
                    // not the mid-flight openAmount) -- scroll bounds and rows below settle in one
                    // frame whether opening OR closing. Only the visual reveal (ModuleButton's own
                    // scissor clip, still keyed off openAmount) animates.
                    int revealHeight = Math.round(fullHeight * openAmount);
                    row.setRevealHeight(revealHeight);
                    // Opening: commit to the full target height immediately (the fix above).
                    // Closing: keep following the animated (shrinking) revealHeight -- committing to
                    // 0 immediately here would snap every row below upward while this module's
                    // content is still visibly rendered mid-close (revealHeight > 0), overlapping it.
                    // Closing was never the reported problem (nothing becomes harder to reach by
                    // collapsing), so it keeps the original smooth-shrink behavior.
                    totalHeight += row.isOpen() ? Math.round(fullHeight) : revealHeight;
                }
            }
        }

        // Bounds off THIS frame's now-accurate totalHeight, step the spring physics, then shift
        // every already-laid-out button (and its settings rows) down by the result.
        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        float maxScroll = 0f;
        float minScroll = -Math.max(0, totalHeight - (screenHeight - y - 4));

        scrollOffset += scrollVelocity;
        scrollVelocity *= SCROLL_FRICTION;
        if (scrollOffset > maxScroll) scrollVelocity += (maxScroll - scrollOffset) * SCROLL_SPRING;
        else if (scrollOffset < minScroll) scrollVelocity += (minScroll - scrollOffset) * SCROLL_SPRING;
        scrollOffset = Math.clamp(scrollOffset, minScroll - SCROLL_MAX_STRETCH, maxScroll + SCROLL_MAX_STRETCH);
        if (Math.abs(scrollVelocity) < 0.01f && scrollOffset >= minScroll && scrollOffset <= maxScroll) scrollVelocity = 0f;

        int shift = Math.round(scrollOffset);
        if (shift != 0) {
            for (Button button : buttons) {
                if (!button.isVisible()) continue;
                button.setY(button.getY() + shift);
                if (button instanceof ExpandableRow row) {
                    for (Button b : row.getButtons()) {
                        if (b.isVisible()) b.setY(b.getY() + shift);
                    }
                }
            }
        }

        // Category header — dark background with accent underline
        Renderer2D.renderQuad(context, x, y, x + width, y + height, new Color(20, 20, 25, 200));
        Color accentColor = ClickGuiScreen.getButtonColor(y, 200);
        Renderer2D.renderQuad(context, x, y + height - 1, x + width, y + height, accentColor);
        EUClient.FONT_MANAGER.drawTextWithShadow(context, getHeaderText(), x + textPadding, y + 2, Color.WHITE);

        float frameOpenAmount = openAnim.get(open ? 1f : 0f);
        int fullContentHeight = totalHeight - height - 1;
        int revealHeight = Math.round(fullContentHeight * frameOpenAmount);

        // Extra breathing room below the last row -- without it the frame's own bottom border sat
        // flush against the last module's text/settings, looking cramped.
        int bottomMargin = 4;

        if(revealHeight > 0) {
            int clipTop = y + height;
            context.enableScissor(x, clipTop, x + width, clipTop + revealHeight + bottomMargin);
            Renderer2D.renderQuad(context, x, y + height, x + width, y + Math.round(scrollOffset) + totalHeight + 1 + bottomMargin, new Color(15, 15, 20, 180));
            for(Button button : buttons) {
                if(!button.isVisible()) continue;
                button.render(context, mouseX, mouseY, delta);
            }
            context.disableScissor();
        }

        // Border outline around the whole frame (header + revealed content), colored to match
        // ClickGui's own theme Color setting.
        Color borderColor = ClickGuiScreen.getButtonColor(y, 120);
        int borderBottom = open ? y + height + revealHeight + bottomMargin : y + height;
        Renderer2D.renderOutline(context, x, y, x + width, borderBottom, borderColor);
    }

    public void mouseClicked(double mouseX, double mouseY, int button) {
        if(isHovering(mouseX, mouseY)) {
            if(button == 0) {
                dragging = true;
                dragX = (int) (mouseX - getX());
                dragY = (int) (mouseY - getY());
            } else if(button == 1) {
                open = !open;
            }
        }

        if(open) {
            for(Button b : buttons) {
                if(!b.isVisible()) continue;
                b.mouseClicked(mouseX, mouseY, button);
            }
        }
    }

    public void mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            dragging = false;
        }

        for(Button b : buttons) {
            if(!b.isVisible()) continue;
            b.mouseReleased(mouseX, mouseY, button);
        }
    }

    public void mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (x <= mouseX && x + width > mouseX) {
            boolean whitelistHandling = false;
            for (Button b : buttons) {
                if (b instanceof ExpandableRow row && row.isOpen()) {
                    List<Button> wbButtons = row.getButtons().stream().filter(button -> button instanceof WhitelistButton).toList();
                    for (Button whitelistButton : wbButtons) {
                        if (whitelistButton instanceof WhitelistButton wb) {
                            if (wb.isHandlingScroll(mouseX, mouseY)) {
                                wb.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
                                whitelistHandling = true;
                                break;
                            }
                        }
                    }
                }
            }
            if (!whitelistHandling && verticalAmount != 0) {
                float impulse = EUClient.MODULE_MANAGER.getModule(ClickGuiModule.class).scrollSpeed.getValue().floatValue();
                // Rubber-band resistance: an impulse landing while already past the clamp range
                // (mid-overscroll) only stretches it further at a fraction of normal strength,
                // instead of flying off unbounded.
                boolean outOfBounds = scrollOffset > 0f || scrollOffset < -Math.max(0, totalHeight - (Minecraft.getInstance().getWindow().getGuiScaledHeight() - y - 4));
                float resistance = outOfBounds ? SCROLL_OVERSCROLL_RESISTANCE : 1f;
                scrollVelocity += (verticalAmount < 0 ? -impulse : impulse) * resistance;
            }
        }
    }

    public void mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        for (Button b : buttons) {

            b.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        }
    }

    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        if (open) {
            for (Button button : buttons) {
                if(!button.isVisible()) continue;
                button.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }

    public void charTyped(char chr, int modifiers) {
        if (open) {
            for (Button button : buttons) {
                if(!button.isVisible()) continue;
                button.charTyped(chr, modifiers);
            }
        }
    }

    public boolean isHovering(double mouseX, double mouseY) {
        return x <= mouseX && y <= mouseY && x + width > mouseX && y + height > mouseY;
    }
}
