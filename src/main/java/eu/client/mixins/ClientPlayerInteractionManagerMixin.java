package eu.client.mixins;

import eu.client.EUClient;
import eu.client.events.impl.AttackBlockEvent;
import eu.client.events.impl.AttackEntityEvent;
import eu.client.events.impl.BreakBlockEvent;
import eu.client.modules.impl.player.NoInteractModule;
import eu.client.utils.minecraft.WorldUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class ClientPlayerInteractionManagerMixin {
    @Shadow @Final private Minecraft client;

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
        if (noInteractModule.isToggled() && noInteractModule.shouldNoInteract() && noInteractModule.mode.getValue().equalsIgnoreCase("Disable") && WorldUtils.RIGHT_CLICKABLE_BLOCKS.contains(client.level.getBlockState(hitResult.getBlockPos()).getBlock())) {
            info.setReturnValue(InteractionResult.FAIL);
        }
    }

    @Inject(method = "useItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/MultiPlayerGameMode;sendSequencedPacket(Lnet/minecraft/client/world/ClientWorld;Lnet/minecraft/client/network/SequencedPacketCreator;)V"))
    private void interactBlock$BEFORE(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
        NoInteractModule noInteractModule = EUClient.MODULE_MANAGER.getModule(NoInteractModule.class);
        if (!client.player.isShiftKeyDown() && noInteractModule.isToggled() && noInteractModule.shouldNoInteract() && noInteractModule.mode.getValue().equalsIgnoreCase("Sneak") && WorldUtils.RIGHT_CLICKABLE_BLOCKS.contains(client.level.getBlockState(hitResult.getBlockPos()).getBlock())) {
            client.player.connection.send(new ServerboundPlayerInputPacket(new Input(false, false, false, false, false, true, false)));
        }
    }

    @Inject(method = "useItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/MultiPlayerGameMode;sendSequencedPacket(Lnet/minecraft/client/world/ClientWorld;Lnet/minecraft/client/network/SequencedPacketCreator;)V", shift = At.Shift.AFTER))
    private void interactBlock$AFTER(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> info) {
        NoInteractModule noInteractModule = EUClient.MODULE_MANAGER.getModule(NoInteractModule.class);
        if (!client.player.isShiftKeyDown() && noInteractModule.isToggled() && noInteractModule.shouldNoInteract() && noInteractModule.mode.getValue().equalsIgnoreCase("Sneak") && WorldUtils.RIGHT_CLICKABLE_BLOCKS.contains(client.level.getBlockState(hitResult.getBlockPos()).getBlock())) {
            client.player.connection.send(new ServerboundPlayerInputPacket(new Input(false, false, false, false, false, false, false)));
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
