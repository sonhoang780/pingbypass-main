package eu.client.gui.impl;

import lombok.Getter;
import lombok.Setter;
import eu.client.EUClient;
import eu.client.gui.ClickGuiScreen;
import eu.client.modules.Module;
import eu.client.gui.api.Button;
import eu.client.gui.api.ExpandableRow;
import eu.client.gui.api.Frame;
import eu.client.settings.Setting;
import eu.client.settings.impl.*;
import eu.client.utils.animations.Animation;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.animations.Easing;
import eu.client.utils.graphics.Renderer2D;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.ChatFormatting;

import java.awt.*;
import java.util.ArrayList;

@Getter @Setter
public class ModuleButton extends Button implements ExpandableRow {
    private final Module module;
    private boolean open = false;
    private final ArrayList<Button> buttons = new ArrayList<>();
    private String searchQuery = "";

    // Drives the settings-panel reveal: Frame scales each setting row's contribution to the
    // panel's total height by this (0..1), so the panel visibly unfolds/slides open instead of
    // instantly snapping to full height.
    private final Animation openAnim = new Animation(220, Easing.Method.EASE_OUT_CUBIC);

    // Pixel height of the settings panel Frame is willing to show this frame (continuous, eased --
    // see Frame.render()). Sub-buttons sit at their full, un-scaled Y offsets; this scissor-clips
    // rendering to that height so the panel visibly slides open/closed without any row ever
    // overlapping the one above it.
    private int revealHeight = 0;

    // Row fill (replaces the old static instant-appear left bar) -- slides in/out on toggle
    // instead of just popping into existence. Seeded to match the module's actual starting
    // isToggled() state (not always 0) -- same phantom-play bug class as BooleanSetting.openAnim/
    // CategorySetting.openAnim if left at the field-initializer default.
    private final Animation fillAnim;

    @Override
    public float getOpenAmount() {
        return openAnim.get(open ? 1f : 0f);
    }

    @Override
    public String getRowName() {
        return module.getName();
    }

    public ModuleButton(Module module, Frame parent, int height) {
        super(parent, height, module.getDescription());
        this.module = module;
        float start = module.isToggled() ? 1f : 0f;
        this.fillAnim = new Animation(start, start, 200, Easing.Method.EASE_OUT_CUBIC);

        java.util.List<Setting> settings = module.getSettings();
        for (int i = 0; i < settings.size(); i++) {
            Setting setting = settings.get(i);
            if(setting instanceof BooleanSetting s) {
                // A CategorySetting's own "Enabled" toggle (convention: the first BooleanSetting
                // gated behind it, tagged "Enabled" -- e.g. HUDModule's watermarkCategory/watermark
                // pair) is folded into the category header row itself instead of getting its own
                // separate row -- see the skip condition below and CategoryButton's left/right
                // click split.
                if (s.getTag().equals("Enabled") && s.getVisibility() instanceof CategorySetting.Visibility v
                        && i > 0 && settings.get(i - 1) == v.getValue()) {
                    continue;
                }
                buttons.add(new BooleanButton(s, parent, height));
            } else if(setting instanceof NumberSetting s) {
                buttons.add(new NumberButton(s, parent, height));
            } else if(setting instanceof CategorySetting s) {
                BooleanSetting enableSetting = null;
                if (i + 1 < settings.size() && settings.get(i + 1) instanceof BooleanSetting next
                        && next.getTag().equals("Enabled") && next.getVisibility() instanceof CategorySetting.Visibility v
                        && v.getValue() == s) {
                    enableSetting = next;
                }
                buttons.add(new CategoryButton(s, enableSetting, parent, height));
            } else if(setting instanceof BindSetting s) {
                buttons.add(new BindButton(s, parent, height));
            } else if(setting instanceof ModeSetting s) {
                buttons.add(new ModeButton(s, parent, height));
            } else if(setting instanceof WhitelistSetting s) {
                buttons.add(new WhitelistButton(s, parent, height));
            } else if(setting instanceof StringSetting s) {
                buttons.add(new StringButton(s, parent, height));
            } else if(setting instanceof ColorSetting s) {
                buttons.add(new ColorButton(s, parent, height));
            }
        }
    }

