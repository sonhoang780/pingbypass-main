package eu.client.modules.impl.movement;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.TickEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.EntityUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.ChatFormatting;

@RegisterModule(name = "TickShift", description = "Manipulates minecraft timer to give you a small speed boost.", category = Module.Category.MOVEMENT)
public class TickShiftModule extends Module {
    public NumberSetting maxTicks = new NumberSetting("MaxTicks", "The maximum amount of ticks that the boost will be charging for.", 1, 1, 40);
    public NumberSetting delay = new NumberSetting("Delay", "The delay between each tick.", 4, 1, 10);
    public NumberSetting speed = new NumberSetting("Speed", "The speed of charging each tick.", 2.0f, 1.0f, 10.0f);

    int ticks = 0;
    int wait = 0;

    @Override
    public void onEnable() {
        if (getNull()) return;
        reset();
    }

    @Override
    public void onDisable() {
        if (getNull()) return;
        reset();
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (getNull()) return;

        if (mc.player.fallDistance >= 5.0f)
            return;

        // speed<=5 keeps charging (instead of spending) through the few ramp-up ticks right after
        // pressing a movement key -- vanilla acceleration takes a moment to exceed 5km/h, so this
        // avoids wasting charge on a tick that barely moved. Only meaningful for modes that
        // actually HAVE a ramp, though: Sprint's Instant mode sets velocity straight to its target
        // speed the same tick a key is pressed (no ramp at all, see SprintModule), so that window
        // never exists for it -- keeping the speed<=5 clause for Instant just meant it never got
        // ANY extra charge at sprint-start and combo'd as if TickShift wasn't running (reported:
        // "behave y hệt sprint instant bình thường"). Skip the speed clause for Instant specifically
        // (standing-still is still required); keep it for every other mode, unchanged.
        ElytraFlyModule elytra = EUClient.MODULE_MANAGER.getModule(ElytraFlyModule.class);
        if (elytra.isToggled() && elytra.mode.getValue().equalsIgnoreCase("Control") && mc.player.isFallFlying()) return;
        boolean instant = EUClient.MODULE_MANAGER.getModule(SprintModule.class).mode.getValue().equalsIgnoreCase("Instant");
        boolean charging = mc.player.xxa == 0.0f && mc.player.zza == 0.0f && mc.player.fallDistance == 0.0f;
        if (!instant) charging |= EntityUtils.getSpeed(mc.player, EntityUtils.SpeedUnit.KILOMETERS) <= 5;

        if (charging) {
            EUClient.WORLD_MANAGER.setTimerMultiplier(1.0f);
            if(wait >= delay.getValue().intValue()) {
                if(ticks < maxTicks.getValue().intValue()) {
                    ticks++;
                }
                wait = 0;
            }
            wait++;
        } else {
            if(ticks > 0) {
                if (!EUClient.MODULE_MANAGER.getModule(SpeedModule.class).isDrivingTimer() && !mc.options.keyJump.isDown()) EUClient.WORLD_MANAGER.setTimerMultiplier(speed.getValue().floatValue());
                ticks--;
            } else {
                reset();
            }
        }
    }

    public void reset() {
        EUClient.WORLD_MANAGER.setTimerMultiplier(1.0f);
        ticks = 0;
        wait = 0;
    }

    @Override
    public String getMetaData() {
        return (ticks >= maxTicks.getValue().intValue() ? ChatFormatting.GREEN + "" : "") + ticks + ChatFormatting.RESET;
    }
}
