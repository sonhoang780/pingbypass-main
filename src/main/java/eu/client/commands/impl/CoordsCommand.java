package eu.client.commands.impl;

import eu.client.EUClient;
import eu.client.commands.Command;
import eu.client.commands.RegisterCommand;
import eu.client.utils.chat.ChatUtils;
import net.minecraft.world.entity.player.Player;

import java.util.List;

@RegisterCommand(name = "coords", tag = "Coords", description = "Copies your position, or whispers it to a player.", syntax = "| <[player]>")
public class CoordsCommand extends Command {
    @Override
    public List<String> getSuggestions(String[] args) {
        if (args.length == 0) {
            return mc.level.players().stream().filter(p -> p != mc.player).map(p -> p.getName().getString()).toList();
        }
        return List.of();
    }

    @Override
    public void execute(String[] args) {
        if (mc.player == null || mc.level == null) return;

        String dimension = mc.player.level().dimension().identifier().toString().replace("minecraft:", "");
        String coords = (int) mc.player.getX() + " " + (int) mc.player.getY() + " " + (int) mc.player.getZ() + " (" + dimension + ")";

        if (args.length == 0) {
            mc.keyboardHandler.setClipboard(coords);
            EUClient.CHAT_MANAGER.tagged("Copied your position (" + ChatUtils.getPrimary() + coords + ChatUtils.getSecondary() + ") to the clipboard.", getTag(), getName());
            return;
        }

        if (args.length == 1) {
            String target = args[0];
            boolean online = mc.level.players().stream().map(Player::getName).anyMatch(name -> name.getString().equalsIgnoreCase(target));
            if (!online) {
                EUClient.CHAT_MANAGER.tagged("Could not find the player specified.", getTag(), getName());
                return;
            }

            mc.getConnection().sendChat("/w " + target + " " + coords);
            EUClient.CHAT_MANAGER.tagged("Sent your position to " + ChatUtils.getPrimary() + target + ChatUtils.getSecondary() + ".", getTag(), getName());
            return;
        }

        messageSyntax();
    }
}
