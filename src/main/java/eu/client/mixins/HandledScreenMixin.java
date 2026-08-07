package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.miscellaneous.ShulkerInfoModule;
import eu.client.modules.impl.movement.InventoryControlModule;
import eu.client.utils.IMinecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class HandledScreenMixin extends Screen implements IMinecraft {
    @Shadow @Nullable protected Slot hoveredSlot;

    @Shadow protected AbstractContainerMenu menu;

    @Shadow protected abstract void slotClicked(Slot slot, int slotId, int mouseButton, ContainerInput type);

    // Only re-triggers on an ACTUAL slot change while dragging -- without this, mouseDragged
    // fires every frame the mouse moves even a pixel over the same slot, spamming slotClicked.
    private Slot euclient$lastDragSlot = null;

    protected HandledScreenMixin(net.minecraft.network.chat.Component title) {
        super(title);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void render(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ShulkerInfoModule shulkerInfoModule = EUClient.MODULE_MANAGER.getModule(ShulkerInfoModule.class);

        if(!shulkerInfoModule.isToggled()) return;

        if(hoveredSlot != null && !hoveredSlot.getItem().isEmpty() && menu.getCarried().isEmpty() && shulkerInfoModule.hasItems(hoveredSlot.getItem())) {
            shulkerInfoModule.renderInfo(context, mouseX, mouseY, hoveredSlot.getItem());
        }
    }

    // DragClick: hold shift + left-click and drag across slots to shift-click (quick-move) every
    // item the cursor passes over, instead of clicking each slot individually. Vanilla's own
    // mouseDragged only distributes a HELD stack across slots (plain drag, no shift) -- shift+drag
    // does nothing in vanilla, so hijacking it here doesn't fight any existing behavior.
    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void euclient$dragClick(MouseButtonEvent event, double dragX, double dragY, CallbackInfoReturnable<Boolean> info) {
        InventoryControlModule module = EUClient.MODULE_MANAGER.getModule(InventoryControlModule.class);
        if (!module.isToggled() || !module.dragClick.getValue()) return;
        boolean shiftDown = com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow(), org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT)
                || com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow(), org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT);
        if (event.button() != 0 || !shiftDown) return;
        if (hoveredSlot == null || hoveredSlot.getItem().isEmpty() || hoveredSlot == euclient$lastDragSlot) return;

        euclient$lastDragSlot = hoveredSlot;
        slotClicked(hoveredSlot, hoveredSlot.index, 0, ContainerInput.QUICK_MOVE);

        info.setReturnValue(true);
        info.cancel();
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"))
    private void euclient$dragClickReset(MouseButtonEvent event, CallbackInfoReturnable<Boolean> info) {
        euclient$lastDragSlot = null;
    }
}
