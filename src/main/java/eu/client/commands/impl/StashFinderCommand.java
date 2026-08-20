package eu.client.commands.impl;

import eu.client.EUClient;
import eu.client.commands.Command;
import eu.client.commands.RegisterCommand;
import eu.client.modules.impl.visuals.StashFinderModule;
import eu.client.modules.impl.visuals.stashfinder.StashWebhook;
import eu.client.utils.chat.ChatUtils;

import java.util.List;
import java.util.Map;

@RegisterCommand(name = "stashfinder", aliases = {"stash"}, tag = "StashFinder", description = "Configures StashFinder's Discord webhook and controls scan state.", syntax = "<webhook <url> | userid <id> | test | reset>")
public class StashFinderCommand extends Command {

    @Override
    public List<String> getSuggestions(String[] args) {
        if (args.length == 0) return List.of("webhook", "userid", "test", "reset");
        return List.of();
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            messageSyntax();
            return;
        }

        switch (args[0].toLowerCase()) {
            case "webhook" -> {
                if (args.length < 2) {
                    EUClient.CHAT_MANAGER.tagged("Current webhook: " + ChatUtils.getPrimary() + (StashWebhook.getWebhookUrl().isBlank() ? "None" : StashWebhook.getWebhookUrl()), getTag());
                    return;
                }
                String url = args[1];
                StashWebhook.setWebhookUrl(url);
                StashFinderModule module = EUClient.MODULE_MANAGER.getModule(StashFinderModule.class);
                if (module != null) module.webhookUrl.setValue(url);
                EUClient.CHAT_MANAGER.tagged("Webhook URL updated.", getTag());
            }
            case "userid" -> {
                if (args.length < 2) {
                    EUClient.CHAT_MANAGER.tagged("Current User ID: " + ChatUtils.getPrimary() + (StashWebhook.getUserId().isBlank() ? "None" : StashWebhook.getUserId()), getTag());
                    return;
                }
                String id = args[1];
                StashWebhook.setUserId(id);
                StashFinderModule module = EUClient.MODULE_MANAGER.getModule(StashFinderModule.class);
                if (module != null) module.userId.setValue(id);
                EUClient.CHAT_MANAGER.tagged("User ID updated (will be pinged on stash finds).", getTag());
            }
            case "test" -> {
                StashFinderModule module = EUClient.MODULE_MANAGER.getModule(StashFinderModule.class);
                String url = module != null && !module.webhookUrl.getValue().isBlank() ? module.webhookUrl.getValue() : StashWebhook.getWebhookUrl();
                String id = module != null && !module.userId.getValue().isBlank() ? module.userId.getValue() : StashWebhook.getUserId();

                if (url.isBlank()) {
                    EUClient.CHAT_MANAGER.tagged("No webhook URL configured. Use " + ChatUtils.getPrimary() + ".stashfinder webhook <url>" + ChatUtils.getSecondary() + " first.", getTag());
                } else {
                    StashWebhook.send(0, 0, "Test Dimension", Map.of("Chests", 25, "Shulkers", 8), url, id);
                    EUClient.CHAT_MANAGER.tagged("Test webhook dispatched.", getTag());
                }
            }
            case "reset" -> {
                StashFinderModule module = EUClient.MODULE_MANAGER.getModule(StashFinderModule.class);
                if (module != null) {
                    module.resetCurrentWorldStore();
                    EUClient.CHAT_MANAGER.tagged("Scan history cleared for this world. All chunks will be re-evaluated.", getTag());
                }
            }
            default -> messageSyntax();
        }
    }
}
