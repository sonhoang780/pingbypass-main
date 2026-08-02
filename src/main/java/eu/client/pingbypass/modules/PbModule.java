package eu.client.pingbypass.modules;

import eu.client.settings.Setting;

import java.util.List;

/**
 * Base class for a proxy-side module implementation. Unlike ProxyModuleManager (deleted --
 * see plan Task 13), a PbModule does NOT share a Module instance with the client: it is a
 * standalone class that runs only on the proxy, reading settings mirrored from the client via
 * SyncModule. This removes the dual-execution race where the same Module object (or two
 * independent instances of the same class ticking in parallel on two separate JVMs) could act
 * on the same target at the same time with no coordination.
 */
public abstract class PbModule {
    private final String name;
    private boolean toggled = false;

    protected PbModule(String name) {
        this.name = name;
    }

    public String getName() { return name; }
    public boolean isToggled() { return toggled; }

    public void setToggled(boolean toggled) {
        this.toggled = toggled;
        if (toggled) onEnable(); else onDisable();
    }

    public void onEnable() {}
    public void onDisable() {}

    /** Called once per proxy tick while toggled. */
    public abstract void tick();

    public abstract List<Setting> getSettings();

    public Setting getSetting(String settingName) {
        return getSettings().stream()
                .filter(s -> s.getName().equalsIgnoreCase(settingName))
                .findFirst().orElse(null);
    }
}
