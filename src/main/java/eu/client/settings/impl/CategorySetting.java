package eu.client.settings.impl;

import lombok.Getter;
import lombok.Setter;
import eu.client.settings.Setting;
import eu.client.utils.animations.Animation;
import eu.client.utils.animations.Easing;

@Getter @Setter
public class CategorySetting extends Setting {
    private boolean open = false;

    // Same slide-reveal pattern as ModuleButton's settings panel / ModeButton's dropdown: the
    // member settings' visibility (and Frame's height contribution for them) now follows this
    // animated amount instead of snapping instantly on the raw `open` boolean.
    private final Animation openAnim = new Animation(180, Easing.Method.EASE_OUT_QUAD);

    public CategorySetting(String name, String description) {
        super(name, name, description, new Setting.Visibility());
    }

    public CategorySetting(String name, String tag, String description) {
        super(name, tag, description, new Setting.Visibility());
    }

    public CategorySetting(String name, String description, Setting.Visibility visibility) {
        super(name, name, description, visibility);
    }

    public CategorySetting(String name, String tag, String description, Setting.Visibility visibility) {
        super(name, tag, description, visibility);
    }

    public float getOpenAmount() {
        return openAnim.get(open ? 1f : 0f);
    }

    public static class Visibility extends Setting.Visibility {
        private final CategorySetting value;

        public Visibility(CategorySetting value) {
            super(value);
            this.value = value;
        }

        public CategorySetting getValue() {
            return value;
        }

        @Override
        public void update() {
            if (value.getVisibility() != null) {
                value.getVisibility().update();
                if (!value.getVisibility().isVisible()) {
                    setVisible(false);
                    return;
                }
            }

            // Stays "visible" for the whole close animation, not just while value.isOpen() --
            // otherwise the member settings vanish instantly and only the height scale animates,
            // which looks identical to no animation at all.
            setVisible(value.getOpenAmount() > 0.001f);
        }
    }
}
