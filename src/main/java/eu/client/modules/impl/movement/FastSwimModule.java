package eu.client.modules.impl.movement;

import eu.client.events.SubscribeEvent;
import eu.client.events.impl.UpdateMovementEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.NumberSetting;
import net.minecraft.world.phys.Vec3;

// Ported from 3arthh4ck's FastSwim (me.earth.earthhack.impl.modules.movement.fastswim), default
// (non-Strafe, non-Accelerate) path only -- ListenerMove.invoke()'s plain else branch:
//   if (!onGround) {
//       if (inLava)  { x *= hLava;  y *= vLava;  z *= hLava;  }
//       else if (inWater) { x *= hWater; y *= vWater; z *= hWater; }
//   }
// i.e. a straight per-axis MULTIPLIER on whatever velocity is already there for the tick -- 1.0
// on every axis is vanilla's own water/lava drag, untouched. Deliberately NOT porting Strafe/
// Accelerate/Fall (those override horizontal speed AND replace vertical with a fixed up/down/hold
// value gated on space/shift, a different feature than "modify swim speed") or NCP.passed(250)
// (earthhack's own anti-cheat-specific cooldown, not applicable here) -- just the direct up/down +
// horizontal multiplier this was asked to port. Applied the same "overwrite the tick's physics
// result" way SprintModule's Instant mode and FastClimbModule already do (UpdateMovementEvent
// fires after AbstractClientPlayer.tick(), i.e. after travel()'s own water/lava drag already ran --
// see ClientPlayerEntityMixin.tick$AFTER), since earthhack's MoveEvent is the 1.12.2 equivalent
// hook (fires post-travel, pre-send).
@RegisterModule(name = "FastSwim", description = "Modifies your swim speed in water and lava.", category = Module.Category.MOVEMENT)
public class FastSwimModule extends Module {
    public NumberSetting hWater = new NumberSetting("H-Water", "Horizontal swim speed multiplier in water.", 1.0f, 0.1f, 20.0f);
    public NumberSetting vWater = new NumberSetting("V-Water", "Vertical (up/down) swim speed multiplier in water.", 1.0f, 0.1f, 20.0f);
    public NumberSetting hLava = new NumberSetting("H-Lava", "Horizontal swim speed multiplier in lava.", 1.0f, 0.1f, 20.0f);
    public NumberSetting vLava = new NumberSetting("V-Lava", "Vertical (up/down) swim speed multiplier in lava.", 1.0f, 0.1f, 20.0f);

    @SubscribeEvent
    public void onUpdateMovement(UpdateMovementEvent event) {
        if (mc.player == null) return;
        if (mc.player.onGround()) return;

        float h, v;
        if (mc.player.isInLava()) {
            h = hLava.getValue().floatValue();
            v = vLava.getValue().floatValue();
        } else if (mc.player.isInWater()) {
            h = hWater.getValue().floatValue();
            v = vWater.getValue().floatValue();
        } else {
            return;
        }

        Vec3 delta = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(delta.x * h, delta.y * v, delta.z * h);
    }
}
