package eu.client.commands.impl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import eu.client.EUClient;
import eu.client.commands.Command;
import eu.client.commands.RegisterCommand;
import eu.client.modules.Module;
import eu.client.settings.Setting;
import eu.client.settings.impl.*;
import eu.client.utils.chat.ChatUtils;
import eu.client.utils.system.FileUtils;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.StringJoiner;

@RegisterCommand(name = "config", aliases = {"cfg"}, tag = "Config", description = "Allows you to manage the client's configuration system.", syntax = "<load|save> <[name]> | global <[module]> <load|save> <[name]> | <reload|save|current>")
public class ConfigCommand extends Command {
    @Override
    public List<String> getSuggestions(String[] args) {
        if (args.length == 0) return List.of("load", "save", "reload", "current", "global");
        if (args.length == 1) {
            if (args[0].equalsIgnoreCase("load") || args[0].equalsIgnoreCase("save")) {
                return savedConfigNames();
            } else if (args[0].equalsIgnoreCase("global")) {
                return EUClient.MODULE_MANAGER.getModules().stream().map(Module::getName).toList();
            }
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("global")) {
            return List.of("load", "save");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("global") && (args[2].equalsIgnoreCase("load") || args[2].equalsIgnoreCase("save"))) {
            return globalConfigNames(args[1]);
        }
        return List.of();
    }

    private List<String> savedConfigNames() {
        File dir = new File(EUClient.MOD_NAME + "/Configs");
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return List.of();

        return java.util.Arrays.stream(files)
                .map(File::getName)
                .map(name -> name.substring(0, name.length() - ".json".length()))
                .sorted()
                .toList();
    }

    private List<String> globalConfigNames(String moduleName) {
        File dir = new File(EUClient.MOD_NAME + "/GlobalConfigs/" + moduleName);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return List.of();

        return java.util.Arrays.stream(files)
                .map(File::getName)
                .map(name -> name.substring(0, name.length() - ".json".length()))
                .sorted()
                .toList();
    }

    @Override
    public void execute(String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("global")) {
            if (args.length == 4) {
                Module module = EUClient.MODULE_MANAGER.getModule(args[1]);
                if (module == null) {
                    EUClient.CHAT_MANAGER.tagged("Could not find the module specified.", getTag(), getName());
                    return;
                }
                String action = args[2].toLowerCase();
                String configName = args[3];

                switch (action) {
                    case "save" -> saveGlobalConfig(module, configName);
                    case "load" -> loadGlobalConfig(module, configName);
                    default -> EUClient.CHAT_MANAGER.info("config global <module> <load|save> <name>");
                }
            } else {
                EUClient.CHAT_MANAGER.info("config global <module> <load|save> <name>");
            }
            return;
        }

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

    private void saveGlobalConfig(Module module, String configName) {
        try {
            JsonObject moduleObject = new JsonObject();
            moduleObject.add("Status", new JsonPrimitive(module.isToggled()));

            JsonObject settingsObject = new JsonObject();
            for (Setting uncastedSetting : module.getSettings()) {
                switch (uncastedSetting) {
                    case BooleanSetting setting -> settingsObject.add(setting.getName(), new JsonPrimitive(setting.getValue()));
                    case NumberSetting setting -> settingsObject.add(setting.getName(), new JsonPrimitive(setting.getValue()));
                    case ModeSetting setting -> settingsObject.add(setting.getName(), new JsonPrimitive(setting.getValue()));
                    case StringSetting setting -> settingsObject.add(setting.getName(), new JsonPrimitive(setting.getValue()));
                    case BindSetting setting -> settingsObject.add(setting.getName(), new JsonPrimitive(setting.getValue() + "," + setting.getMode()));
                    case ColorSetting setting -> settingsObject.add(setting.getName(), new JsonPrimitive(setting.getValue().getColor().getRed() + "," + setting.getValue().getColor().getGreen() + "," + setting.getValue().getColor().getBlue() + "," + setting.getValue().getColor().getAlpha() + "," + setting.isSync() + "," + setting.isRainbow()));
                    case WhitelistSetting setting -> {
                        StringJoiner objects = new StringJoiner(",");
                        for (String id : setting.getWhitelistIds()) objects.add(id);
                        settingsObject.add(setting.getName(), new JsonPrimitive(objects.toString()));
                    }
                    case PositionSetting setting -> settingsObject.add(setting.getName(), new JsonPrimitive(setting.getX() + "," + setting.getY()));
                    default -> {}
                }
            }

            moduleObject.add("Settings", settingsObject);

            File dir = new File(EUClient.MOD_NAME + "/GlobalConfigs/" + module.getName());
            if (!dir.exists()) dir.mkdirs();

            File globalFile = new File(dir, configName + ".json");
            try (FileWriter writer = new FileWriter(globalFile)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(moduleObject, writer);
            }
            EUClient.CHAT_MANAGER.tagged("Successfully saved global config " + ChatUtils.getPrimary() + configName + ChatUtils.getSecondary() + " for module " + ChatUtils.getPrimary() + module.getName() + ChatUtils.getSecondary() + ".", getTag(), getName());
        } catch (Exception e) {
            EUClient.CHAT_MANAGER.tagged("Failed to save global config: " + e.getMessage(), getTag(), getName());
            e.printStackTrace();
        }
    }

    private void loadGlobalConfig(Module module, String configName) {
        File globalFile = new File(EUClient.MOD_NAME + "/GlobalConfigs/" + module.getName() + "/" + configName + ".json");
        if (!globalFile.exists()) {
            EUClient.CHAT_MANAGER.tagged("Global config " + ChatUtils.getPrimary() + configName + ChatUtils.getSecondary() + " does not exist for " + module.getName() + ".", getTag(), getName());
            return;
        }

        try (FileReader reader = new FileReader(globalFile)) {
            // Đọc cục setting riêng lẻ của Module
            JsonObject globalModuleJson = JsonParser.parseReader(reader).getAsJsonObject();

            // Quét và tiêm vào toàn bộ config đang có trong thư mục Configs
            File configsDir = new File(EUClient.MOD_NAME + "/Configs");
            File[] configFiles = configsDir.listFiles((d, name) -> name.endsWith(".json"));
            if (configFiles != null) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                for (File file : configFiles) {
                    JsonObject root;
                    try (FileReader fr = new FileReader(file)) {
                        root = JsonParser.parseReader(fr).getAsJsonObject();
                    }
                    JsonObject modules = root.has("Modules") ? root.getAsJsonObject("Modules") : new JsonObject();
                    modules.add(module.getName(), globalModuleJson);
                    root.add("Modules", modules);

                    try (FileWriter fw = new FileWriter(file)) {
                        gson.toJson(root, fw);
                    }
                }
            }

            // Reload config hiện tại để apply thay đổi ngay vào game
            EUClient.CONFIG_MANAGER.loadConfig();
            EUClient.CHAT_MANAGER.tagged("Successfully loaded global config " + ChatUtils.getPrimary() + configName + ChatUtils.getSecondary() + " for module " + ChatUtils.getPrimary() + module.getName() + ChatUtils.getSecondary() + " across all profiles.", getTag(), getName());

        } catch (Exception e) {
            EUClient.CHAT_MANAGER.tagged("Failed to load global config: " + e.getMessage(), getTag(), getName());
            e.printStackTrace();
        }
    }
}