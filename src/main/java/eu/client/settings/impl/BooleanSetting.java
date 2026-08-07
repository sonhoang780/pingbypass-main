package eu.client.settings.impl;

import lombok.Setter;
import eu.client.EUClient;
import eu.client.events.impl.SettingChangeEvent;
import eu.client.settings.Setting;
import eu.client.utils.animations.Animation;
import eu.client.utils.animations.Easing;

@Setter
public class BooleanSetting extends Setting {
    private boolean value;
    private final boolean defaultValue;

    // Same slide-reveal pattern as CategorySetting.openAnim -- a BooleanSetting.Visibility-gated
    // row (e.g. MenuModule's Mode setting, only shown while MainMenu is on) used to pop in/out
    // instantly the moment the boolean flipped, the only kind of settings-panel reveal in the
    // whole GUI that didn't animate.
    //
    // NOT a field initializer (that would always seed prev=current=0) -- built in each
    // constructor instead, seeded to already match the real starting `value`. A setting that
    // defaults to true (e.g. MenuModule.mainMenu) would otherwise have its Animation start
    // "closed" while the setting itself starts "open": the very first getOpenAmount() read sees
    // current(1) != internal current(0) and plays a full phantom opening animation on its own,
    // before the user ever touches the toggle -- the reported "animation only plays once" (that
    // one bogus auto-play, then every REAL click afterward finds the Animation already caught up
    // to whatever value was read most recently and has nothing left to animate toward).
    private final Animation openAnim;

    public float getOpenAmount() {
        return openAnim.get(value ? 1f : 0f);
    }

    public BooleanSetting(String name, String description, boolean value) {
        super(name, name, description, new Setting.Visibility());
        this.value = value;
        this.defaultValue = value;
        this.openAnim = newOpenAnim(value);
    }

    public BooleanSetting(String name, String tag, String description, boolean value) {
        super(name, tag, description, new Setting.Visibility());
        this.value = value;
        this.defaultValue = value;
        this.openAnim = newOpenAnim(value);
    }

    public BooleanSetting(String name, String description, Setting.Visibility visibility, boolean value) {
        super(name, name, description, visibility);
        this.value = value;
        this.defaultValue = value;
        this.openAnim = newOpenAnim(value);
    }

    public BooleanSetting(String name, String tag, String description, Setting.Visibility visibility, boolean value) {
        super(name, tag, description, visibility);
        this.value = value;
        this.defaultValue = value;
        this.openAnim = newOpenAnim(value);
    }

    private static Animation newOpenAnim(boolean startingValue) {
        float start = startingValue ? 1f : 0f;
        return new Animation(start, start, 180, Easing.Method.EASE_OUT_QUAD);
    }

    public boolean getValue() {
        return value;
    }

    public void setValue(boolean value) {
        this.value = value;
        EUClient.EVENT_HANDLER.post(new SettingChangeEvent(this));
    }


    public boolean getDefaultValue() {
        return defaultValue;
    }

    public void resetValue() {
        value = defaultValue;
    }

    public static class Visibility extends Setting.Visibility {
        private final BooleanSetting value;
        private final boolean targetValue;

        public Visibility(BooleanSetting value, boolean targetValue) {
            super(value);
            this.value = value;
            this.targetValue = targetValue;
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

            // Stays "visible" for the whole close animation, not just while the raw value already
            // matches -- same reasoning as CategorySetting.Visibility: otherwise the row vanishes
            // instantly and only its height scales down, which reads as no animation at all.
            setVisible(getOpenAmount() > 0.001f);
        }

        /** 0..1 toward "matches targetValue" -- mirrors {@link CategorySetting#getOpenAmount()} for Frame's row-height scaling. */
        public float getOpenAmount() {
            float amount = value.getOpenAmount();
            return targetValue ? amount : 1f - amount;
        }
    }
}
