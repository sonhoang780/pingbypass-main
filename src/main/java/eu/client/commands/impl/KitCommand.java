package eu.client.commands.impl;

import eu.client.EUClient;
import eu.client.commands.Command;
import eu.client.commands.RegisterCommand;
import eu.client.modules.impl.player.RekitModule;

import java.util.List;

// Ported from example-addon-master's KitCommand -- manages Rekit's saved kits.
@RegisterCommand(name = "kit", tag = "Kit", description = "Manages Rekit's saved kits.", syntax = "<save|load|delete> <name> | <list|active>")
public class KitCommand extends Command {
    @Override
    public List<String> getSuggestions(String[] args) {
        if (args.length == 0) return List.of("save", "load", "delete", "list", "active");
        if (args.length == 1 && (args[0].equalsIgnoreCase("load") || args[0].equalsIgnoreCase("delete")))
            return EUClient.MODULE_MANAGER.getModule(RekitModule.class).getKitNames();
        return List.of();
    }

    @Override
    public void execute(String[] args) {
        RekitModule rekit = EUClient.MODULE_MANAGER.getModule(RekitModule.class);

        if (args.length == 0) { messageSyntax(); return; }

        switch (args[0].toLowerCase()) {
            case "save" -> {
                if (args.length < 2) { messageSyntax(); return; }
                rekit.saveKit(args[1]);
            }
            case "load" -> {
                if (args.length < 2) rekit.listKits();
                else rekit.loadKit(args[1]);
            }
            case "delete" -> {
                if (args.length < 2) { messageSyntax(); return; }
                rekit.deleteKit(args[1]);
            }
            case "active" -> rekit.showActiveKit();
            case "list" -> rekit.listKits();
            default -> messageSyntax();
        }
    }
}
