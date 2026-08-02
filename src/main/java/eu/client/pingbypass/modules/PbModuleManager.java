package eu.client.pingbypass.modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Registry of proxy-side PbModule instances. Replaces ProxyModuleManager (deleted --
 * see plan Task 13): instead of reusing client Module instances directly, each registered
 * module here is a standalone PbModule implementation (ServerAutoCrystal, ServerAutoTotem, ...).
 */
public class PbModuleManager {
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
