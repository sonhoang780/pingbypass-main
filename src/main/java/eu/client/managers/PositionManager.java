package eu.client.managers;

import lombok.Getter;
import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PacketSendEvent;
import eu.client.utils.IMinecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

@Getter
public class PositionManager implements IMinecraft {
    public PositionManager() {
        EUClient.EVENT_HANDLER.subscribe(this);
    }

    private double serverX;
    private double serverY;
    private double serverZ;

    private boolean serverOnGround;

    private boolean serverSprinting;
    private boolean serverSneaking;

    private int serverSlot;

    @SubscribeEvent
    public void onPacketSend(PacketSendEvent event) {
        if (mc.player == null) return;

        if (event.getPacket() instanceof ServerboundMovePlayerPacket packet) {
            if (packet.hasPosition()) {
                serverX = packet.getX(mc.player.getX());
                serverY = packet.getY(mc.player.getY());
                serverZ = packet.getZ(mc.player.getZ());
            }

            serverOnGround = packet.isOnGround();
        }

        if (event.getPacket() instanceof ServerboundSetCarriedItemPacket packet) {
            serverSlot = packet.getSlot();
        }

        if (event.getPacket() instanceof ServerboundPlayerCommandPacket packet) {
            switch (packet.getAction()) {
                case START_SPRINTING -> serverSprinting = true;
                case STOP_SPRINTING -> serverSprinting = false;
                default -> {}
            }
        }

        if (event.getPacket() instanceof ServerboundPlayerInputPacket packet) {
            serverSneaking = packet.input().shift();
        }
    }
}
