package eu.client.modules.impl.visuals;

import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.ModeSetting;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

@RegisterModule(name = "FullBright", description = "Gives you the ability to clearly see even when in the dark.", category = Module.Category.VISUALS)
public class FullBrightModule extends Module {
    public ModeSetting mode = new ModeSetting("Mode", "The way that will be used to change the game's brightness.", "Gamma", new String[]{"Gamma", "Potion"});

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (!mode.getValue().equalsIgnoreCase("Potion")) return;

        if (!mc.player.hasEffect(MobEffects.NIGHT_VISION)) {
            mc.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION));
        }
    }

    @Override
    public void onEnable() {
        if (mc.player == null) return;
        if (!mode.getValue().equalsIgnoreCase("Potion")) return;

        if (!mc.player.hasEffect(MobEffects.NIGHT_VISION)) {
            mc.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION));
        }
    }

    @Override
    public void onDisable() {
        if (mc.player == null) return;
        if (!mode.getValue().equalsIgnoreCase("Potion")) return;

        if (mc.player.hasEffect(MobEffects.NIGHT_VISION)) {
            mc.player.removeEffect(MobEffects.NIGHT_VISION);
        }
    }

    @Override
    public String getMetaData() {
        return mode.getValue();
    }
}
