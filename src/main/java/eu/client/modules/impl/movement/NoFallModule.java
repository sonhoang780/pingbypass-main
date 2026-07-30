package eu.client.modules.impl.movement;

import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PacketSendEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.mixins.accessors.PlayerMoveC2SPacketAccessor;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.ModeSetting;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.ai.attributes.Attributes;

@RegisterModule(name = "NoFall", description = "Prevents you from taking fall damage.", category = Module.Category.MOVEMENT)
public class NoFallModule extends Module {
    public ModeSetting mode = new ModeSetting("Mode", "The method that will be used in preventing fall damage.", "Packet", new String[]{"Packet", "Grim"});

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (!isFalling()) return;

        if (mode.getValue().equalsIgnoreCase("Grim")) {
            mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(mc.player.getX(), mc.player.getY() + 0.000000001, mc.player.getZ(), mc.player.getYRot(), mc.player.getXRot(), false, mc.player.horizontalCollision));
            mc.player.resetFallDistance();
        }
    }

    @SubscribeEvent
    public void onPacketSend(PacketSendEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (!mode.getValue().equalsIgnoreCase("Packet")) return;

        if (event.getPacket() instanceof ServerboundMovePlayerPacket packet && isFalling()) {
            ((PlayerMoveC2SPacketAccessor) packet).setOnGround(true);
        }
    }

    @Override
    public String getMetaData() {
        return mode.getValue();
    }

    private boolean isFalling() {
        return mc.player.fallDistance > mc.player.getAttributeValue(Attributes.SAFE_FALL_DISTANCE) && !mc.player.onGround() && !mc.player.isFallFlying();
    }
}
