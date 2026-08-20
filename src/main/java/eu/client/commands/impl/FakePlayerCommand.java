package eu.client.commands.impl;

import eu.client.EUClient;
import eu.client.commands.Command;
import eu.client.commands.RegisterCommand;
import eu.client.modules.impl.miscellaneous.FakePlayerModule;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Pose;

import java.util.List;

@RegisterCommand(name = "fakeplayer", aliases = {"fp"}, description = "Spawns a client-side fake player to test on.", syntax = "[remove|pop|swim|record|stop|play|<name>]")
public class FakePlayerCommand extends Command {
    private static final byte TOTEM_POP_EVENT_ID = 35;

    @Override
    public void execute(String[] args) {
        FakePlayerModule fpModule = EUClient.MODULE_MANAGER.getModule(FakePlayerModule.class);
        if (fpModule == null) return;

        if (args.length == 0) {
            fpModule.setToggled(!fpModule.isToggled());
            return;
        }

        switch (args[0].toLowerCase()) {
            case "remove" -> {
                if (fpModule.isToggled()) fpModule.setToggled(false);
                else EUClient.CHAT_MANAGER.warn("No fake player spawned.");
            }
            case "pop" -> popBot(fpModule);
            case "swim" -> toggleSwim(fpModule);
            case "record" -> fpModule.startRecording();
            case "stop" -> fpModule.stopRecording();
            case "play" -> fpModule.startPlaying();
            default -> {
                fpModule.name.setValue(args[0]);
                if (!fpModule.isToggled()) {
                    fpModule.setToggled(true);
                } else {
                    // Tắt rồi bật lại để respawn với tên mới
                    fpModule.setToggled(false);
                    fpModule.setToggled(true);
                }
            }
        }
    }

    @Override
    public List<String> getSuggestions(String[] args) {
        if (args.length == 0) return List.of("remove", "pop", "swim", "record", "stop", "play");
        return List.of();
    }

    private boolean nullCheck() {
        return mc.level == null || mc.player == null;
    }

    private void popBot(FakePlayerModule fpModule) {
        if (nullCheck() || mc.getConnection() == null) {
            EUClient.CHAT_MANAGER.warn("You need to be in a world to do that.");
            return;
        }

        RemotePlayer fake = fpModule.getPlayer();
        if (fake == null || fake.level() != mc.level) {
            EUClient.CHAT_MANAGER.warn("No fake player spawned. Spawn one first.");
            return;
        }

        mc.getConnection().handleEntityEvent(new ClientboundEntityEventPacket(fake, TOTEM_POP_EVENT_ID));
        EUClient.EVENT_HANDLER.post(new eu.client.events.impl.PlayerPopEvent(fake, 1));
        EUClient.CHAT_MANAGER.tagged("Popped fake player.", "FakePlayer");
    }

    private void toggleSwim(FakePlayerModule fpModule) {
        if (nullCheck()) {
            EUClient.CHAT_MANAGER.warn("You need to be in a world to do that.");
            return;
        }

        RemotePlayer fake = fpModule.getPlayer();
        if (fake == null || fake.level() != mc.level) {
            EUClient.CHAT_MANAGER.warn("No fake player spawned. Spawn one first.");
            return;
        }

        Pose pose = fake.getPose() != Pose.SWIMMING ? Pose.SWIMMING : Pose.STANDING;
        fake.setPose(pose);
        fake.refreshDimensions();
        EUClient.CHAT_MANAGER.tagged(pose == Pose.SWIMMING ? "Fake player now in swim pose." : "Fake player now standing.", "FakePlayer");
    }
}