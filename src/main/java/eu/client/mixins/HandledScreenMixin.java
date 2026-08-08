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

    // Was hooking extractRenderState's TAIL -- works for a plain AbstractContainerScreen (chests,
    // ender chest...) but AbstractRecipeBookScreen (InventoryScreen, i.e. the player's own "E"
    // inventory, crafting table...) OVERRIDES extractRenderState ENTIRELY and never calls
    // super.extractRenderState() -- it calls extractContents/extractCarriedItem/extractTooltip
    // directly itself instead, so the mixin-injected code inside AbstractContainerScreen's own
    // extractRenderState body never ran for those screens (the reported "container gui thì hiện,
    // mở inventory thuần thì không"). extractTooltip is the one call BOTH class hierarchies
    // actually share, so hook that instead -- also lets the vanilla-tooltip suppression and our
    // own draw live in one place instead of two separate injections.
    //
    // Vanilla's own item-name tooltip (queued via setTooltipForNextFrame) renders on a dedicated
    // always-on-top stratum regardless of insertion order -- it painted directly over our box
    // below (both anchor near the mouse, and ShulkerInfo's box is tall enough to overlap where the
    // vanilla tooltip lands). Our box already includes the item's name, so cancel vanilla's
    // redundant one for the same slot instead of fighting the stratum ordering.
    @Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true)
    private void euclient$shulkerInfo(GuiGraphicsExtractor context, int mouseX, int mouseY, CallbackInfo ci) {
        ShulkerInfoModule shulkerInfoModule = EUClient.MODULE_MANAGER.getModule(ShulkerInfoModule.class);
        if (!shulkerInfoModule.isToggled()) return;

        if (hoveredSlot != null && !hoveredSlot.getItem().isEmpty() && menu.getCarried().isEmpty() && shulkerInfoModule.hasItems(hoveredSlot.getItem())) {
            shulkerInfoModule.renderInfo(context, mouseX, mouseY, hoveredSlot.getItem());
            ci.cancel();
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
