package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.visuals.ChamsModule;
import eu.client.modules.impl.visuals.NameTagsModule;
import eu.client.modules.impl.visuals.PopChamsModule;
import eu.client.modules.impl.visuals.ShadersModule;
import eu.client.utils.mixins.IChamsCapture;
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
        // Chams now captures real per-quad fill+outline geometry at flush time (see
        // ModelFeatureRendererMixin) instead of riding vanilla's outlineColor/entity_outline
        // post-chain -- reset every frame (an entity that stops matching, or the module getting
        // toggled off, must not keep whatever spec was set on a previous frame) then set for real
        // if it currently qualifies.
        ChamsModule chams = EUClient.MODULE_MANAGER.getModule(ChamsModule.class);
        IChamsCapture capture = (IChamsCapture) (Object) state;
        capture.euclient$setChams(false, 0, false, 0, false);
        if (chams.isToggled()) {
            if (entity instanceof LivingEntity livingEntity && chams.isValidEntity(livingEntity)) {
                chams.applyEntityChams(livingEntity, capture);
            } else if (chams.crystals.getValue() && entity instanceof EndCrystal) {
                chams.applyCrystalChams(capture);
            }
        }

        PopChamsModule popChams = EUClient.MODULE_MANAGER.getModule(PopChamsModule.class);
        if (entity instanceof Player player && popChams.isActive(player)) {
            state.outlineColor = popChams.getColor(player).getRGB() | 0xFF000000;
        }

        ShadersModule shaders = EUClient.MODULE_MANAGER.getModule(ShadersModule.class);
        if (shaders.isToggled() && shaders.isValidEntity(entity)) {
            state.outlineColor = shaders.getFillColor(entity);
        }
    }
}
