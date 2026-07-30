package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.visuals.NoRenderModule;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HumanoidArmorLayer.class)
public abstract class ArmorFeatureRendererMixin<S extends HumanoidRenderState, M extends HumanoidModel<S>, A extends HumanoidModel<S>> {
    @Inject(method = "render(Lnet/minecraft/client/util/math/PoseStack;Lnet/minecraft/client/render/MultiBufferSource;ILnet/minecraft/client/render/entity/state/HumanoidRenderState;FF)V", at = @At("HEAD"), cancellable = true)
    private void renderArmor(PoseStack matrixStack, MultiBufferSource vertexConsumerProvider, int i, S bipedEntityRenderState, float f, float g, CallbackInfo info) {
        NoRenderModule module = EUClient.MODULE_MANAGER.getModule(NoRenderModule.class);
        if (module.isToggled() && module.armor.getValue()) {
            info.cancel();
        }
    }
}
