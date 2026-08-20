package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.visuals.NoRenderModule;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Injected directly into ItemEntityRenderer (NOT the EntityRenderer base): ItemEntityRenderer
// overrides submit() without calling super, so a base-class injection never fires for items --
// that's why the earlier EntityRendererMixin.submit hook culled nothing. Targeting the concrete
// renderer's own submit at HEAD cancels the item's whole draw once NoRender's per-frame Limit is
// hit. No parameters declared (Mixin allows a CallbackInfo-only handler) to avoid the changed
// collector/camera-state parameter types in 26.1.2.
@Mixin(ItemEntityRenderer.class)
public class ItemEntityRendererMixin {
    @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
    private void euclient$limitItems(CallbackInfo info) {
        NoRenderModule noRender = EUClient.MODULE_MANAGER.getModule(NoRenderModule.class);
        if (noRender == null || !noRender.isToggled()) return;
        if (!noRender.shouldRenderItem()) info.cancel();
    }
}