package eu.client.pingbypass.protocol.packets;

import eu.client.pingbypass.protocol.PbPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Server → Client: mirrors a proxy-side "Normal" hotbar switch onto the real
 * client's own hotbar display. "Normal" mode changes the proxy ghost player's
 * selected slot (which is what the real server needs), but the real client's
 * own hotbar is drawn from its own local selection and never gets told about
 * that change otherwise -- so visually nothing appears to switch.
 * Packet ID: 13
 */
public class S2CSlotSyncPacket extends PbPacket {
    public static final int ID = 13;

    private final int slot;

    public S2CSlotSyncPacket(int slot) {
        this.slot = slot;
    }

    public S2CSlotSyncPacket(FriendlyByteBuf buf) {
        this.slot = buf.readVarInt();
    }

    @Override
    public int getPacketId() { return ID; }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(slot);
    }

    public int getSlot() { return slot; }
}
