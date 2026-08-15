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
import org.objectweb.asm.Opcodes;

@Mixin(LocalPlayer.class)
public abstract class ClientPlayerEntityMixin extends AbstractClientPlayer {
    public ClientPlayerEntityMixin(ClientLevel world, GameProfile profile) {
        super(world, profile);
    }

    @Shadow protected abstract void updateAutoJump(float dx, float dz);

    @Shadow @Final public ClientPacketListener connection;

    @Shadow private float xRotLast;

    // Ported verbatim from NamiDevelopment/nami-public's MixinLocalPlayer
    // (sendMovementPackets1/2) -- see RotationManager.silentSyncRequired's own doc for why.
    private float originalSilentXRot;

    @Inject(method = "sendPosition", at = @At("HEAD"))
    private void silentSync$pre(CallbackInfo ci) {
        if (!EUClient.ROTATION_MANAGER.isSilentSyncRequired()) return;

        this.originalSilentXRot = this.getXRot();
        this.xRotLast -= 4;
        float f = (float) ((Math.random() * 2.0 - 1.0) * 0.001f);
        float f2 = net.minecraft.util.Mth.clamp(this.originalSilentXRot + f, -90.0F, 90.0F);
        this.setXRot(f2);
    }

    @Inject(method = "sendPosition", at = @At("RETURN"))
    private void silentSync$post(CallbackInfo ci) {
        if (!EUClient.ROTATION_MANAGER.isSilentSyncRequired()) return;

        this.setXRot(this.originalSilentXRot);
        EUClient.ROTATION_MANAGER.setSilentSyncRequired(false);
    }

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

    // PORT REGRESSION FIX. 1.21.4 wrapped the FIELD read `lastYaw` at ordinal 0 -- i.e. ONLY the
    // change-detection baseline in `getYaw() - lastYaw`, replacing it with the last yaw actually
    // PUT ON THE WIRE, so "did my look change since the server last heard about it" is measured
    // against the server's view instead of the local cache. The port instead wrapped
    // `getYRot()`/`getXRot()` in sendPosition with no ordinal, which hits ALL THREE uses: the
    // delta computation, the packet payload itself, and the `yRotLast = getYRot()` refresh. Net
    // effect while ANY rotation is queued:
    //   deltaYRot = serverYaw - yRotLast, and yRotLast was itself last set to serverYaw
    //             => delta is always 0 => `rot` is false => a Pos-only packet is sent
    //   and even when something else forced a rotation packet, its payload was serverYaw
    //             (the PREVIOUS packet's yaw), never rotation.getYaw().
    // So RotationManager's yaw swap (applied to the real mc.player for the whole
    // UpdateMovementEvent -> UpdateMovementEvent.Post window, which encloses sendPosition) was
    // correctly putting the spoofed yaw on mc.player, and this mixin then threw it away: NO silent
    // rotation from ANY module has been reaching the server since the port. Everything downstream
    // that assumes "the server now believes I'm facing X" (SprintModule's Grim/GrimStrict/RageStrict
    // yaw compensation, AutoCrystal's Rotate=Normal, KillAura, SpeedMine) was silently a no-op on
    // the wire while still altering local state -- exactly the movement/rotation desync a real
    // prediction-based anticheat sets you back for.
    // 26.1.2's sendPosition reads yRotLast/xRotLast once each (the delta) before writing them back,
    // so ordinal 0 is the read, same as 1.21.4's lastYaw.
    @WrapOperation(method = "sendPosition", at = @At(value = "FIELD", target = "Lnet/minecraft/client/player/LocalPlayer;yRotLast:F", opcode = Opcodes.GETFIELD, ordinal = 0))
    private float sendPosition$yRotLast(LocalPlayer instance, Operation<Float> original) {
        if (EUClient.ROTATION_MANAGER.getRotation() != null) return EUClient.ROTATION_MANAGER.getServerYaw();
        return original.call(instance);
    }

    @WrapOperation(method = "sendPosition", at = @At(value = "FIELD", target = "Lnet/minecraft/client/player/LocalPlayer;xRotLast:F", opcode = Opcodes.GETFIELD, ordinal = 0))
    private float sendPosition$xRotLast(LocalPlayer instance, Operation<Float> original) {
        if (EUClient.ROTATION_MANAGER.getRotation() != null) return EUClient.ROTATION_MANAGER.getServerPitch();
        return original.call(instance);
    }

    // NOTE for Sprint "Grim" (omni-sprint vs GrimAC): there is deliberately NO wire input-key fake
    // here any more. 26.1.2's LocalPlayer.tick() calls super.tick() (-> aiStep -> ClientInput.tick())
    // BEFORE it builds ServerboundPlayerInputPacket from this.input.keyPresses, so the single write
    // KeyboardInputMixin already makes at the tail of KeyboardInput.tick() is what ends up on the
    // wire -- and is the same object vanilla's physics reads. A second, independent fake at packet-
    // send time (what the previous attempt did) can only ever drift from the first one.
    // The sendPosition wire-rotation fix above is what carries Grim's faked YAW, unchanged: Grim
    // queues into ROTATION_MANAGER like every other aim module and inherits it for free.

    @ModifyExpressionValue(method = "modifyInput", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z"))
    private boolean tickMovement$isUsingItem(boolean original) {
        if (EUClient.MODULE_MANAGER.getModule(NoSlowModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(NoSlowModule.class).items.getValue() && !EUClient.MODULE_MANAGER.getModule(NoSlowModule.class).shouldSlow()) return false;
        return original;
    }

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void move(MoverType movementType, Vec3 movement, CallbackInfo info) {
        // Matches earthhack's PbMoveListener (cancels MoveEvent whenever PingBypass.isConnected(),
        // i.e. server && connected -- proxy side only): the ghost player here is teleported
        // straight from the client's own forwarded packets (PbPlayHandler.handleMovePlayer) and
        // must never run its own physics/collision on top of that -- doing so fights the teleport
        // every tick (gravity/friction applied from wherever the last teleport left off), corrupting
        // the position the proxy then reports to the real server. That's the rubberbanding/can't-jump
        // bug: previously nothing here suppressed the ghost's own move() at all, despite the stale
        // comment at the top of PbPlayHandler claiming it was suppressed.
        if (eu.client.pingbypass.PingBypassFlags.proxyForwardingActive
                && EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer()) {
            info.cancel();
            return;
        }

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
        // Same path on both sides. On the CLIENT, this sends the real player's own movement
        // packet to the proxy as always. On the PROXY, mc.player is the ghost that
        // PbPlayHandler.handleMovePlayer() keeps positioned from the client's own packets --
        // this now generates the proxy's OWN fresh movement packet from that position each
        // tick and sends it straight to the real server (mc.getConnection() on the proxy IS
        // the real server connection, see ProxyServer.setServerConnection callers), matching
        // earthhack's MotionUpdateHelper.invokeUpdateWalkingPlayer() -- rather than dumb-piping
        // the client's raw packet bytes through. This is also the hook point for proxy-side
        // aim modules: the WrapOperation getYRot/getXRot below already substitutes
        // RotationManager's queued rotation into whatever packet gets built here, for both
        // sides, so a module's rotation is applied before the send regardless of which side
        // is running it.
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
