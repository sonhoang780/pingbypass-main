package eu.client.modules.impl.movement;

import eu.client.events.SubscribeEvent;
import eu.client.events.impl.TickEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;

@RegisterModule(name = "AutoWalk", description = "Automatically walks at all times.", category = Module.Category.MOVEMENT)
public class AutoWalkModule extends Module {
    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null) return;
        mc.options.keyUp.setDown(true);
    }

    @Override
    public void onDisable() {
        if (mc.player == null || mc.level == null) return;
        mc.options.keyUp.setDown(false);
    }
}
