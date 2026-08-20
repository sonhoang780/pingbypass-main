package eu.client.commands.impl;

import eu.client.EUClient;
import eu.client.commands.Command;
import eu.client.commands.RegisterCommand;
import eu.client.modules.Module;

import java.util.ArrayList;
import java.util.List;

// .all off          -> disables every currently enabled (non-persistent) module. Persistent
//                      modules can't be toggled off (Module.setToggled returns early), so they're
//                      skipped automatically. Toggled silently (no per-module chat spam).
// .all off restore  -> re-enables exactly the modules that the last ".all off" turned off.
@RegisterCommand(name = "all", tag = "All", description = "Mass-toggles modules. 'off' disables all enabled modules; 'off restore' re-enables them.", syntax = "off | off restore")
public class AllCommand extends Command {
    // Remembers what the last ".all off" disabled, so ".all off restore" can bring them back.
    private static final List<Module> lastDisabled = new ArrayList<>();

    @Override
    public List<String> getSuggestions(String[] args) {
        if (args.length == 0) return List.of("off");
        if (args.length == 1 && args[0].equalsIgnoreCase("off")) return List.of("restore");
        return List.of();
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("off")) { messageSyntax(); return; }

        // .all off restore
        if (args.length == 2 && args[1].equalsIgnoreCase("restore")) {
            if (lastDisabled.isEmpty()) {
                EUClient.CHAT_MANAGER.tagged("Nothing to restore.", getTag(), getName());
                return;
            }
            int restored = 0;
            for (Module module : lastDisabled) {
                if (!module.isToggled()) { module.setToggled(true, false); restored++; }
            }
            lastDisabled.clear();
            EUClient.CHAT_MANAGER.tagged("Restored " + restored + " module(s).", getTag(), getName());
            return;
        }

        // .all off
        if (args.length == 1) {
            lastDisabled.clear();
            int disabled = 0;
            for (Module module : EUClient.MODULE_MANAGER.getModules()) {
                // Persistent modules never actually turn off, so don't record them for restore.
                if (module.isPersistent()) continue;
                if (module.isToggled()) {
                    module.setToggled(false, false); // silent toggle, no chat spam
                    lastDisabled.add(module);
                    disabled++;
                }
            }
            EUClient.CHAT_MANAGER.tagged("Disabled " + disabled + " module(s). Use '.all off restore' to bring them back.", getTag(), getName());
            return;
        }

        messageSyntax();
    }
}