package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.visuals.ChamsModule;
import eu.client.modules.impl.visuals.LogoutSpotModule;
import eu.client.modules.impl.visuals.NameTagsModule;
import eu.client.modules.impl.visuals.PopChamsModule;
import eu.client.modules.impl.visuals.ShadersModule;
import eu.client.modules.impl.visuals.NoRenderModule;
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
        if (entity instanceof net.minecraft.client.player.RemotePlayer ghost) {
            PopChamsModule popChams = EUClient.MODULE_MANAGER.getModule(PopChamsModule.class);
            if (popChams != null && popChams.isToggled() && popChams.isGhost(ghost)) {
                info.setReturnValue(null);
                return;
            }
            LogoutSpotModule logoutSpot = EUClient.MODULE_MANAGER.getModule(LogoutSpotModule.class);
            if (logoutSpot != null && logoutSpot.isToggled() && logoutSpot.isGhost(ghost)) {
                info.setReturnValue(null);
                return;
            }
        }
        if (entity instanceof Player && EUClient.MODULE_MANAGER.getModule(NameTagsModule.class).isToggled()) {
            info.setReturnValue(null);
        }
    }

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void euclient$limitItemRendering(T entity, net.minecraft.client.renderer.culling.Frustum frustum, double camX, double camY, double camZ, CallbackInfoReturnable<Boolean> cir) {
        NoRenderModule noRender = EUClient.MODULE_MANAGER != null ? EUClient.MODULE_MANAGER.getModule(NoRenderModule.class) : null;
        if (noRender != null && noRender.isToggled()) {
            if (noRender.items.getValue() && entity instanceof net.minecraft.world.entity.item.ItemEntity) {
                if (!noRender.shouldRenderItem()) {
                    cir.setReturnValue(false);
                }
            }
            if (noRender.displays.getValue() && entity instanceof net.minecraft.world.entity.Display) {
                cir.setReturnValue(false);
            }
        }
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void euclient$chams(T entity, S state, float partialTicks, CallbackInfo info) {
        IChamsCapture capture = (IChamsCapture) (Object) state;
        capture.euclient$setChams(false, 0, false, 0, false);

        PopChamsModule popChams = EUClient.MODULE_MANAGER.getModule(PopChamsModule.class);
        LogoutSpotModule logoutSpot = EUClient.MODULE_MANAGER.getModule(LogoutSpotModule.class);
        boolean isPopGhost = entity instanceof net.minecraft.client.player.RemotePlayer ghost && popChams != null && popChams.isGhost(ghost);
        boolean isLogoutGhost = entity instanceof net.minecraft.client.player.RemotePlayer ghost && logoutSpot != null && logoutSpot.isGhost(ghost);
        boolean isGhost = isPopGhost || isLogoutGhost;

        if (!isGhost) {
            ChamsModule chams = EUClient.MODULE_MANAGER.getModule(ChamsModule.class);
            if (chams.isToggled()) {
                if (entity instanceof LivingEntity livingEntity && chams.isValidEntity(livingEntity)) {
                    chams.applyEntityChams(livingEntity, capture);
                } else if (chams.crystals.getValue() && entity instanceof EndCrystal) {
                    chams.applyCrystalChams(capture);
                }
            }

            ShadersModule shaders = EUClient.MODULE_MANAGER.getModule(ShadersModule.class);
            if (shaders.isToggled() && shaders.isValidEntity(entity)) {
                state.outlineColor = shaders.getFillColor(entity);
            }
        }

        if (popChams != null && popChams.isToggled() && isPopGhost) {
            net.minecraft.client.player.RemotePlayer ghost = (net.minecraft.client.player.RemotePlayer) entity;
            capture.euclient$setChams(popChams.shouldFill(), popChams.getFillColor(ghost).getRGB(),
                    popChams.shouldOutline(), popChams.getOutlineColor(ghost).getRGB(), false, true);
        }

        if (logoutSpot != null && logoutSpot.isToggled() && isLogoutGhost) {
            net.minecraft.client.player.RemotePlayer ghost = (net.minecraft.client.player.RemotePlayer) entity;
            capture.euclient$setChams(logoutSpot.shouldFill(), logoutSpot.getFillColor(ghost).getRGB(),
                    logoutSpot.shouldOutline(), logoutSpot.getOutlineColor(ghost).getRGB(), false, true);
        }
    }
}