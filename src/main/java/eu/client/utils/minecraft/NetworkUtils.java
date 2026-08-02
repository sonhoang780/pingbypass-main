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
        sendSequencedPacket(packetCreator, packet -> mc.getConnection().send(packet));
    }

    // Lets proxy-enhanced modules route the sequenced packet through their own serverSend()
    // (straight to the real server connection) instead of mc.getConnection() (which on the
    // proxy is the dumb-pipe path through the connected client -- an extra network hop this
    // is meant to skip).
    public static void sendSequencedPacket(SequencedPacketCreator packetCreator, java.util.function.Consumer<Packet<ServerGamePacketListener>> sender) {
        try (BlockStatePredictionHandler prediction = ((ClientWorldAccessor) mc.level).invokeGetPendingUpdateManager().startPredicting()) {
            Packet<ServerGamePacketListener> packet = packetCreator.predict(prediction.currentSequence());
            sender.accept(packet);
        }
    }
}
