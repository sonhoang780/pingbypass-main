package eu.client.mixins;

import eu.client.EUClient;
import eu.client.events.impl.ClientConnectEvent;
import eu.client.utils.IMinecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientPlayNetworkHandlerMixin implements IMinecraft {
    @Inject(method = "handleLogin", at = @At("TAIL"))
    private void onGameJoin(ClientboundLoginPacket packet, CallbackInfo info) {
        EUClient.EVENT_HANDLER.post(new ClientConnectEvent());
    }

    // Ported from homovore's MixinClientPlayNetworkHandler.onHandleMovePlayerPost -- a protection
    // this project never had. packetRotate (Surround/SelfTrap/AutoCrystal's "Packet" rotate mode)
    // fire-and-forgets a fake rotation to the server with no restore-to-real follow-up of its own;
    // if the player's REAL yaw genuinely isn't changing tick to tick (standing still, precisely
    // aiming -- exactly how SelfTrap/Surround are used), sendPosition's own delta==0 dedup means
    // nothing re-reports the real yaw to correct the server's belief back afterward. The server
    // then keeps believing whatever was last faked. ClientboundPlayerPositionPacket (sent by real
    // servers after knockback/damage among other things) carries the player's OWN last-known
    // rotation as part of that sync, and vanilla's handler applies it to the real
    // mc.player.setYRot()/setXRot() unconditionally -- silently snapping the actual camera to
    // whatever was last faked. Reported as "euclient forces me to look at my SelfTrap/Surround
    // target," specifically only while standing still contesting a cell AND taking damage --
    // exactly the two conditions that trigger this. Save/restore the real rotation around the
    // packet's own handling whenever a fake report is still within its window (see
    // RotationManager.isPacketRotateActive()).
    @org.spongepowered.asm.mixin.Unique
    private float handleMovePlayer$prevYaw, handleMovePlayer$prevPitch;
    @org.spongepowered.asm.mixin.Unique
    private boolean handleMovePlayer$guard;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void handleMovePlayer$HEAD(ClientboundPlayerPositionPacket packet, CallbackInfo info) {
        handleMovePlayer$guard = false;
        if (mc.player == null || EUClient.ROTATION_MANAGER == null) return;
        if (eu.client.pingbypass.PingBypassFlags.isPingBypassActive()) return;
        if (!EUClient.ROTATION_MANAGER.isPacketRotateActive()) return;

        handleMovePlayer$prevYaw = mc.player.getYRot();
        handleMovePlayer$prevPitch = mc.player.getXRot();
        handleMovePlayer$guard = true;
    }

    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void handleMovePlayer$TAIL(ClientboundPlayerPositionPacket packet, CallbackInfo info) {
        if (!handleMovePlayer$guard) return;
        handleMovePlayer$guard = false;

        mc.player.setYRot(handleMovePlayer$prevYaw);
        mc.player.setXRot(handleMovePlayer$prevPitch);
    }
}
