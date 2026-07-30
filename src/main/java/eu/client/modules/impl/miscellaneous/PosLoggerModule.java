package eu.client.modules.impl.miscellaneous;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.TickEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.WorldUtils;
import eu.client.utils.system.Timer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;

@RegisterModule(name = "PosLogger", category = Module.Category.MISCELLANEOUS)
public class PosLoggerModule extends Module {
    public NumberSetting delay = new NumberSetting("Delay", "", 1, 0, 5);
    public BooleanSetting showIDs = new BooleanSetting("ShowIDs", "", false);
    private final Timer timer = new Timer();

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if(getNull()) return;
        Player target = getTarget();

        if(target != null && timer.hasTimeElapsed(delay.getValue().intValue()*1000)) {
            EUClient.CHAT_MANAGER.message(target.getName().getString() + ", [" + WorldUtils.getMovementDirection(target.getDirection()) + "], X:" + target.getX() + ", Y:" + target.getY() + ", Z:" + target.getZ() + ", Yaw: " + target.getYRot() + ", Pitch: " + target.getXRot());
            timer.reset();
        }

        if(showIDs.getValue()) {
            for(Entity entity : mc.level.entitiesForRendering()) {
                entity.setCustomName(Component.literal(entity.getId() + ""));
                entity.setCustomNameVisible(true);
            }
        }
    }

    private Player getTarget() {
        Player target = null;
        for(Player player : mc.level.players()) {
            if(player == mc.player || player.isDeadOrDying() || mc.player.distanceTo(player) > 12) continue;

            if(target == null) {
                target = player;
                continue;
            }

            if(mc.player.distanceTo(player) < mc.player.distanceTo(target)) {
                target = player;
            }
        }
        return target;
    }
}
