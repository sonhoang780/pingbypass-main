package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.miscellaneous.FOVModifierModule;
import eu.client.modules.impl.visuals.FreecamModule;
import eu.client.modules.impl.visuals.MotionCameraModule;
import eu.client.modules.impl.visuals.NoRenderModule;
import eu.client.modules.impl.visuals.ViewClipModule;
import eu.client.utils.IMinecraft;
import net.minecraft.world.level.material.FogType;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(Camera.class)
public abstract class CameraMixin implements IMinecraft {
    @Shadow private boolean detached;
    @Shadow protected abstract void move(float distance, float y, float z);
    @Shadow protected abstract float getMaxZoom(float distance);

    // clipToSpace(F)F was folded into getMaxZoom(F)F, called from alignWithEntity (private, called by update)
    @ModifyArgs(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"))
    private void update(Args args) {
        MotionCameraModule motionCamera = EUClient.MODULE_MANAGER != null ? EUClient.MODULE_MANAGER.getModule(MotionCameraModule.class) : null;
        if (motionCamera != null && motionCamera.isToggled() && motionCamera.smoothPerspective.getValue()) {
            args.set(0, (float) motionCamera.getDistance());
            return;
        }
        if (EUClient.MODULE_MANAGER != null && EUClient.MODULE_MANAGER.getModule(ViewClipModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(ViewClipModule.class).extend.getValue()) {
            args.set(0, EUClient.MODULE_MANAGER.getModule(ViewClipModule.class).distance.getValue().floatValue());
        }
    }

    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
    private void clipToSpace(float cameraDist, CallbackInfoReturnable<Float> info) {
        if (EUClient.MODULE_MANAGER != null && EUClient.MODULE_MANAGER.getModule(ViewClipModule.class).isToggled()) {
            info.setReturnValue(cameraDist);
        }
    }

    @Inject(method = "getFluidInCamera", at = @At("HEAD"), cancellable = true)
    private void getSubmersionType(CallbackInfoReturnable<FogType> info) {
        if (EUClient.MODULE_MANAGER != null && EUClient.MODULE_MANAGER.getModule(NoRenderModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(NoRenderModule.class).liquidOverlay.getValue()) {
            info.setReturnValue(FogType.NONE);
        }
    }

    @Inject(method = "update", at = @At("TAIL"))
    private void update$TAIL(DeltaTracker deltaTracker, CallbackInfo info) {
        if (EUClient.MODULE_MANAGER == null) return;

        if (EUClient.MODULE_MANAGER.getModule(FreecamModule.class).isToggled()) {
            this.detached = true;
            return;
        }

        MotionCameraModule motionCamera = EUClient.MODULE_MANAGER.getModule(MotionCameraModule.class);
        if (motionCamera != null && motionCamera.isToggled() && motionCamera.smoothPerspective.getValue() && motionCamera.shouldBeDetached()) {
            this.detached = true;
            if (mc.options != null && mc.options.getCameraType().isFirstPerson()) {
                float dist = (float) motionCamera.getDistance();
                if (dist > 0.001F) {
                    this.move(-this.getMaxZoom(dist), 0.0F, 0.0F);
                }
            }
        }
    }

    @ModifyArgs(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"))
    private void update$setRotation(Args args) {
        if (EUClient.MODULE_MANAGER != null && EUClient.MODULE_MANAGER.getModule(FreecamModule.class).isToggled()) {
            args.setAll(EUClient.MODULE_MANAGER.getModule(FreecamModule.class).getFreeYaw(), EUClient.MODULE_MANAGER.getModule(FreecamModule.class).getFreePitch());
        }
    }

    @ModifyArgs(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V"))
    private void update$setPos(Args args) {
        if (EUClient.MODULE_MANAGER == null) return;

        if (EUClient.MODULE_MANAGER.getModule(FreecamModule.class).isToggled()) {
            args.setAll(EUClient.MODULE_MANAGER.getModule(FreecamModule.class).getFreeX(), EUClient.MODULE_MANAGER.getModule(FreecamModule.class).getFreeY(), EUClient.MODULE_MANAGER.getModule(FreecamModule.class).getFreeZ());
        } else {
            MotionCameraModule motionCamera = EUClient.MODULE_MANAGER.getModule(MotionCameraModule.class);
            if (motionCamera != null && motionCamera.isToggled() && motionCamera.on()) {
                args.setAll(motionCamera.getFakeX(), motionCamera.getFakeY(), motionCamera.getFakeZ());
            }
        }
    }

    // getFov(Camera,float,boolean) on GameRenderer was removed; the calculation now lives directly on Camera as calculateFov/calculateHudFov
    @Inject(method = "calculateFov", at = @At("TAIL"), cancellable = true)
    private void getFOV(float partialTicks, CallbackInfoReturnable<Float> info) {
        if (EUClient.MODULE_MANAGER == null) return;
        FOVModifierModule module = EUClient.MODULE_MANAGER.getModule(FOVModifierModule.class);
        if (module.isToggled()) {
            info.setReturnValue(module.fov.getValue().floatValue());
        }
    }

    @Inject(method = "calculateHudFov", at = @At("TAIL"), cancellable = true)
    private void getHudFOV(float partialTicks, CallbackInfoReturnable<Float> info) {
        if (EUClient.MODULE_MANAGER == null) return;
        FOVModifierModule module = EUClient.MODULE_MANAGER.getModule(FOVModifierModule.class);
        if (module.isToggled() && module.items.getValue()) {
            info.setReturnValue(module.fov.getValue().floatValue());
        }
    }
}
