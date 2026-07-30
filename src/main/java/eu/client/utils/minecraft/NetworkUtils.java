package eu.client.utils.minecraft;

import eu.client.mixins.accessors.ClientWorldAccessor;
import eu.client.utils.IMinecraft;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerGamePacketListener;

public class NetworkUtils implements IMinecraft {
    public static void sendIgnoredPacket(Packet<?> packet) {
        mc.getConnection().getConnection().send(packet, null, true);
    }

    public interface SequencedPacketCreator {
        Packet<ServerGamePacketListener> predict(int sequence);
    }

    public static void sendSequencedPacket(SequencedPacketCreator packetCreator) {
        try (BlockStatePredictionHandler prediction = ((ClientWorldAccessor) mc.level).invokeGetPendingUpdateManager().startPredicting()) {
            Packet<ServerGamePacketListener> packet = packetCreator.predict(prediction.currentSequence());
            mc.getConnection().send(packet);
        }
    }
}
