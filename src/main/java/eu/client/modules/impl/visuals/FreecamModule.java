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

        mc.player.input.keyPresses = new Input(mc.player.input.keyPresses.forward(), mc.player.input.keyPresses.backward(), mc.player.input.keyPresses.left(), mc.player.input.keyPresses.right(), false, false, false);
        ((eu.client.mixins.accessors.ClientInputAccessor) mc.player.input).setMoveVector(net.minecraft.world.phys.Vec2.ZERO);
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
