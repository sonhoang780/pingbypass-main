package eu.client.modules.impl.movement;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.NumberSetting;

@RegisterModule(name = "ReverseStep", description = "Makes it so that you fall down instantly at a specified height.", category = Module.Category.MOVEMENT)
public class ReverseStepModule extends Module {
    public NumberSetting height = new NumberSetting("Height", "The maximum height at which instant falling will be applied to.", 3.0, 0.0, 12.0);

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (!EUClient.SERVER_MANAGER.getSetbackTimer().hasTimeElapsed(300L)) return;
        if (mc.player.isPassenger() || mc.player.isFallFlying() || mc.player.onClimbable() || mc.player.isInLava() || mc.player.isInWater() || mc.player.input.keyPresses.jump() || mc.player.input.keyPresses.shift()) {
            return;
        }

        if (mc.player.onGround() && nearBlock(height.getValue().doubleValue())) {
            mc.player.setDeltaMovement(mc.player.getDeltaMovement().x, -height.getValue().doubleValue(), mc.player.getDeltaMovement().z);
        }
    }

    @Override
    public String getMetaData() {
        return String.valueOf(height.getValue().floatValue());
    }

    private boolean nearBlock(double height) {
        for (double i = 0; i < height + 0.5; i += 0.01) {
            if (!mc.level.noCollision(mc.player, mc.player.getBoundingBox().move(0, -i, 0))) return true;
        }

        return false;
    }
}
