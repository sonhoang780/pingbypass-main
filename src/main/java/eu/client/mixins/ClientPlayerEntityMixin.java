package eu.client.mixins;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.authlib.GameProfile;
import eu.client.EUClient;
import eu.client.events.impl.*;
import eu.client.modules.impl.movement.InventoryControlModule;
import eu.client.modules.impl.movement.NoSlowModule;
import eu.client.modules.impl.movement.VelocityModule;
import eu.client.modules.impl.player.NoEntityTraceModule;
import eu.client.modules.impl.player.SwingModule;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.MoverType;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class ClientPlayerEntityMixin extends AbstractClientPlayer {
    public ClientPlayerEntityMixin(ClientLevel world, GameProfile profile) {
        super(world, profile);
    }

    @Shadow protected abstract void updateAutoJump(float dx, float dz);

    @Shadow @Final public ClientPacketListener connection;

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;tick()V", shift = At.Shift.BEFORE))
    private void tick$BEFORE(CallbackInfo info) {
        EUClient.EVENT_HANDLER.post(new PlayerUpdateEvent());
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;tick()V", shift = At.Shift.AFTER))
    private void tick$AFTER(CallbackInfo info) {
        EUClient.EVENT_HANDLER.post(new UpdateMovementEvent());
    }

    @Inject(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/client/player/LocalPlayer;ambientSoundHandlers:Ljava/util/List;", shift = At.Shift.BEFORE))
    private void tick$tickables(CallbackInfo ci) {
        EUClient.EVENT_HANDLER.post(new UpdateMovementEvent.Post());
    }

    // PORT: sendMovementPackets was inlined into tick()/sendPosition() -- the passenger-rotation
    // send path in tick() itself is left alone (not covered by the old feature either); this wraps
    // the on-foot movement-send path (sendPosition), which reads getYRot/getXRot both to build the
    // outgoing packet and to refresh the yRotLast/xRotLast change-detection cache.
    @WrapOperation(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getYRot()F"))
    private float sendPosition$getYRot(LocalPlayer instance, Operation<Float> original) {
        if (EUClient.ROTATION_MANAGER.getRotation() != null) return EUClient.ROTATION_MANAGER.getServerYaw();
        return original.call(instance);
    }

    @WrapOperation(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getXRot()F"))
    private float sendPosition$getXRot(LocalPlayer instance, Operation<Float> original) {
        if (EUClient.ROTATION_MANAGER.getRotation() != null) return EUClient.ROTATION_MANAGER.getServerPitch();
        return original.call(instance);
    }

    @ModifyExpressionValue(method = "modifyInput", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z"))
    private boolean tickMovement$isUsingItem(boolean original) {
        if (EUClient.MODULE_MANAGER.getModule(NoSlowModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(NoSlowModule.class).items.getValue() && !EUClient.MODULE_MANAGER.getModule(NoSlowModule.class).shouldSlow()) return false;
        return original;
    }

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void move(MoverType movementType, Vec3 movement, CallbackInfo info) {
        PlayerMoveEvent event = new PlayerMoveEvent(movementType, movement);
        EUClient.EVENT_HANDLER.post(event);

        if (event.isCancelled()) {
            info.cancel();

            double prevX = getX();
            double prevZ = getZ();

            super.move(movementType, event.getMovement());
            updateAutoJump((float) (getX() - prevX), (float) (getZ() - prevZ));
        }
    }

    @Inject(method = "sendPosition", at = @At("HEAD"), cancellable = true)
    private void sendMovementPackets(CallbackInfo info) {
        // When proxy forwarding is active on the SERVER (proxy), cancel sendMovementPackets.
        // The client's movement packets are forwarded by PbPlayHandler.onPlayerMove.
        // Without this cancel, the proxy would send its OWN movement to the server
        // (conflicting with the forwarded client packets).
        if (eu.client.pingbypass.PingBypassFlags.proxyForwardingActive
                && EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer()) {
            info.cancel();
            return;
        }
        // On the CLIENT side: do NOT cancel. The client sends movement packets
        // to the proxy normally, and the proxy forwards them to the real server.
        SendMovementEvent event = new SendMovementEvent();
        EUClient.EVENT_HANDLER.post(event);
        if (event.isCancelled()) {
            info.cancel();
        }
    }

    @ModifyExpressionValue(method = "pick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/ProjectileUtil;getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;"))
    private static @Nullable EntityHitResult pick$getEntityHitResult(@Nullable EntityHitResult original) {
        NoEntityTraceModule module = EUClient.MODULE_MANAGER.getModule(NoEntityTraceModule.class);
        if (module.isToggled() && module.shouldIgnore()) {
            return null;
        }

        return original;
    }

    @ModifyExpressionValue(method = "canStartSprinting", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isSlowDueToUsingItem()Z"))
    private boolean canStartSprinting$isUsingItem(boolean original) {
        NoSlowModule module = EUClient.MODULE_MANAGER.getModule(NoSlowModule.class);
        if (EUClient.MODULE_MANAGER != null && module.isToggled() && module.items.getValue()) {
            return false;
        }

        return original;
    }

    @Inject(method = "moveTowardsClosestSpace", at = @At("HEAD"), cancellable = true)
    private void pushOutOfBlocks(double x, double z, CallbackInfo info) {
        if (EUClient.MODULE_MANAGER.getModule(VelocityModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(VelocityModule.class).antiBlockPush.getValue()) {
            info.cancel();
        }
    }

    @Inject(method = "swing", at = @At("HEAD"), cancellable = true)
    private void swingHand(InteractionHand hand, CallbackInfo info) {
        if (EUClient.MODULE_MANAGER.getModule(SwingModule.class).isToggled()) {
            if (!EUClient.MODULE_MANAGER.getModule(SwingModule.class).hand.getValue().equals("None")) {
                switch (EUClient.MODULE_MANAGER.getModule(SwingModule.class).hand.getValue()) {
                    case "Default" -> super.swing(hand);
                    case "Mainhand" -> super.swing(InteractionHand.MAIN_HAND);
                    case "Offhand" -> super.swing(InteractionHand.OFF_HAND);
                    case "Both" -> {
                        super.swing(InteractionHand.MAIN_HAND);
                        super.swing(InteractionHand.OFF_HAND);
                    }
                }

                if (EUClient.MODULE_MANAGER.getModule(SwingModule.class).hand.getValue().equalsIgnoreCase("Packet") || !EUClient.MODULE_MANAGER.getModule(SwingModule.class).noPacket.getValue()) {
                    connection.send(new ServerboundSwingPacket(hand));
                }
            }

            info.cancel()   ;
        }
    }

    @Inject(method = "handlePortalTransitionEffect", at = @At("HEAD"), cancellable = true)
    private void tickNausea(boolean fromPortalEffect, CallbackInfo info) {
        if (EUClient.MODULE_MANAGER.getModule(InventoryControlModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(InventoryControlModule.class).portals.getValue()) {
            info.cancel();
        }
    }

    @Inject(method = "startUsingItem", at = @At(value = "HEAD"))
    private void setCurrentHand(InteractionHand hand, CallbackInfo info) {
        EUClient.EVENT_HANDLER.post(new ChangeHandEvent());
    }
}
