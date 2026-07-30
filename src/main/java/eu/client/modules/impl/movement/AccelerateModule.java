package eu.client.modules.impl.movement;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerMoveEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.utils.minecraft.MovementUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector2d;

@RegisterModule(name = "Accelerate", description = "Gives you more precise movement instantly.", category = Module.Category.MOVEMENT)
public class AccelerateModule extends Module {
    public BooleanSetting air = new BooleanSetting("Air", "Increases your speed while off ground.", true);
    public BooleanSetting speedInWater = new BooleanSetting("SpeedInWater", "Increases your speed while in water.", false);

    @SubscribeEvent
    public void onPlayerMove(PlayerMoveEvent event) {
        if(getNull() || (EUClient.MODULE_MANAGER.getModule(HoleSnapModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(HoleSnapModule.class).hole != null) || EUClient.MODULE_MANAGER.getModule(SpeedModule.class).isToggled()) return;

        if (mc.player.fallDistance >= 5.0f || mc.player.isShiftKeyDown() || mc.player.onClimbable() || mc.level.getBlockState(mc.player.blockPosition()).getBlock() == Blocks.COBWEB || mc.player.getAbilities().flying || mc.player.isFallFlying())
            return;

        if(!mc.player.onGround() && !air.getValue()) return;

        if((mc.player.isInWater() || mc.player.isInLava()) && !speedInWater.getValue()) return;

        Vector2d velocity = MovementUtils.forward(MovementUtils.getPotionSpeed(MovementUtils.DEFAULT_SPEED));
        event.setMovement(new Vec3(velocity.x, event.getMovement().y, event.getMovement().z));
        event.setMovement(new Vec3(event.getMovement().x, event.getMovement().y, velocity.y));
        event.setCancelled(true);
    }
}