    @Override
    public void render(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        if(this.isHovering(mouseX, mouseY) && EUClient.CLICK_GUI.getDescriptionFrame().getDescription().isEmpty()) EUClient.CLICK_GUI.getDescriptionFrame().setDescription(this.getDescription());

        // Background — subtle hover highlight
        Color bgColor = isHovering(mouseX, mouseY) ? new Color(255, 255, 255, 15) : new Color(0, 0, 0, 0);
        if (bgColor.getAlpha() > 0) {
            Renderer2D.renderQuad(context, getX() + getPadding(), getY(), getX() + getWidth() - getPadding(), getY() + getHeight() - 1, bgColor);
        }

        // Row fill -- slides in from the right, tucking to the left, instead of the old static
        // 2px bar popping in/out instantly. FillMode picks the look: Default = flat theme-color
        // highlight (same accent color the bar used to be, just now animated + spanning the row);
        // anything else = one of ShadersModule's own 9 animated patterns (see NeekeriFill),
        // Speed/Opacity-controlled the same way ShadersModule's are. Low alpha throughout so the
        // module name/settings text stays readable on top.
        float fillProgress = fillAnim.get(module.isToggled() ? 1f : 0f);
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

        // Separator line between rows -- was a full 4-sided outline PER row, so two adjacent
        // toggled rows doubled up their shared edge into a visibly thicker line. Bottom edge only;
        // the row above's bottom line IS the row below's top line.
        Color separator = ClickGuiScreen.getButtonColor(getY(), 60);
        Renderer2D.renderQuad(context, getX() + getPadding(), getY() + getHeight() - 1, getX() + getWidth() - getPadding(), getY() + getHeight(), separator);

        // Render module name with search highlighting
        String moduleName = module.getName();
        int textX = getX() + getTextPadding() + (module.isToggled() ? 1 : 0);
        int textY = getY() + 2;
        
        if (searchQuery != null && !searchQuery.isEmpty()) {
            // Find the match position (case-insensitive)
            String lowerName = moduleName.toLowerCase();
            String lowerQuery = searchQuery.toLowerCase();
            int matchIndex = lowerName.indexOf(lowerQuery);
            
            if (matchIndex != -1) {
                // Split into: before match, match, after match
                String before = moduleName.substring(0, matchIndex);
                String match = moduleName.substring(matchIndex, matchIndex + searchQuery.length());
                String after = moduleName.substring(matchIndex + searchQuery.length());
                
                // Render before match
                if (!before.isEmpty()) {
                    String prefix = module.isToggled() ? "" : ChatFormatting.GRAY.toString();
                    EUClient.FONT_MANAGER.drawTextWithShadow(context, prefix + before, textX, textY, Color.WHITE);
                    textX += EUClient.FONT_MANAGER.getWidth(before);
                }
                
                // Render match with highlight background
                Color highlightColor = ClickGuiScreen.getButtonColor(getY(), 200);
                int matchWidth = EUClient.FONT_MANAGER.getWidth(match);
                Renderer2D.renderQuad(context, textX - 1, textY - 1, textX + matchWidth + 1, textY + EUClient.FONT_MANAGER.getHeight(), highlightColor);
                EUClient.FONT_MANAGER.drawTextWithShadow(context, match, textX, textY, Color.WHITE);
                textX += matchWidth;
                
                // Render after match
                if (!after.isEmpty()) {
                    String prefix = module.isToggled() ? "" : ChatFormatting.GRAY.toString();
                    EUClient.FONT_MANAGER.drawTextWithShadow(context, prefix + after, textX, textY, Color.WHITE);
                }
            } else {
                // No match found (shouldn't happen if filtering is correct)
                EUClient.FONT_MANAGER.drawTextWithShadow(context, (module.isToggled() ? "" : ChatFormatting.GRAY) + moduleName, textX, textY, Color.WHITE);
            }
        } else {
            // No search query, render normally
            EUClient.FONT_MANAGER.drawTextWithShadow(context, (module.isToggled() ? "" : ChatFormatting.GRAY) + moduleName, textX, textY, Color.WHITE);
        }

        if(revealHeight > 0) {
            int clipTop = getY() + getHeight();
            context.enableScissor(getX(), clipTop, getX() + getWidth(), clipTop + revealHeight);
            for(Button button : buttons) {
                if(!button.isVisible()) continue;
                button.render(context, mouseX, mouseY, delta);
                if(button.isHovering(mouseX, mouseY) && EUClient.CLICK_GUI.getDescriptionFrame().getDescription().isEmpty()) EUClient.CLICK_GUI.getDescriptionFrame().setDescription(button.getDescription());
            }
            context.disableScissor();
        }
    }

    @Override
    public void mouseClicked(double mouseX, double mouseY, int button) {
        if(isHovering(mouseX, mouseY)) {
            if(button == 0) {
                module.setToggled(!module.isToggled());
                playClickSound();
            } else if(button == 1) {
                open = !open;
                // Same fix as ClickGuiScreen's whole-panel slide (see its slideAnim comment):
                // EASE_OUT_QUAD on BOTH directions makes closing look like it never decelerates --
                // ease-out's slow-down phase only happens approaching the target, meaningless when
                // the target is 0. Swap to ease-in when closing so the row-reveal actually starts
                // slow and speeds up while collapsing, matching how opening decelerates into place.
                openAnim.setEasing(open ? Easing.Method.EASE_OUT_QUAD : Easing.Method.EASE_IN_QUAD);
                playClickSound();
            }
        }

        if(open) {
            for(Button b : buttons) {
                if(!b.isVisible()) continue;
                b.mouseClicked(mouseX, mouseY, button);
            }
        }
    }

    @Override
    public void mouseReleased(double mouseX, double mouseY, int button) {
        for(Button b : buttons) {
            b.mouseReleased(mouseX, mouseY, button);
        }
    }

    @Override
    public void mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        for (Button b : buttons) {
            b.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        }
    }

    @Override
    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        if(open) {
            for(Button b : buttons) {
                if(!b.isVisible()) continue;
                b.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }

    @Override
    public void charTyped(char chr, int modifiers) {
        if(open) {
            for(Button b : buttons) {
                if(!b.isVisible()) continue;
                b.charTyped(chr, modifiers);
            }
        }
    }
}
