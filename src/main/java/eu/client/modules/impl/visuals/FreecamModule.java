package eu.client.modules.impl.visuals;

import lombok.Getter;
import lombok.Setter;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.ClientConnectEvent;
import eu.client.events.impl.KeyboardTickEvent;
import eu.client.events.impl.PlayerDeathEvent;
import eu.client.events.impl.TickEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.MovementUtils;
import eu.client.utils.system.MathUtils;
import net.minecraft.world.entity.player.Input;
import net.minecraft.client.CameraType;
import net.minecraft.util.Mth;
import org.joml.Vector2d;

@Getter @Setter
@RegisterModule(name = "Freecam", description = "Allows you to move your camera anywhere you want without restriction.", category = Module.Category.VISUALS)
public class FreecamModule extends Module {
    public NumberSetting horizontalSpeed = new NumberSetting("HorizontalSpeed", "The speed at which your camera will move horizontally.", 1.0f, 0.1f, 3.0f);
    public NumberSetting verticalSpeed = new NumberSetting("VerticalSpeed", "The speed at which your camera will move vertically.", 0.5f, 0.1f, 3.0f);
    public BooleanSetting rotate = new BooleanSetting("Rotate", "Rotates your real player model along with the camera.", false);
    public BooleanSetting shift = new BooleanSetting("Shift", "Allows moving down with shift even when a GUI is open.", false);

    private float freeYaw, freePitch;
    private float prevFreeYaw, prevFreePitch;

    private double freeX, freeY, freeZ;
    private double prevFreeX, prevFreeY, prevFreeZ;

    // Freecam's own render hook (wherever it draws the world from freeX/Y/Z) only replaces the
    // CAMERA position/rotation -- it never touches Options.cameraType, so 3rd-person stayed
    // selectable while Freecam ran and rendered the real player model instead of the free view.
    // Force 1st-person for the duration, restore whatever the user had on disable.
    private CameraType prevCameraType;

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.player == null) return;

        // Ép xác người thật đứng thẳng mỗi tick để chống lỗi lún Hitbox (Sửa lỗi "Xác vẫn Shift theo")
        mc.player.setShiftKeyDown(false);
        prevFreeYaw = freeYaw;
        prevFreePitch = freePitch;

        if (rotate.getValue()) {
        freeYaw = mc.player.getYRot();
        freePitch = mc.player.getXRot();
    }
}
    public void onMouseTurn(double cursorDeltaYaw, double cursorDeltaPitch) {
        float pitchSpeed = (float)cursorDeltaPitch * 0.15F;
        float yawSpeed = (float)cursorDeltaYaw * 0.15F;
        
        this.freePitch = Mth.clamp(this.freePitch + pitchSpeed, -90.0F, 90.0F);
        this.freeYaw += yawSpeed;
    }

    @SubscribeEvent
    public void onKeyboardTick(KeyboardTickEvent event) {
        if (mc.player == null) return;
        
        float realYaw = mc.player.getYRot(); // 1. Lưu lại hướng nhìn thật của Xác
        mc.player.setYRot(freeYaw);
        Vector2d motion = MovementUtils.forward(horizontalSpeed.getValue().doubleValue());
        mc.player.setYRot(realYaw);

        prevFreeX = freeX;
        prevFreeY = freeY;
        prevFreeZ = freeZ;

        freeX += motion.x;
        freeZ += motion.y;

        // Trả lại sự đơn giản nguyên thủy: Jump và Shift dùng chung một logic kiểm tra
        if (mc.options.keyJump.isDown()) {
            freeY += verticalSpeed.getValue().doubleValue();
        }
        
        if (mc.options.keyShift.isDown() && (shift.getValue() || mc.gui.screen() == null)) {
            freeY -= verticalSpeed.getValue().doubleValue();
        }

        // Ép mọi tín hiệu đầu vào gửi lên máy chủ thành false
        mc.player.input.keyPresses = new Input(false, false, false, false, false, false, false);
        mc.player.setShiftKeyDown(false);
        
        // Chặn Vector di chuyển
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

        prevCameraType = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON);

        freeYaw = prevFreeYaw = mc.player.getYRot();
        freePitch = prevFreePitch = mc.player.getXRot();

        freeX = prevFreeX = mc.player.getX();
        freeY = prevFreeY = mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose());
        freeZ = prevFreeZ = mc.player.getZ();
    }
    
    @SubscribeEvent
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (event.getPlayer() == null || mc.player == null) return;
        setToggled(false);
    }

    @SubscribeEvent
    public void onPlayerLogin(ClientConnectEvent event) { setToggled(false); }
    
    @Override
    public void onDisable() {
        if (mc.player == null || mc.level == null) return;

        mc.smartCull = true;

        if (prevCameraType != null) {
            mc.options.setCameraType(prevCameraType);
        }
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