package eu.client.modules.impl.movement;

import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerMoveEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.MovementUtils;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector2d;

// Was ported from 3arthh4ck's FastSwim (me.earth.earthhack.impl.modules.movement.fastswim),
// default (non-Strafe, non-Accelerate) path -- a straight per-axis MULTIPLIER on whatever
// velocity vanilla's own travel() already left for the tick. Two real problems with that reported
// (2026-08-12), both because "multiply whatever's there" depends on vanilla's water/lava physics
// having already put something worth multiplying into that axis, which it doesn't reliably do:
//  1. V-Water/V-Lava barely affected swimming UP (holding space) -- vanilla's swim-ascend isn't a
//     growing momentum value this reads and scales, it's recomputed close to a small fixed target
//     each tick regardless of history, so multiplying it either does nothing noticeable or (worse)
//     compounds unpredictably depending on exactly when in that recompute our hook lands relative
//     to vanilla's own. Falling (gravity-driven, an actual growing momentum value) IS reliably
//     scaled by a multiply, which is why only "rising" was reported broken.
//  2. H-Water/H-Lava was asked to snap 0->max the instant a movement key is pressed and max->0 the
//     instant it's released, independent of friction/momentum -- a multiply of vanilla's own
//     (friction-decayed, ramping) horizontal velocity can never do that; it can only ever scale
//     whatever partial ramp-up vanilla itself is mid-way through.
// Fixed by not depending on vanilla's per-tick water/lava output at all -- explicit overrides
// instead, same "replace the tick's physics result outright" technique SprintModule's Instant mode
// uses.
//
// 2026-08-14: moved off UpdateMovementEvent onto PlayerMoveEvent, same fix and for the same
// reason as SprintModule's Instant mode -- UpdateMovementEvent fires AFTER
// AbstractClientPlayer.tick() has already called travel()/move() for the tick (see
// ClientPlayerEntityMixin.tick$AFTER), so a setDeltaMovement() there only takes effect on the
// FOLLOWING tick's move() call, and that call still runs full, un-intercepted vanilla water/lava
// drag -- the doc above claims "instant 0->max" but it was really a friction-smoothed ramp one
// tick late, the same "0 -> 11.71 -> 20.62 instead of a snap" shape Sprint's Instant had.
// PlayerMoveEvent fires from INSIDE travel() (ClientPlayerEntityMixin#move, which the mixin
// posts from Entity.move() itself) and lets event.setMovement()+setCancelled() replace the
// actual vector physics is about to apply THIS tick -- the real "instant" hook.
@RegisterModule(name = "FastSwim", description = "Modifies your swim speed in water and lava.", category = Module.Category.MOVEMENT)
public class FastSwimModule extends Module {
    // Vanilla's own default water/lava vertical swim speed is close to this -- v=1.0 stays roughly
    // vanilla-feeling, same as h=1.0 does for MovementUtils.DEFAULT_SPEED below.
    private static final double DEFAULT_VERTICAL_SPEED = 0.04;

    public NumberSetting hWater = new NumberSetting("H-Water", "Horizontal swim speed multiplier in water.", 1.0f, 0.1f, 20.0f);
    public NumberSetting vWater = new NumberSetting("V-Water", "Vertical (up/down) swim speed multiplier in water.", 1.0f, 0.1f, 20.0f);
    public NumberSetting hLava = new NumberSetting("H-Lava", "Horizontal swim speed multiplier in lava.", 1.0f, 0.1f, 20.0f);
    public NumberSetting vLava = new NumberSetting("V-Lava", "Vertical (up/down) swim speed multiplier in lava.", 1.0f, 0.1f, 20.0f);

    @SubscribeEvent
    public void onPlayerMove(PlayerMoveEvent event) {
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

        // Horizontal: instant 0->max on input, instant max->0 on release -- MovementUtils.forward()
        // already returns (0, 0) with no movement key held and a real yaw-rotated unit-direction
        // vector scaled to `speed` otherwise (same helper AccelerateModule/SprintModule's own
        // homing-velocity techniques use), so this is a direct override, not a decay.
        Vector2d horizontal = MovementUtils.forward(MovementUtils.DEFAULT_SPEED * h);

        // Vertical: explicit override keyed off the real jump/shift keys instead of scaling
        // whatever vanilla's swim physics left in this tick's motion (see class doc point 1) --
        // guarantees V actually does something on the way up, not just on the way down. Neither
        // key held: leave Y as vanilla's travel() computed it (not asked to change idle
        // floating/sinking).
        double y = event.getMovement().y;
        if (mc.options.keyJump.isDown()) {
            y = DEFAULT_VERTICAL_SPEED * v;
        } else if (mc.options.keyShift.isDown()) {
            y = -DEFAULT_VERTICAL_SPEED * v;
        }

        event.setMovement(new Vec3(horizontal.x, y, horizontal.y));
        event.setCancelled(true);
    }
}
