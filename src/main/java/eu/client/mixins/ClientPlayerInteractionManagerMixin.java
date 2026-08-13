package eu.client.mixins;

import eu.client.EUClient;
import eu.client.events.impl.AttackBlockEvent;
import eu.client.events.impl.AttackEntityEvent;
import eu.client.events.impl.BreakBlockEvent;
import eu.client.modules.impl.movement.InventoryControlModule;
import eu.client.modules.impl.player.NoInteractModule;
import eu.client.utils.minecraft.WorldUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class ClientPlayerInteractionManagerMixin {
    @Shadow @Final private Minecraft minecraft;

    // GrimV2 (InventoryControl) -- see that setting's own doc for the two Grim triggers this
    // defeats. Container clicks are never re-entrant on this thread, so a single instance field
    // is safe (no click can start while another is mid-flight).
    @Unique private boolean euclient$stoppedSprintForClick = false;

    @Inject(method = "handleContainerInput", at = @At("HEAD"))
    private void beforeContainerClick(int containerId, int slotNum, int buttonNum, ContainerInput containerInput, Player player, CallbackInfo info) {
        euclient$stoppedSprintForClick = false;
        InventoryControlModule module = EUClient.MODULE_MANAGER.getModule(InventoryControlModule.class);
        if (!module.isToggled() || !module.grimV2.getValue()) return;
        if (minecraft.player == null || minecraft.getConnection() == null) return;

        // Sends one input packet with every movement bit zeroed, only if the real state has any
        // set -- matches what a genuinely stationary client would send this tick.
        Input real = minecraft.player.input.keyPresses;
        if (real.forward() || real.backward() || real.left() || real.right() || real.jump()) {
            Input fake = new Input(false, false, false, false, false, real.shift(), real.sprint());
            minecraft.getConnection().send(new ServerboundPlayerInputPacket(fake));
            ((eu.client.mixins.accessors.ClientPlayerEntityAccessor) minecraft.player).setLastSentInput(fake);
        }

        if (minecraft.player.isSprinting()) {
            minecraft.getConnection().send(new ServerboundPlayerCommandPacket(minecraft.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
            euclient$stoppedSprintForClick = true;
        }
    }

    @Inject(method = "handleContainerInput", at = @At("TAIL"))
    private void afterContainerClick(int containerId, int slotNum, int buttonNum, ContainerInput containerInput, Player player, CallbackInfo info) {
        if (!euclient$stoppedSprintForClick) return;
        euclient$stoppedSprintForClick = false;
        if (minecraft.player == null || minecraft.getConnection() == null) return;
        minecraft.getConnection().send(new ServerboundPlayerCommandPacket(minecraft.player, ServerboundPlayerCommandPacket.Action.START_SPRINTING));
    }

    @Inject(method = "ensureHasSentCarriedItem", at = @At("HEAD"), cancellable = true)
    private void syncSelectedSlot(CallbackInfo info) {
        // On the proxy, don't let interactionManager send its own slot sync packets.
        // The client's UpdateSelectedSlotC2SPacket is forwarded directly by PbPlayHandler.
        if (eu.client.pingbypass.PingBypassFlags.proxyForwardingActive
                && EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer()) {
            info.cancel();
        }
    }

    @Inject(method = "releaseUsingItem", at = @At("HEAD"), cancellable = true)
    private void stopUsingItem(CallbackInfo info) {
        // On the proxy, don't let interactionManager send RELEASE_USE_ITEM.
        // The client handles its own item use lifecycle (eating, bows, etc.)
        // and sends its own release packet when done.
        if (eu.client.pingbypass.PingBypassFlags.proxyForwardingActive
                && EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer()) {
            info.cancel();
        }
    }

    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void attackBlock(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> info) {
        AttackBlockEvent event = new AttackBlockEvent(pos, direction);
        EUClient.EVENT_HANDLER.post(event);
        if (event.isCancelled()) {
            info.setReturnValue(false);
        }
    }

    @Inject(method = "useItemOn", at = @At(value = "HEAD"), cancellable = true)
    private void interactBlock(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> info) {
        NoInteractModule noInteractModule = EUClient.MODULE_MANAGER.getModule(NoInteractModule.class);
        if (noInteractModule.isToggled() && noInteractModule.shouldNoInteract() && noInteractModule.mode.getValue().equalsIgnoreCase("Disable") && WorldUtils.RIGHT_CLICKABLE_BLOCKS.contains(minecraft.level.getBlockState(hitResult.getBlockPos()).getBlock())) {
            info.setReturnValue(InteractionResult.FAIL);
        }
    }

    @Inject(method = "useItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;startPrediction(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/multiplayer/prediction/PredictiveAction;)V"))
    private void interactBlock$BEFORE(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
        NoInteractModule noInteractModule = EUClient.MODULE_MANAGER.getModule(NoInteractModule.class);
        if (!minecraft.player.isShiftKeyDown() && noInteractModule.isToggled() && noInteractModule.shouldNoInteract() && noInteractModule.mode.getValue().equalsIgnoreCase("Sneak") && WorldUtils.RIGHT_CLICKABLE_BLOCKS.contains(minecraft.level.getBlockState(hitResult.getBlockPos()).getBlock())) {
            minecraft.player.connection.send(new ServerboundPlayerInputPacket(new Input(false, false, false, false, false, true, false)));
        }
    }

    @Inject(method = "useItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;startPrediction(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/multiplayer/prediction/PredictiveAction;)V", shift = At.Shift.AFTER))
    private void interactBlock$AFTER(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> info) {
        NoInteractModule noInteractModule = EUClient.MODULE_MANAGER.getModule(NoInteractModule.class);
        if (!minecraft.player.isShiftKeyDown() && noInteractModule.isToggled() && noInteractModule.shouldNoInteract() && noInteractModule.mode.getValue().equalsIgnoreCase("Sneak") && WorldUtils.RIGHT_CLICKABLE_BLOCKS.contains(minecraft.level.getBlockState(hitResult.getBlockPos()).getBlock())) {
            minecraft.player.connection.send(new ServerboundPlayerInputPacket(new Input(false, false, false, false, false, false, false)));
        }
    }

    @Inject(method = "attack", at = @At("HEAD"))
    private void attackEntity(Player player, Entity target, CallbackInfo ci) {
        EUClient.EVENT_HANDLER.post(new AttackEntityEvent(player, target));
    }

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void breakBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        EUClient.EVENT_HANDLER.post(new BreakBlockEvent(pos));
    }
}
