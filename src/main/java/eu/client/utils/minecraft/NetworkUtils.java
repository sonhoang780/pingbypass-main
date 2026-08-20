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

    // 2026-08-19 ROOT CAUSE of "NoSlow still rubberbands when eating+sprinting on a 1.20.x server
    // through ViaFabricPlus". This method ALWAYS returned false, so NoSlowModule's whole legacy
    // slot-swap-desync branch (and the !isLegacyProtocol() guard in its Strict branch) was dead code
    // on every connection -- which is exactly why only the Via path was ever broken: on native
    // 1.21.2+ the legacy branch is supposed to be skipped anyway.
    //   * The primary path looked up "de.florianreuth.viafabricplus.api.ViaFabricPlusAPI#getAPI".
    //     No such class or method exists in any ViaFabricPlus release. The real entrypoint is
    //     ViaFabricPlus.getImpl() -> ViaFabricPlusBase#getTargetVersion() -> ProtocolVersion
    //     (verified against viafabricplus-api 4.5.5 sources). ClassNotFoundException, every call.
    //   * The fallback then cast ViaAPI#getServerVersion() to int. It returns a ServerProtocolVersion
    //     object (viaversion-common 5.10.0, ViaAPI.java:77), so that path died on ClassCastException
    //     -- and both throwables were swallowed by the catch, silently returning false forever.
    // Dropped the ViaVersion fallback rather than repairing it: on a Fabric client, ViaFabricPlus IS
    // how ViaVersion is present, so it was never a reachable second case.
    private static Object vfpImpl;
    private static java.lang.reflect.Method vfpTargetVersion, protocolVersionGetVersion;

    public static boolean isLegacyProtocol() {
        try {
            if (vfpImpl == null) {
                vfpImpl = Class.forName("com.viaversion.viafabricplus.ViaFabricPlus").getMethod("getImpl").invoke(null);
                vfpTargetVersion = Class.forName("com.viaversion.viafabricplus.api.ViaFabricPlusBase").getMethod("getTargetVersion");
                protocolVersionGetVersion = Class.forName("com.viaversion.viaversion.api.protocol.version.ProtocolVersion").getMethod("getVersion");
            }
            Object targetVersion = vfpTargetVersion.invoke(vfpImpl);
            return targetVersion != null && (int) protocolVersionGetVersion.invoke(targetVersion) < 768;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
