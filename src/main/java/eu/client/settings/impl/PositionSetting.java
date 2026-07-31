package eu.client.settings.impl;

import lombok.Getter;
import lombok.Setter;
import eu.client.settings.Setting;

/**
 * A drag-offset (x, y) for a HUD element, persisted like any other setting but never rendered as a
 * ClickGui row (ModuleButton only builds buttons for the Setting subtypes it explicitly matches) --
 * it's edited exclusively via HUDEditorModule's on-screen drag overlay.
 */
@Getter @Setter
public class PositionSetting extends Setting {
    private float x;
    private float y;

    public PositionSetting(String name, String description) {
        super(name, name, description, new Setting.Visibility());
        this.x = 0;
        this.y = 0;
    }

    public void set(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public void resetValue() {
        this.x = 0;
        this.y = 0;
    }
}
