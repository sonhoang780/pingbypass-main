package eu.client.pingbypass.modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Registry of proxy-side PbModule instances. Replaces ProxyModuleManager (deleted --
 * see plan Task 13): instead of reusing client Module instances directly, each registered
 * module here is a standalone PbModule implementation (ServerAutoCrystal, ServerAutoTotem, ...).
 */
public class PbModuleManager {
    /**
     * Names of client Module classes that have been migrated to a PbModule submodule here
     * (ServerAutoCrystal, ServerAutoTotem, ServerSurround, ...). The client-side Module.setToggled
     * uses this to decide whether to relay a toggle to the proxy even though these modules are
     * no longer proxyEnhanced (that flag now only gates the not-yet-migrated modules still on
     * ProxyModuleManager). Update this set as more modules migrate.
     */
    public static final Set<String> MIGRATED_MODULE_NAMES = Set.of("AutoCrystal", "AutoTotem", "Surround", "SpeedMine", "AutoTrap");

    private final List<PbModule> modules = new ArrayList<>();

    public void register(PbModule module) {
        modules.add(module);
        modules.sort(Comparator.comparing(PbModule::getName));
    }

    public PbModule getModule(String name) {
        return modules.stream()
                .filter(m -> m.getName().equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }

    public List<PbModule> getModules() {
        return modules;
    }

    public void tick() {
        for (PbModule module : modules) {
            if (module.isToggled()) module.tick();
        }
    }
}
