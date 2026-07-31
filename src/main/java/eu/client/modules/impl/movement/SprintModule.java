package eu.client.modules.impl.movement;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.mixins.accessors.ClientPlayerEntityAccessor;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.ModeSetting;
import net.minecraft.world.effect.MobEffects;

@RegisterModule(name = "Sprint", description = "Makes it so that you are always sprinting when possible.", category = Module.Category.MOVEMENT)
public class SprintModule extends Module {
    public ModeSetting mode = new ModeSetting("Mode", "The limits to when you can be sprinting.", "Rage", new String[]{"Legit", "Rage"});

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (mc.player == null) return;

        if (shouldSprint()) {
            mc.player.setSprinting(true);
        }
    }

    @Override
    public void onEnable() {
        if (mc.player == null) return;
        mc.player.setSprinting(shouldSprint());
    }

    @Override
    public void onDisable() {
        if (mc.player == null) return;
        mc.player.setSprinting(false);
    }

    @Override
    public String getMetaData() {
        return mode.getValue();
    }

    public boolean shouldSprint() {
        if (!((ClientPlayerEntityAccessor) mc.player).invokeCanSprint(true)) return false;
        if (mc.player.isInWater() && !mc.player.isUnderWater()) return false;
        if (mc.player.isSwimming() && !mc.player.onGround() && !mc.player.input.keyPresses.shift() && !mc.player.isInWater()) return false;

        if (mode.getValue().equalsIgnoreCase("Rage")) {
            return mc.player.isUnderWater() ? (mc.player.input.keyPresses.forward() || mc.player.input.keyPresses.backward() || mc.player.input.keyPresses.left() || mc.player.input.keyPresses.right()) : (mc.player.input.getMoveVector().y >= 0.8 || mc.player.input.getMoveVector().y <= -0.8 || mc.player.input.getMoveVector().x >= 0.8 || mc.player.input.getMoveVector().x <= -0.8);
        } else {
            if (!((ClientPlayerEntityAccessor) mc.player).invokeIsWalking()) return false;
            if (mc.player.isUsingItem() && (!EUClient.MODULE_MANAGER.getModule(NoSlowModule.class).isToggled() || !EUClient.MODULE_MANAGER.getModule(NoSlowModule.class).items.getValue())) return false;
            if (mc.player.hasEffect(MobEffects.BLINDNESS)) return false;
            if (mc.player.isFallFlying()) return false;
            if (mc.player.horizontalCollision && !mc.player.minorHorizontalCollision) return false;
            return mc.player.input.hasForwardImpulse();
        }
    }
}
