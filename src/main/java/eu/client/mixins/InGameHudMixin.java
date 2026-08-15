package eu.client.mixins;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import eu.client.EUClient;
import eu.client.events.impl.RenderOverlayEvent;
import eu.client.modules.impl.core.HUDModule;
import eu.client.modules.impl.movement.ElytraFlyModule;
import eu.client.modules.impl.visuals.NoRenderModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class InGameHudMixin {
    @Shadow @Final private Minecraft minecraft;

    private static final Identifier POWDER_SNOW_OUTLINE_LOCATION = Identifier.withDefaultNamespace("textures/misc/powder_snow_outline.png");

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void render(GuiGraphicsExtractor context, DeltaTracker tickCounter, CallbackInfo info) {
        if (minecraft.options.hideGui) return;
        EUClient.EVENT_HANDLER.post(new RenderOverlayEvent(context, tickCounter.getGameTimeDeltaPartialTick(true)));
    }

    @Inject(method = "extractEffects", at = @At("HEAD"), cancellable = true)
    private void renderStatusEffectOverlay(GuiGraphicsExtractor context, DeltaTracker tickCounter, CallbackInfo info) {
        if (EUClient.MODULE_MANAGER != null && EUClient.MODULE_MANAGER.getModule(HUDModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(HUDModule.class).vanillaPotions.getValue().equalsIgnoreCase("Hide")) {
            info.cancel();
        }
    }

    @Inject(method = "extractPortalOverlay", at = @At("HEAD"), cancellable = true)
    private void renderPortalOverlay(GuiGraphicsExtractor context, float portalIntensity, CallbackInfo info) {
        if (EUClient.MODULE_MANAGER.getModule(NoRenderModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(NoRenderModule.class).portalOverlay.getValue()) {
            info.cancel();
        }
    }

    @Inject(method = "extractVignette", at = @At("HEAD"), cancellable = true)
    private void renderVignetteOverlay(GuiGraphicsExtractor context, Entity entity, CallbackInfo info) {
        if (EUClient.MODULE_MANAGER.getModule(NoRenderModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(NoRenderModule.class).vignette.getValue()) {
            info.cancel();
        }
    }

    /**
     * Pumpkin overlay is data-driven now (Equippable.cameraOverlay component), no dedicated
     * call site to hook -- it flows through the same extractTextureOverlay(texture, alpha) as
     * the powder-snow freeze overlay, so distinguish by the resolved texture path.
     */
    @WrapWithCondition(method = "extractCameraOverlays", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;extractTextureOverlay(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/resources/Identifier;F)V"))
    private boolean renderTextureOverlay(Gui instance, GuiGraphicsExtractor context, Identifier texture, float alpha) {
        if (texture.equals(POWDER_SNOW_OUTLINE_LOCATION)) {
            return !(EUClient.MODULE_MANAGER.getModule(NoRenderModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(NoRenderModule.class).snowOverlay.getValue());
        }
        if (texture.getPath().contains("pumpkin")) {
            return !(EUClient.MODULE_MANAGER.getModule(NoRenderModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(NoRenderModule.class).pumpkinOverlay.getValue());
        }
        return true;
    }

    // ElytraFly ControlRocket + GrimV3 genuinely swaps a hotbar item (real clicks, see
    // ElytraFlyModule.grimSwapCycle's 2026-08-15 note) to park the elytra out of the chest slot for
    // one tick per cycle. extractItemHotbar reads Inventory.getItem(i) fresh every FRAME (no
    // interpolation) for each of the 9 hotbar slots, so whichever real hotbar slot the cycle uses
    // shows the elytra for that one real tick -- perceived as the reported "elytra dao động giữa
    // chestplate và elytra ở hotbar". Redirect just this one read: while a swap-out is in flight and
    // this loop's index is the parked slot, hand back what that slot looked like BEFORE the swap
    // instead of its real (temporarily different) content. Container state itself is untouched --
    // this only lies to the render call, same principle as LivingEntityMixin's getItemBySlot(CHEST)
    // fix for the 3rd-person model, just for the hotbar icon instead.
    @Redirect(method = "extractItemHotbar", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Inventory;getItem(I)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack euclient$grimHotbarVisual(Inventory inventory, int slot) {
        ItemStack real = inventory.getItem(slot);
        if (EUClient.MODULE_MANAGER == null) return real;
        ElytraFlyModule elytraFly = EUClient.MODULE_MANAGER.getModule(ElytraFlyModule.class);
        if (elytraFly.getGrimParkedSlot() == slot) return elytraFly.getGrimParkedDisplaced();
        return real;
    }
}
