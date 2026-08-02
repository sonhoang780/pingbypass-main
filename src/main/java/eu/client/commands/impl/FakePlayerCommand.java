package eu.client.commands.impl;

import eu.client.EUClient;
import eu.client.commands.Command;
import eu.client.commands.RegisterCommand;
import eu.client.modules.impl.miscellaneous.FakePlayerModule;

@RegisterCommand(name = "togglefp", aliases = {"fp"}, description = "Toggles the FakePlayer module (spawns it in, or despawns it if already active).", syntax = "")
public class FakePlayerCommand extends Command {
    @Override
    public void execute(String[] args) {
        FakePlayerModule module = EUClient.MODULE_MANAGER.getModule(FakePlayerModule.class);
        module.setToggled(!module.isToggled());
    }
}
