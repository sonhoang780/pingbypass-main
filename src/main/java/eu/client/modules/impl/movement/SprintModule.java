package eu.client.modules.impl.movement;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.events.impl.UpdateMovementEvent;
import eu.client.mixins.accessors.ClientPlayerEntityAccessor;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.MovementUtils;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

@RegisterModule(name = "Sprint", description = "Makes it so that you are always sprinting when possible.", category = Module.Category.MOVEMENT)
public class SprintModule extends Module {
    // Instant: same technique HoleSnapModule's own homing movement uses -- set the horizontal
    // velocity directly toward the input direction every tick instead of letting vanilla's normal
    // acceleration/friction physics ramp it up over several ticks, so full speed applies
    // instantly the moment a movement key is pressed (and stops instantly on release, no slide).
    public ModeSetting mode = new ModeSetting("Mode", "The limits to when you can be sprinting.", "Rage", new String[]{"Legit", "Rage", "Grim", "Instant"});
    public NumberSetting instantSpeed = new NumberSetting("InstantSpeed", "Per-tick horizontal speed to move at (blocks/tick) when Mode is Instant.", new ModeSetting.Visibility(mode, "Instant"), (float) MovementUtils.DEFAULT_SPEED, 0.05f, 0.6f);

    // Captured here (fires before UpdateMovementEvent) instead of read live inside
    // onUpdateMovement below -- RotationManager's "Normal"/silent-rotation queue (e.g. AutoCrystal's
    // default Rotate=Normal) really does overwrite mc.player's actual getYRot() for the DURATION of
    // UpdateMovementEvent (set at MAX priority, restored at MIN priority), so a live read while
    // AutoCrystal is mid-place/attack returned the CRYSTAL's facing yaw instead of the camera's --
    // Instant then set velocity toward wherever the crystal was, not wherever WASD pointed (the
    // reported "đi theo hướng đặt crystal"). PlayerUpdateEvent runs before that window opens, so
    // the yaw here is always the real one.
    private float cachedYaw;

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (mc.player == null) return;

        cachedYaw = mc.player.getYRot();

        if (shouldSprint()) {
            mc.player.setSprinting(true);
        }
    }

    // Grim: turn the PLAYER MODEL's head AND body (yHeadRot/yBodyRot) to face the direction
    // you're actually walking, independent of where the real camera/yRot is looking -- e.g.
    // strafing left with the camera still facing forward turns the model to visibly face
    // left, without touching the real look direction anything server-side (movement,
    // rotations) reads. Both fields, not just the head -- setting only yHeadRot while
    // yBodyRot stays locked to the real look direction produces an owl-neck twist past
    // vanilla's own head/body clamp (tickHeadTurn) the instant the two diverge past ~90
    // degrees (e.g. strafing sideways), instead of the model turning to face movement
    // cleanly like a real player would.
    //
    // Was on PlayerUpdateEvent (fires BEFORE AbstractClientPlayer.tick()) -- LivingEntity.tick()
    // runs its OWN yBodyRot/tickHeadTurn logic right after (using the real XZ position delta,
    // already flips 180 for backward movement on its own), which immediately re-clamped whatever
    // we'd just forced back toward ITS target, fighting our value every tick -- the reported
    // "spins forward then back" jitter. UpdateMovementEvent fires AFTER tick(), so this now has
    // the last word for the frame instead of getting overwritten a moment later.
    @SubscribeEvent
    public void onUpdateMovement(UpdateMovementEvent event) {
        if (mc.player == null) return;

        if (mode.getValue().equalsIgnoreCase("Grim") && mc.player.isSprinting()) {
            float forward = mc.player.input.getMoveVector().y;
            float sideways = mc.player.input.getMoveVector().x;
            if (forward != 0.0f || sideways != 0.0f) {
                float moveYaw = cachedYaw + (float) Math.toDegrees(Math.atan2(-sideways, forward));
                mc.player.yHeadRot = moveYaw;
                mc.player.yHeadRotO = moveYaw;
                mc.player.yBodyRot = moveYaw;
                mc.player.yBodyRotO = moveYaw;
            }
        }

        if (mode.getValue().equalsIgnoreCase("Instant")) {
            Vec2 move = mc.player.input.getMoveVector();
            Vec3 v = mc.player.getDeltaMovement();

            if (move.x != 0.0f || move.y != 0.0f) {
                // Inverse of the strafe/forward <- world-delta rotation (yaw rotates world->input
                // via [xxa;zza] = [[cos,sin],[-sin,cos]]*[dx;dz], same formula HoleSnap's Strict
                // mode uses the other direction) -- input->world is the transpose of that rotation.
                double yawRad = Math.toRadians(cachedYaw);
                double sin = Math.sin(yawRad), cos = Math.cos(yawRad);
                double vx = move.x * cos - move.y * sin;
                double vz = move.x * sin + move.y * cos;
                double speed = instantSpeed.getValue().doubleValue();

                mc.player.setDeltaMovement(vx * speed, v.y, vz * speed);
            } else {
                // No movement key held -- this only ever force-set horizontal velocity to full
                // speed above, never back down, so releasing keys left vanilla's normal ground
                // friction to decay it over several ticks (0.2 -> 24.29km/h instantly on press,
                // but a gradual slide back to 0 on release instead of stopping the same way).
                // Zero it outright, same instant-snap treatment, every tick no key is held.
                mc.player.setDeltaMovement(0.0, v.y, 0.0);
            }
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

        // Grim's whole point is sprinting in every direction while faking the model's facing to
        // look legit (the yHeadRot/yBodyRot block in onPlayerUpdate) -- it needs the SAME
        // omnidirectional trigger as Rage, not Legit's forward-key-only gate below. Falling into
        // the Legit branch meant hasForwardImpulse() (true only while W is held) gated sprinting
        // entirely: walking backward or strafing alone never set isSprinting() true, so neither
        // the speed boost nor the model-rotation block (guarded by isSprinting()) ever ran.
        if (mode.getValue().equalsIgnoreCase("Rage") || mode.getValue().equalsIgnoreCase("Grim") || mode.getValue().equalsIgnoreCase("Instant")) {
            // KeyboardInput.tick() builds moveVector as new Vec2(strafe, forward).normalized() --
            // keyboard input is always an exact unit vector (or zero), never a partial analog
            // value, so pure diagonal movement (e.g. W+D) normalizes to (~0.707, ~0.707) on BOTH
            // axes. A per-axis >=0.8 threshold (meant to filter weak analog-stick input) rejects
            // that as "not really moving", failing to trigger sprint at all for diagonal-only
            // movement -- the reported "can't reach ~20.2km/h moving diagonally" bug. Check the
            // raw key presses directly instead -- makes the water/non-water split above dead code
            // (both used the same condition once this got fixed), collapsed into one return.
            return mc.player.input.keyPresses.forward() || mc.player.input.keyPresses.backward() || mc.player.input.keyPresses.left() || mc.player.input.keyPresses.right();
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
