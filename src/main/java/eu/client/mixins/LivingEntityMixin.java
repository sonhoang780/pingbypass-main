package eu.client.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
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

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends Entity implements IMinecraft {
    @Shadow public abstract @Nullable AttributeInstance getAttributeInstance(Holder<Attribute> attribute);

    @Shadow @Final private static AttributeModifier SPRINTING_SPEED_BOOST;

    @Shadow private int jumpingCooldown;

    @Shadow protected ItemStack activeItemStack;

    public LivingEntityMixin(EntityType<?> type, Level world) {
        super(type, world);
    }

    @WrapMethod(method = "getStepHeight")
    private float getStepHeight(Operation<Float> original) {
        if ((Object) this == mc.player && EUClient.MODULE_MANAGER != null && ((EUClient.MODULE_MANAGER.getModule(StepModule.class).isToggled() && mc.player.onGround()) || (EUClient.MODULE_MANAGER.getModule(HoleSnapModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(HoleSnapModule.class).step.getValue()) /*|| EUClient.MODULE_MANAGER.getModule(EURoboticsModule.class).shouldStep()*/)) {
            return EUClient.MODULE_MANAGER.getModule(StepModule.class).height.getValue().floatValue();
        }

        return original.call();
    }

    @Inject(method = "tickMovement", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiler/Profiler;pop()V", ordinal = 2, shift = At.Shift.BEFORE))
    private void doItemUse(CallbackInfo info) {
        if (EUClient.MODULE_MANAGER != null && EUClient.MODULE_MANAGER.getModule(NoJumpDelayModule.class).isToggled() && jumpingCooldown == 10) {
            jumpingCooldown = EUClient.MODULE_MANAGER.getModule(NoJumpDelayModule.class).ticks.getValue().intValue();
        }
    }

    @ModifyConstant(method = "getHandSwingDuration", constant = @Constant(intValue = 6))
    private int getHandSwingDuration(int constant) {
        if ((Object) this != mc.player) return constant;
        return EUClient.MODULE_MANAGER.getModule(SwingModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(SwingModule.class).modifySpeed.getValue() && mc.options.getCameraType().isFirstPerson() ? (21 - EUClient.MODULE_MANAGER.getModule(SwingModule.class).speed.getValue().intValue()) : constant;
    }

    @Inject(method = "consumeItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/item/ItemStack;finishUsing(Lnet/minecraft/world/Level;Lnet/minecraft/entity/LivingEntity;)Lnet/minecraft/item/ItemStack;", shift = At.Shift.AFTER))
    private void consumeItem(CallbackInfo ci) {
        if((Object) this == mc.player) EUClient.EVENT_HANDLER.post(new ConsumeItemEvent(activeItemStack));
    }

    @Inject(method = "setSprinting", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;setSprinting(Z)V", shift = At.Shift.AFTER), cancellable = true)
    private void setSprinting$setSprinting(boolean sprinting, CallbackInfo info) {
        if ((Object) this == mc.player && EUClient.MODULE_MANAGER.getModule(SprintModule.class).isToggled()) {
            AttributeInstance entityAttributeInstance = getAttributeInstance(Attributes.MOVEMENT_SPEED);
            entityAttributeInstance.removeModifier(SPRINTING_SPEED_BOOST.id());

            if (EUClient.MODULE_MANAGER.getModule(SprintModule.class).shouldSprint()) {
                setSharedFlag(3, true);
                entityAttributeInstance.addTransientModifier(SPRINTING_SPEED_BOOST);
            } else {
                setSharedFlag(3, false);
            }

            info.cancel();
        }
    }

    @Inject(method = "jump", at = @At("HEAD"))
    private void jump$HEAD(CallbackInfo info) {
        if ((Object) this != mc.player) return;
        EUClient.EVENT_HANDLER.post(new PlayerJumpEvent());
    }

    @Inject(method = "jump", at = @At("RETURN"))
    private void jump$RETURN(CallbackInfo info) {
        if ((Object) this != mc.player) return;
        EUClient.EVENT_HANDLER.post(new PlayerJumpEvent.Post());
    }
}
