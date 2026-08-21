package eu.client.mixins;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import eu.client.EUClient;
import eu.client.modules.impl.core.HUDModule;
import eu.client.modules.impl.movement.ElytraFlyModule;
import eu.client.modules.impl.visuals.NoRenderModule;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// PORT (26.2): moved out of InGameHudMixin (@Mixin(Gui.class)) -- found via a targeted audit after
// ItemInHandRenderer.renderHandsWithItems's own rename crashed at runtime (MixinApplyError, not
// caught by compileJava since method-name strings aren't type-checked). Cross-checked every
// mixin's method-name string against real 26.2 source: extractEffects/extractPortalOverlay/
// extractVignette/extractCameraOverlays/extractTextureOverlay/extractItemHotbar all moved from
// Gui to a new Hud sub-object (Gui.hud, same object that already held getChat()/isHidden()/
// getGuiTicks() found earlier this session) -- confirmed via real net/minecraft/client/gui/Hud.java.
// InGameHudMixin keeps extractRenderState/setScreen, both still genuinely on Gui itself.
@Mixin(Hud.class)
public class HudMixin {
    private static final Identifier POWDER_SNOW_OUTLINE_LOCATION = Identifier.withDefaultNamespace("textures/misc/powder_snow_outline.png");

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
    @WrapWithCondition(method = "extractCameraOverlays", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Hud;extractTextureOverlay(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/resources/Identifier;F)V"))
    private boolean renderTextureOverlay(Hud instance, GuiGraphicsExtractor context, Identifier texture, float alpha) {
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
