package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.visuals.ChamsModule;
import eu.client.modules.impl.visuals.NameTagsModule;
import eu.client.modules.impl.visuals.PopChamsModule;
import eu.client.modules.impl.visuals.ShadersModule;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public class EntityRendererMixin<T extends Entity, S extends EntityRenderState> {
    @Inject(method = "getNameTag", at = @At("HEAD"), cancellable = true)
    private void getDisplayName(T entity, CallbackInfoReturnable<Component> info) {
        if (entity instanceof Player && EUClient.MODULE_MANAGER.getModule(NameTagsModule.class).isToggled()) {
            info.setReturnValue(null);
        }
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void euclient$chams(T entity, S state, float partialTicks, CallbackInfo info) {
        ChamsModule chams = EUClient.MODULE_MANAGER.getModule(ChamsModule.class);
        if (chams.isToggled()) {
            if (entity instanceof LivingEntity livingEntity && chams.isValidEntity(livingEntity)) {
                state.outlineColor = chams.getEntityColor(livingEntity).getRGB();
            } else if (chams.crystals.getValue() && entity instanceof EndCrystal) {
                state.outlineColor = chams.getCrystalColor().getRGB();
            }
        }

        PopChamsModule popChams = EUClient.MODULE_MANAGER.getModule(PopChamsModule.class);
        if (entity instanceof Player player && popChams.isActive(player)) {
            state.outlineColor = popChams.getColor(player).getRGB();
        }

        ShadersModule shaders = EUClient.MODULE_MANAGER.getModule(ShadersModule.class);
        if (shaders.isToggled() && shaders.isValidEntity(entity)) {
            state.outlineColor = shaders.getColor(entity).getRGB();
        }
    }
}
