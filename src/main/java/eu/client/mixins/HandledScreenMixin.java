package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.miscellaneous.ShulkerInfoModule;
import eu.client.utils.IMinecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class HandledScreenMixin implements IMinecraft {
    @Shadow @Nullable protected Slot hoveredSlot;

    @Shadow protected AbstractContainerMenu menu;

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void render(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ShulkerInfoModule shulkerInfoModule = EUClient.MODULE_MANAGER.getModule(ShulkerInfoModule.class);

        if(!shulkerInfoModule.isToggled()) return;

        if(hoveredSlot != null && !hoveredSlot.getItem().isEmpty() && menu.getCarried().isEmpty() && shulkerInfoModule.hasItems(hoveredSlot.getItem())) {
            shulkerInfoModule.renderInfo(context, mouseX, mouseY, hoveredSlot.getItem());
        }
    }
}
