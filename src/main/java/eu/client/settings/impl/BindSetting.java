package eu.client.settings.impl;

import lombok.Getter;
import lombok.Setter;
import eu.client.EUClient;
import eu.client.events.impl.SettingChangeEvent;
import eu.client.settings.Setting;

@Getter @Setter
public class BindSetting extends Setting {
    private int value;
    private final int defaultValue;

    // Bind: toggles the module on press (the only behavior before this). Hold: module is toggled
    // on only while the bind is physically held down, off otherwise. ReverseHold: the inverse --
    // toggled on normally, off only while the bind is held.
    public static final String[] MODES = new String[]{"Bind", "Hold", "ReverseHold"};
    private String mode = "Bind";
    // Hold/ReverseHold are only meaningful for a bind that some central place actively drives
    // based on "is the key currently down" (ModuleManager.onTick does this for every module's own
    // `bind`). Sub-binds a module reads itself as a one-shot press trigger (ElytraBind, AutoPlace,
    // ChorusControl's Confirm...) have no such driver -- their own key-read code only ever checks
    // "was this just pressed", so right-clicking them to Hold/ReverseHold silently did nothing
    // except relabel the button (reported: "vô lý", right-click switches to Hold/ReverseHold with
    // no real behavior behind it). disableHoldModes() opts a BindSetting out of the cycle entirely
    // so right-click is a no-op and the button always reads its real tag.
    private boolean holdModesSupported = true;

    public BindSetting disableHoldModes() {
        this.holdModesSupported = false;
        return this;
    }

    public BindSetting(String name, String description, int value) {
        super(name, name, description, new Setting.Visibility());
        this.value = value;
        this.defaultValue = value;
    }

    public BindSetting(String name, String tag, String description, int value) {
        super(name, tag, description, new Setting.Visibility());
        this.value = value;
        this.defaultValue = value;
    }

    public BindSetting(String name, String description, Setting.Visibility visibility, int value) {
        super(name, name, description, visibility);
        this.value = value;
        this.defaultValue = value;
    }

    public BindSetting(String name, String tag, String description, Setting.Visibility visibility, int value) {
        super(name, tag, description, visibility);
        this.value = value;
        this.defaultValue = value;
    }

    public void resetValue() {
        value = defaultValue;
        mode = "Bind";
    }

    public void setValue(int value) {
        this.value = value;
        EUClient.EVENT_HANDLER.post(new SettingChangeEvent(this));
    }

    public void cycleMode() {
        if (!holdModesSupported) return;
        int index = (java.util.Arrays.asList(MODES).indexOf(mode) + 1) % MODES.length;
        mode = MODES[index];
        EUClient.EVENT_HANDLER.post(new SettingChangeEvent(this));
    }

    public void setMode(String mode) {
        if (!holdModesSupported && !mode.equals("Bind")) return;
        if (!java.util.Arrays.asList(MODES).contains(mode)) return;
        this.mode = mode;
        EUClient.EVENT_HANDLER.post(new SettingChangeEvent(this));
    }

    public static class Visibility extends Setting.Visibility {
        private final BindSetting value;
        private final int targetValue;

        public Visibility(BindSetting value, int targetValue) {
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

            setVisible(value.getValue() == targetValue);
        }
    }
}
