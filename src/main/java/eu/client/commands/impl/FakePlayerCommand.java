package eu.client.commands.impl;

import eu.client.EUClient;
import eu.client.commands.Command;
import eu.client.commands.RegisterCommand;
import eu.client.modules.impl.miscellaneous.FakePlayerModule;

// FakePlayerModule already copies mc.player's pose/equipment/health on spawn (onEnable ->
// restoreFrom/saveWithoutId+load) -- no args needed, just toggle. "fakeplayer" is a direct alias so
// ".fakeplayer" alone spawns/despawns, same as homovore's, in addition to "togglefp"/"fp".
@RegisterCommand(name = "togglefp", aliases = {"fp", "fakeplayer"}, description = "Spawns in a fake player copying your current pose, or despawns it if already active.", syntax = "")
public class FakePlayerCommand extends Command {
    @Override
    public void execute(String[] args) {
        FakePlayerModule module = EUClient.MODULE_MANAGER.getModule(FakePlayerModule.class);
        module.setToggled(!module.isToggled());
    }
}
