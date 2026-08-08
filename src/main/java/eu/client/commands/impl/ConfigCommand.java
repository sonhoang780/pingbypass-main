package eu.client.commands.impl;

import eu.client.EUClient;
import eu.client.commands.Command;
import eu.client.commands.RegisterCommand;
import eu.client.utils.chat.ChatUtils;
import eu.client.utils.system.FileUtils;

import java.io.IOException;
import java.util.List;

@RegisterCommand(name = "config", tag = "Config", description = "Allows you to manage the client's configuration system.", syntax = "<load|save> <[name]> | <reload|save|current>")
public class ConfigCommand extends Command {
    @Override
    public List<String> getSuggestions(String[] args) {
        if (args.length == 0) return List.of("load", "save", "reload", "current");
        if (args.length == 1 && (args[0].equalsIgnoreCase("load") || args[0].equalsIgnoreCase("save"))) {
            return savedConfigNames();
        }
        return List.of();
    }

    private List<String> savedConfigNames() {
        java.io.File dir = new java.io.File(EUClient.MOD_NAME + "/Configs");
        java.io.File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return List.of();

        return java.util.Arrays.stream(files)
                .map(java.io.File::getName)
                .map(name -> name.substring(0, name.length() - ".json".length()))
                .sorted()
                .toList();
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "load" -> {
                    if (!FileUtils.fileExists(EUClient.MOD_NAME + "/Configs/" + args[1] + ".json")) {
                        EUClient.CHAT_MANAGER.tagged("The specified configuration does not exist.", getTag(), getName());
                        return;
                    }

                    try {
                        EUClient.CONFIG_MANAGER.loadModules(args[1]);
                        EUClient.CHAT_MANAGER.tagged("Successfully loaded the " + ChatUtils.getPrimary() + args[1] + ChatUtils.getSecondary() + " configuration.", getTag(), getName());
                    } catch (IOException exception) {
                        EUClient.CHAT_MANAGER.tagged("Failed to load the " + ChatUtils.getPrimary() + args[1] + ChatUtils.getSecondary() + " configuration.", getTag(), getName());
                    }
                }
                case "save" -> {
                    try {
                        EUClient.CONFIG_MANAGER.saveModules(args[1]);
                        EUClient.CHAT_MANAGER.tagged("Successfully saved the configuration to " + ChatUtils.getPrimary() + args[1] + ".json" + ChatUtils.getSecondary() + ".", getTag(), getName());
                    } catch (IOException exception) {
                        EUClient.CHAT_MANAGER.tagged("Failed to save the " + ChatUtils.getPrimary() + args[1] + ChatUtils.getSecondary() + " configuration.", getTag(), getName());
                    }
                }
                default -> messageSyntax();
            }
        } else if (args.length == 1) {
            switch (args[0].toLowerCase()) {
                case "reload" -> {
                    EUClient.CONFIG_MANAGER.loadConfig();
                    EUClient.CHAT_MANAGER.tagged("Successfully reloaded the current configuration.", getTag(), getName());
                }
                case "save" -> {
                    EUClient.CONFIG_MANAGER.saveConfig();
                    EUClient.CHAT_MANAGER.tagged("Successfully saved the current configuration.", getTag(), getName());
                }
                case "current" -> EUClient.CHAT_MANAGER.tagged("The client is currently using the " + ChatUtils.getPrimary() + EUClient.CONFIG_MANAGER.getCurrentConfig() + ChatUtils.getSecondary() + " configuration.", getTag(), getName());
                default -> messageSyntax();
            }
        } else {
            messageSyntax();
        }
    }
}
