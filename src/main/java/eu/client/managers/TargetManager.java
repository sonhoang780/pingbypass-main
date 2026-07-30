package eu.client.managers;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.ClientConnectEvent;
import eu.client.events.impl.PlayerDeathEvent;
import eu.client.events.impl.TargetDeathEvent;
import eu.client.events.impl.TickEvent;
import eu.client.modules.impl.combat.KillAuraModule;
import eu.client.modules.impl.combat.AutoCrystalModule;
import eu.client.utils.IMinecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;

public class TargetManager implements IMinecraft {
    private final ArrayList<Target> targets = new ArrayList<>();

    public TargetManager() {
        EUClient.EVENT_HANDLER.subscribe(this);
    }

    @SubscribeEvent
    public void onClientConnect(ClientConnectEvent event) {
        targets.clear();
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if(mc.player == null || mc.level == null) return;

        Player caTarget = EUClient.MODULE_MANAGER.getModule(AutoCrystalModule.class).getTarget();
        Entity kaTarget = EUClient.MODULE_MANAGER.getModule(KillAuraModule.class).target;

        synchronized (targets) {
            targets.removeIf(t -> System.currentTimeMillis() - t.time > 15000); // Remove targets if 15 seconds since last time they were targeted has passed

            if(caTarget != null) targets.add(new Target(caTarget));

            if(kaTarget instanceof Player) targets.add(new Target((Player) kaTarget));
        }
    }

    @SubscribeEvent
    public void onPlayerDeath(PlayerDeathEvent event) {
        if(mc.player == null || mc.level == null || !isTarget(event.getPlayer())) return;

        synchronized (targets) {
            EUClient.EVENT_HANDLER.post(new TargetDeathEvent(event.getPlayer()));
            targets.remove(getTarget(event.getPlayer()));
        }
    }

    private Target getTarget(Player player) {
        for(Target target : targets) {
            if(target.player == player) return target;
        }
        return null;
    }

    public boolean isTarget(Player player) {
        for(Target target : targets) {
            if(target.player == player) return true;
        }
        return false;
    }

    private class Target {
        private final Player player;
        private final long time;

        public Target(Player player) {
            this.player = player;
            this.time = System.currentTimeMillis();
        }
    }
}
