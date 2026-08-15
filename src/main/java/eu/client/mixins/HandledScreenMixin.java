package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.miscellaneous.ShulkerInfoModule;
import eu.client.modules.impl.movement.ElytraFlyModule;
import eu.client.modules.impl.movement.InventoryControlModule;
import eu.client.utils.IMinecraft;
import eu.client.utils.minecraft.InventoryUtils;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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

    // Same swap-out lie as InGameHudMixin's hotbar redirect, for whichever container/inventory
    // screen is open (the player's own "E" tab included -- its armor slots are ordinary Slots in
    // this same menu, container number 6 for chest). extractSlot's single Slot.getItem() call feeds
    // everything the rest of the method draws, so redirecting just that read covers the icon, the
    // durability bar and the tooltip's item lookup all at once. Two independent overrides can apply
    // to the SAME open screen: slot 6 (the chest equipment slot) always shows the real elytra while
    // a swap-out is parked elsewhere -- mirrors LivingEntityMixin's getItemBySlot(CHEST) fix, for
    // the 2D icon instead of the 3D model -- and whichever slot the elytra is genuinely, temporarily
    // sitting in shows what THAT slot looked like before the swap (see ElytraFlyModule's
    // crParkedDisplaced doc). Slot.index is bản gốc/this project's own "raw container number"
    // convention (swapEquipment's own doc), so getGrimParkedSlot() (0-8 hotbar-relative) needs
    // InventoryUtils.indexToSlot() before comparing against it.
    @Redirect(method = "extractSlot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/Slot;getItem()Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack euclient$grimSwapVisual(Slot slot) {
        ItemStack real = slot.getItem();
        if (EUClient.MODULE_MANAGER == null) return real;
        ElytraFlyModule elytraFly = EUClient.MODULE_MANAGER.getModule(ElytraFlyModule.class);

        int parkedSlot = elytraFly.getGrimParkedSlot();
        if (parkedSlot == -1) return real;

        if (slot.index == 6) {
            ItemStack elytra = elytraFly.getGrimHiddenElytra();
            if (elytra != null) return elytra;
        }
        if (slot.index == InventoryUtils.indexToSlot(parkedSlot)) return elytraFly.getGrimParkedDisplaced();
        return real;
    }
}
