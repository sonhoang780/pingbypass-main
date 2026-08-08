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
        //
        // No priority/exclusivity between these -- explicitly requested (2026-08-08): Shaders and
        // Chams are meant to render SIMULTANEOUSLY on the same entity (Shaders' glass/prism shader
        // pattern layered with Chams' own wireframe outline), not have one suppress the other. They
        // don't actually compete for storage anyway: Chams writes into the real fill+outline
        // capture below (IChamsCapture), Shaders writes into vanilla's own separate single-slot
        // outlineColor field -- different fields, safe to both be active.
        //
        // PopChams no longer touches this at all for the LIVE player -- it spawns its own separate
        // ghost entity (frozen at the popper's pose, see PopChamsModule) that flows through this
        // SAME mixin naturally as its own distinct entity/capture, so it never has to fight Chams
        // over the one live-entity slot either.
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
        if (popChams.isToggled() && entity instanceof net.minecraft.client.player.RemotePlayer ghost && popChams.isGhost(ghost)) {
            capture.euclient$setChams(popChams.shouldFill(), popChams.getFillColor(ghost).getRGB(),
                    popChams.shouldOutline(), popChams.getOutlineColor(ghost).getRGB(), false);
        }

        ShadersModule shaders = EUClient.MODULE_MANAGER.getModule(ShadersModule.class);
        if (shaders.isToggled() && shaders.isValidEntity(entity)) {
            state.outlineColor = shaders.getFillColor(entity);
        }
    }
}
