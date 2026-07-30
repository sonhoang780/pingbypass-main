package eu.client.modules.impl.movement;

import eu.client.events.SubscribeEvent;
import eu.client.events.impl.TickEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;

@RegisterModule(name = "Parkour", description = "Automatically jumps whenever you're at the edge of a block.", category = Module.Category.MOVEMENT)
public class ParkourModule extends Module {
    private boolean jumping = false;

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null) return;

        if (mc.player.onGround() && !mc.player.isShiftKeyDown() && mc.level.noCollision(mc.player.getBoundingBox().move(0.0, -0.5, 0.0).expandTowards(-0.001, 0.0, -0.001))) {
            mc.options.keyJump.setDown(true);
            jumping = true;
        } else if (jumping) {
            jumping = false;
            mc.options.keyJump.setDown(false);
        }
    }

    @Override
    public void onDisable() {
        if (jumping) {
            mc.options.keyJump.setDown(false);
            jumping = false;
        }
    }
}
