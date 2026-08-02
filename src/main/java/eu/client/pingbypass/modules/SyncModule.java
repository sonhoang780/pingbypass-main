package eu.client.pingbypass.modules;

import eu.client.settings.Setting;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ColorSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.settings.impl.StringSetting;

/**
 * Mirrors a toggle/setting change from the client's real Module onto a proxy-side PbModule.
 * Called from PbPlayHandler when a C2SModuleTogglePacket/C2SSettingChangePacket arrives for
 * a module name that has a registered PbModule (see PbModuleManager). The per-type dispatch
 * below matches PbPlayHandler.handleSettingChange (the existing mechanism for applying an
 * incoming C2SSettingChangePacket onto a client Module's Setting) -- Setting has no generic
 * setValueFromString(String), each concrete Setting subtype has its own setValue.
 */
public class SyncModule {
    public static void applyToggle(PbModule module, boolean enabled) {
        module.setToggled(enabled);
    }

    public static void applySetting(PbModule module, String settingName, String value) {
        Setting setting = module.getSetting(settingName);
        if (setting == null) return;

        if (setting instanceof BooleanSetting s) {
            s.setValue(Boolean.parseBoolean(value));
        } else if (setting instanceof NumberSetting s) {
            switch (s.getType()) {
                case INTEGER -> s.setValue(Integer.parseInt(value));
                case LONG -> s.setValue(Long.parseLong(value));
                case FLOAT -> s.setValue(Float.parseFloat(value));
                case DOUBLE -> s.setValue(Double.parseDouble(value));
            }
        } else if (setting instanceof ModeSetting s) {
            s.setValue(value);
        } else if (setting instanceof StringSetting s) {
            s.setValue(value);
        } else if (setting instanceof ColorSetting s) {
            String[] parts = value.split(",");
            if (parts.length >= 4) {
                s.setColor(new java.awt.Color(
                        Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]), Integer.parseInt(parts[3])));
                if (parts.length >= 5) s.setSync(Boolean.parseBoolean(parts[4]));
                if (parts.length >= 6) s.setRainbow(Boolean.parseBoolean(parts[5]));
            }
        }
    }
}
