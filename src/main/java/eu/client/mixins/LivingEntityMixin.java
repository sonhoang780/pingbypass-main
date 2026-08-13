package eu.client.mixins;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.item.component.SwingAnimation;
import eu.client.EUClient;
import eu.client.events.impl.ConsumeItemEvent;
import eu.client.events.impl.PlayerJumpEvent;
import eu.client.modules.impl.miscellaneous.EURoboticsModule;
import eu.client.modules.impl.movement.*;
import eu.client.modules.impl.player.SwingModule;
import eu.client.utils.IMinecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends Entity implements IMinecraft {
    @Shadow public abstract @Nullable AttributeInstance getAttribute(Holder<Attribute> attribute);

    @Shadow @Final private static AttributeModifier SPEED_MODIFIER_SPRINTING;

    @Shadow private int noJumpDelay;

    @Shadow protected ItemStack useItem;

    public LivingEntityMixin(EntityType<?> type, Level world) {
        super(type, world);
    }

    @WrapMethod(method = "maxUpStep")
    private float getStepHeight(Operation<Float> original) {
        if ((Object) this == mc.player && EUClient.MODULE_MANAGER != null && ((EUClient.MODULE_MANAGER.getModule(StepModule.class).isToggled() && mc.player.onGround()) || (EUClient.MODULE_MANAGER.getModule(HoleSnapModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(HoleSnapModule.class).step.getValue()) /*|| EUClient.MODULE_MANAGER.getModule(EURoboticsModule.class).shouldStep()*/)) {
            return EUClient.MODULE_MANAGER.getModule(StepModule.class).height.getValue().floatValue();
        }

        return original.call();
    }

    @Inject(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;pop()V", ordinal = 2, shift = At.Shift.BEFORE))
    private void doItemUse(CallbackInfo info) {
        if (EUClient.MODULE_MANAGER != null && EUClient.MODULE_MANAGER.getModule(NoJumpDelayModule.class).isToggled() && noJumpDelay == 10) {
            noJumpDelay = EUClient.MODULE_MANAGER.getModule(NoJumpDelayModule.class).ticks.getValue().intValue();
        }
    }

    @WrapOperation(method = "getCurrentSwingDuration", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/component/SwingAnimation;duration()I"))
    private int getHandSwingDuration(SwingAnimation instance, Operation<Integer> original) {
        int constant = original.call(instance);
        if ((Object) this != mc.player) return constant;
        return EUClient.MODULE_MANAGER.getModule(SwingModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(SwingModule.class).modifySpeed.getValue() && mc.options.getCameraType().isFirstPerson() ? (21 - EUClient.MODULE_MANAGER.getModule(SwingModule.class).speed.getValue().intValue()) : constant;
    }

    @Inject(method = "completeUsingItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;finishUsingItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/item/ItemStack;", shift = At.Shift.AFTER))
    private void consumeItem(CallbackInfo ci) {
        if((Object) this == mc.player) EUClient.EVENT_HANDLER.post(new ConsumeItemEvent(useItem));
    }

    @Inject(method = "setSprinting", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setSprinting(Z)V", shift = At.Shift.AFTER), cancellable = true)
    private void setSprinting$setSprinting(boolean sprinting, CallbackInfo info) {
        if ((Object) this == mc.player && EUClient.MODULE_MANAGER.getModule(SprintModule.class).isToggled()) {
            AttributeInstance entityAttributeInstance = getAttribute(Attributes.MOVEMENT_SPEED);
            entityAttributeInstance.removeModifier(SPEED_MODIFIER_SPRINTING.id());

            if (EUClient.MODULE_MANAGER.getModule(SprintModule.class).shouldSprint()) {
                setSharedFlag(3, true);
                entityAttributeInstance.addTransientModifier(SPEED_MODIFIER_SPRINTING);
            } else {
                setSharedFlag(3, false);
            }

            info.cancel();
        }
    }

    @Inject(method = "jumpFromGround", at = @At("HEAD"))
    private void jump$HEAD(CallbackInfo info) {
        if ((Object) this != mc.player) return;
        EUClient.EVENT_HANDLER.post(new PlayerJumpEvent());
    }

    @Inject(method = "jumpFromGround", at = @At("RETURN"))
    private void jump$RETURN(CallbackInfo info) {
        if ((Object) this != mc.player) return;
        EUClient.EVENT_HANDLER.post(new PlayerJumpEvent.Post());
    }

    // ---- Sprint "Grim" (omni-sprint vs GrimAC): local physics yaw ----------------------------
    // Ports homovore's MixinLivingEntityTravel.homovore$travelHead / homovore$travelReturn /
    // homovore$jumpYaw.
    //
    // RotationManager already swaps mc.player's yaw to the queued rotation for the duration of the
    // UpdateMovementEvent window, which is what puts the faked yaw on the wire (it encloses
    // LocalPlayer.sendPosition()). But that window opens AFTER AbstractClientPlayer.tick() has
    // already returned -- i.e. after aiStep, after jumpFromGround(), after travel(). So local
    // physics for the tick has already run against the REAL yaw by then. Left alone, the server is
    // told "facing the movement direction, holding W" while the client actually moved forward along
    // the camera yaw: a straight desync, and a setback every single tick. These two swaps close
    // that gap by running the local physics at the same yaw the packet reports.
    //
    // Scope: Sprint Grim, or RotationManager's MovementFix octant remap -- see
    // euclient$shouldSpoofRotation below. Never a bare rotation with no input compensation.
    @Unique private float euclient$grimYaw0;
    @Unique private boolean euclient$grimSwapped;

    // FastClimb's Ignore (Speed = 0): ported from example-addon-master's IgnoreClimb --
    // onClimbable() is the single real vanilla gate every climbable block (vines/ladders/
    // scaffolding, all BlockTags.CLIMBABLE) is checked against before handleOnClimbable() applies
    // any climb velocity/gravity override (verified via javap on LivingEntity, 26.1.2). Forcing it
    // false makes the block collide like normal terrain -- no climb-assist velocity, no auto-stick
    // -- instead of FastClimbModule zeroing the resulting delta post-physics (which left the
    // player frozen floating in place rather than genuinely not climbing). Only the binary
    // Ignore case uses this; partial speeds (0 < Speed < 1) still scale vanilla's own climb clamp
    // in FastClimbModule.onUpdateMovement, since onClimbable() has to stay true for that clamp to
    // exist at all.
    @Inject(method = "onClimbable", at = @At("HEAD"), cancellable = true)
    private void euclient$fastClimbIgnore(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this != mc.player) return;
        FastClimbModule fastClimb = EUClient.MODULE_MANAGER.getModule(FastClimbModule.class);
        if (fastClimb.isToggled() && fastClimb.speed.getValue().floatValue() == 0.0f) cir.setReturnValue(false);
    }

    // FastClimb, 0 < Speed < 1: scale vanilla's OWN climb clamp constants (handleOnClimbable's
    // +-0.15F horizontal/vertical bounds) instead of overwriting getDeltaMovement() after the
    // fact -- "vẫn phải speed vanilla nhưng chậm hơn, không phải set deltamovement". Every literal
    // 0.15F/-0.15F in that method scales together (both clamp bounds AND the vertical floor), so
    // the result is genuinely vanilla's own climb physics running at a smaller cap, not a
    // synthetic post-hoc multiply. 1.0 and 0.0 (Ignore, handled above) both skip this entirely.
    //
    // Only ONE @ModifyConstant, for +0.15F -- a second one targeting -0.15F crashed the whole game
    // on launch (InjectionError: "0/1 succeeded, scanned 0 targets"): javac doesn't actually emit a
    // separate -0.15F constant in this method, `Math.max(delta.y, -0.15F)` compiles to FNEG on the
    // SAME 0.15F LDC at runtime, not its own literal in the constant pool. Scaling the one real
    // constant already covers every runtime-negated use of it for free.
    @ModifyConstant(method = "handleOnClimbable", constant = @Constant(floatValue = 0.15F))
    private float euclient$fastClimbScale(float constant) {
        return euclient$fastClimbFactor(constant);
    }

    @Unique
    private float euclient$fastClimbFactor(float constant) {
        if ((Object) this != mc.player) return constant;
        FastClimbModule fastClimb = EUClient.MODULE_MANAGER.getModule(FastClimbModule.class);
        if (!fastClimb.isToggled()) return constant;
        float factor = fastClimb.speed.getValue().floatValue();
        if (factor <= 0.0f || factor >= 1.0f) return constant;
        return constant * factor;
    }

    @Inject(method = "travel", at = @At("HEAD"))
    private void travel$grimHead(Vec3 movementInput, CallbackInfo info) {
        euclient$grimSwapped = false;
        if ((Object) this != mc.player || !euclient$shouldSpoofRotation()) return;

        euclient$grimYaw0 = getYRot();
        setYRot(euclient$spoofYaw());
        euclient$grimSwapped = true;
        // Pitch is intentionally not swapped (homovore swaps it too). SprintModule queues the REAL
        // pitch, so swapping it would be a no-op, and the only travel() paths that read pitch at all
        // (elytra/swimming) are ones Grim mode already refuses to engage on.
    }

    @Inject(method = "travel", at = @At("RETURN"))
    private void travel$grimReturn(Vec3 movementInput, CallbackInfo info) {
        if (!euclient$grimSwapped) return;
        euclient$grimSwapped = false;
        setYRot(euclient$grimYaw0);
    }

    // EASY TO MISS, and was missed for a long time on the previous attempt: the sprint-jump
    // horizontal boost lives in LivingEntity.jumpFromGround(), which LocalPlayer.aiStep() calls
    // directly (26.1.2 bytecode offset 625) -- BEFORE super.aiStep() ever reaches travel(). It reads
    // getYRot() itself, completely outside the travel() swap above, and adds
    // (-sin(yaw) * 0.2, 0, cos(yaw) * 0.2) to the velocity. Without this, every sprint-jump gets its
    // boost along the camera yaw while the rest of the movement (and the packet) uses the faked yaw,
    // and jumping while moving anything other than straight forward desyncs on the spot.
    // Exactly one getYRot() call exists in the method, so no ordinal is needed.
    @ModifyExpressionValue(method = "jumpFromGround", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getYRot()F"))
    private float jumpFromGround$grimYaw(float original) {
        if ((Object) this != mc.player || !euclient$shouldSpoofRotation()) return original;
        return euclient$spoofYaw();
    }

    @Unique
    private boolean euclient$grimCompensating() {
        if (EUClient.MODULE_MANAGER == null) return false;
        SprintModule sprint = EUClient.MODULE_MANAGER.getModule(SprintModule.class);
        return sprint != null && sprint.isGrimCompensating();
    }

    // Two mutually exclusive owners of the spoofed physics yaw, never both on the same tick:
    //  - Sprint Grim, which picks the reported yaw itself so its compensation is exact (lossless);
    //  - RotationManager's MovementFix, for a rotation dictated by an aim module, whose octant remap
    //    of the input already ran this tick at the tail of KeyboardInput.tick() (LocalPlayer.aiStep
    //    calls input.tick() before jumpFromGround() and before travel(), same ordering Grim relies
    //    on). moveFixActive is false whenever that remap declined, so this never swaps the yaw
    //    without the matching input compensation.
    @Unique
    private boolean euclient$shouldSpoofRotation() {
        return euclient$grimCompensating()
                || (EUClient.ROTATION_MANAGER != null && EUClient.ROTATION_MANAGER.isMoveFixActive());
    }

    @Unique
    private float euclient$spoofYaw() {
        return euclient$grimCompensating()
                ? EUClient.MODULE_MANAGER.getModule(SprintModule.class).getGrimYaw()
                : EUClient.ROTATION_MANAGER.getMoveFixYaw();
    }
}
