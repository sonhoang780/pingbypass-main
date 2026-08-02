package eu.client.pingbypass.protocol.packets;

import eu.client.pingbypass.protocol.PbPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Client -> Server: full replace of the friends list.
 * Packet ID: 14
 *
 * FriendManager's list is loaded from a local config file and never had any sync path to the
 * proxy at all -- the proxy runs as a separate JVM on the VPS with no such file, so
 * FRIEND_MANAGER.contains(...) always evaluated false there. Every proxy-side target scan
 * (AutoCrystal.getPlayers(), KillAura) that checks it for "don't attack this player" therefore
 * never actually excluded anyone: friends got crystalled/killed the same as any other player.
 */
public class C2SFriendSyncPacket extends PbPacket {
    public static final int ID = 14;

    private final List<String> friends;

    public C2SFriendSyncPacket(List<String> friends) {
        this.friends = friends;
    }

    public C2SFriendSyncPacket(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        friends = new ArrayList<>(count);
        for (int i = 0; i < count; i++) friends.add(buf.readUtf());
    }

    @Override
    public int getPacketId() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(friends.size());
        for (String friend : friends) buf.writeUtf(friend);
    }

    public List<String> getFriends() {
        return friends;
    }
}
