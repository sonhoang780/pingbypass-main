package eu.client.commands.impl;

import eu.client.EUClient;
import eu.client.commands.Command;
import eu.client.commands.RegisterCommand;
import eu.client.modules.impl.core.FriendModule;
import eu.client.utils.chat.ChatUtils;

import java.util.List;

@RegisterCommand(name = "friend", tag = "Friend", description = "Allows you to manage the client's friend list.", syntax = "<add|del> <[player]> | <clear|list>", aliases = {"f", "friends"})
public class FriendCommand extends Command {
    @Override
    public List<String> getSuggestions(String[] args) {
        if (args.length == 0) return List.of("add", "del", "clear", "list");
        if (args.length == 1 && args[0].equalsIgnoreCase("del")) return EUClient.FRIEND_MANAGER.getFriends();
        // "add" never suggested anything -- only "del" read FRIEND_MANAGER's own list, "add"
        // fell through to the empty default. Suggest online players instead (excluding yourself
        // and anyone already friended), matching what "add" actually needs.
        if (args.length == 1 && args[0].equalsIgnoreCase("add") && mc.level != null && mc.player != null) {
            // getName().getString(), not getGameProfile().getName() -- matches how the rest of
            // the codebase (SurroundModule/AutoCrystal's own friend checks) already resolves the
            // name FRIEND_MANAGER.contains() compares against.
            return mc.level.players().stream()
                    .filter(player -> player != mc.player)
                    .map(player -> player.getName().getString())
                    .filter(name -> !EUClient.FRIEND_MANAGER.contains(name))
                    .toList();
        }
        return List.of();
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "add" -> {
                    if (!EUClient.FRIEND_MANAGER.contains(args[1])) {
                        if (EUClient.MODULE_MANAGER.getModule(FriendModule.class).friendMessage.getValue()) {
                            EUClient.FRIEND_MANAGER.sendFriendMessage(args[1]);
                        }
                        EUClient.FRIEND_MANAGER.add(args[1]);
                        EUClient.CHAT_MANAGER.tagged("Successfully added " + ChatUtils.getPrimary() + args[1] + ChatUtils.getSecondary() + " to your friends list.", getTag(), getName());
                    } else {
                        EUClient.CHAT_MANAGER.tagged(ChatUtils.getPrimary() + args[1] + ChatUtils.getSecondary() + " is already on your friends list.", getTag(), getName());
                    }
                }
                case "del" -> {
                    if (EUClient.FRIEND_MANAGER.contains(args[1])) {
                        EUClient.FRIEND_MANAGER.remove(args[1]);
                        EUClient.CHAT_MANAGER.tagged("Successfully removed " + ChatUtils.getPrimary() + args[1] + ChatUtils.getSecondary() + " from your friends list.", getTag(), getName());
                    } else {
                        EUClient.CHAT_MANAGER.tagged(ChatUtils.getPrimary() + args[1] + ChatUtils.getSecondary() + " is not on your friends list.", getTag(), getName());
                    }
                }
                default -> messageSyntax();
            }
        } else if (args.length == 1) {
            switch (args[0].toLowerCase()) {
                case "clear" -> {
                    EUClient.FRIEND_MANAGER.clear();
                    EUClient.CHAT_MANAGER.tagged("Successfully cleared your friends list.", getTag(), getName() + "-list");
                }
                case "list" -> {
                    List<String> friends = EUClient.FRIEND_MANAGER.getFriends();

                    if (friends.isEmpty()) {
                        EUClient.CHAT_MANAGER.tagged("You currently have no friends.", getTag());
                    } else {
                        StringBuilder builder = new StringBuilder();
                        int index = 0;

                        for (String name : friends) {
                            index++;
                            builder.append(ChatUtils.getSecondary()).append(name)
                                    .append(index == friends.size() ? "" : ", ");
                        }

                        EUClient.CHAT_MANAGER.message("Friends " + ChatUtils.getPrimary() + "[" + ChatUtils.getSecondary() + friends.size() + ChatUtils.getPrimary() + "]: " + ChatUtils.getSecondary() + builder, getName() + "-list");
                    }
                }
                default -> messageSyntax();
            }
        } else {
            messageSyntax();
        }
    }
}