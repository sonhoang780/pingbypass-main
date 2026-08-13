package eu.client.modules.impl.visuals;

import lombok.Getter;
import lombok.Setter;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.KeyboardTickEvent;
import eu.client.events.impl.TickEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.MovementUtils;
import eu.client.utils.system.MathUtils;
import net.minecraft.world.entity.player.Input;
import org.joml.Vector2d;

@Getter @Setter
@RegisterModule(name = "Freecam", description = "Allows you to move your camera anywhere you want without restriction.", category = Module.Category.VISUALS)
public class FreecamModule extends Module {
    public NumberSetting horizontalSpeed = new NumberSetting("HorizontalSpeed", "The speed at which your camera will move horizontally.", 1.0f, 0.1f, 3.0f);
    public NumberSetting verticalSpeed = new NumberSetting("VerticalSpeed", "The speed at which your camera will move vertically.", 0.5f, 0.1f, 3.0f);

    private float freeYaw, freePitch;
    private float prevFreeYaw, prevFreePitch;

    private double freeX, freeY, freeZ;
    private double prevFreeX, prevFreeY, prevFreeZ;

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.player == null) return;

        prevFreeYaw = freeYaw;
        prevFreePitch = freePitch;

        freeYaw = mc.player.getYRot();
        freePitch = mc.player.getXRot();
    }

    @SubscribeEvent
    public void onKeyboardTick(KeyboardTickEvent event) {
        if (mc.player == null) return;

        Vector2d motion = MovementUtils.forward(horizontalSpeed.getValue().doubleValue());

        prevFreeX = freeX;
        prevFreeY = freeY;
        prevFreeZ = freeZ;

        freeX += motion.x;
        freeZ += motion.y;

        if (mc.options.keyJump.isDown()) freeY += verticalSpeed.getValue().doubleValue();
        if (mc.options.keyShift.isDown()) freeY -= verticalSpeed.getValue().doubleValue();

        // Was forward()/backward()/left()/right() passed through unchanged, only jump/shift/sprint
        // zeroed -- only stopped the FLOAT moveVector (zeroed below via the event), never these
        // DISCRETE bits. Anything reading them directly instead of moveVector still saw real WASD:
        // SprintModule's Grim shouldSprint() (mc.player.input.keyPresses.forward()/backward()/
        // left()/right(), unconditional of moveVector) forces isSprinting(true) off real input, and
        // LivingEntity.jumpFromGround()'s sprint-jump boost is itself unconditional of moveVector
        // (adds real velocity along the current/swapped yaw purely from isSprinting()+yaw) -- so
        // Sprint Grim could still genuinely translate the REAL hidden player while Freecam was
        // supposed to have fully suppressed input. Zero every bit, matching the intent already
        // documented below (event.setMovementForward/Sideways(0)).
        mc.player.input.keyPresses = new Input(false, false, false, false, false, false, false);

        // Was a direct field write (ClientInputAccessor.setMoveVector(ZERO)) -- that only lasts
        // until KeyboardInputMixin's own post-listener step, which unconditionally rebuilds
        // moveVector from event.getMovementForward()/Sideways() whenever event.isCancelled() is
        // true, REGARDLESS of what any earlier listener already wrote directly to the field. Any
        // OTHER active silent-rotation (RotationManager's movementFix, e.g. KillAura mid-aim, or
        // Sprint RageStrict) does exactly that -- reads the event's forward/sideways (still the REAL
        // WASD values at that point, since the event snapshots them before any listener runs) and
        // re-cancels with a yaw-corrected nonzero vector, silently overwriting our direct zero right
        // back to real, moving both the hidden real player (this bug) AND dragging the free camera
        // toward wherever that other module is aiming (the related KillAura+Freecam report) --
        // same root cause, one fix. Go through the event's own API instead: canceling with
        // forward=sideways=0 survives any later movementFix remap regardless of listener order,
        // since rotating a zero vector by any yaw delta is still zero.
        event.setMovementForward(0.0f);
        event.setMovementSideways(0.0f);
        event.setCancelled(true);
    }

    @Override
    public void onEnable() {
        if (mc.player == null || mc.level == null) {
            setToggled(false);
            return;
        }

        mc.smartCull = false;

        freeYaw = prevFreeYaw = mc.player.getYRot();
        freePitch = prevFreePitch = mc.player.getXRot();

        freeX = prevFreeX = mc.player.getX();
        freeY = prevFreeY = mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose());
        freeZ = prevFreeZ = mc.player.getZ();
    }

    @Override
    public void onDisable() {
        if (mc.player == null || mc.level == null) return;

        mc.smartCull = true;
    }

    public float getFreeYaw() {
        return (float) MathUtils.interpolate(prevFreeYaw, freeYaw, mc.getDeltaTracker().getGameTimeDeltaPartialTick(true));
    }

    public float getFreePitch() {
        return (float) MathUtils.interpolate(prevFreePitch, freePitch, mc.getDeltaTracker().getGameTimeDeltaPartialTick(true));
    }

    public double getFreeX() {
        return MathUtils.interpolate(prevFreeX, freeX, mc.getDeltaTracker().getGameTimeDeltaPartialTick(true));
    }

    public double getFreeY() {
        return MathUtils.interpolate(prevFreeY, freeY, mc.getDeltaTracker().getGameTimeDeltaPartialTick(true));
    }

    public double getFreeZ() {
        return MathUtils.interpolate(prevFreeZ, freeZ, mc.getDeltaTracker().getGameTimeDeltaPartialTick(true));
    }
}
