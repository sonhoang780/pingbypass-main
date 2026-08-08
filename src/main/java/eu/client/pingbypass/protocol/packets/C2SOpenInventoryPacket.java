package eu.client.pingbypass.protocol.packets;

import eu.client.pingbypass.protocol.PbPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Client → Proxy: mirrors the real client's own InventoryScreen open/close state onto the
 * proxy's ghost. Matches earthhack's C2SOpenInventory/InventoryService exactly -- the proxy
 * literally opens/closes its own (headless, unrendered) InventoryScreen so that proxy-side
 * modules using the same "is my inventory open" checks (InventoryUtils.inInventoryScreen())
 * behave the same as they would running locally, without threading a separate flag through
 * every module that cares.
 * Packet ID: 15
 */
public class C2SOpenInventoryPacket extends PbPacket {
    public static final int ID = 15;

    private final boolean open;

    public C2SOpenInventoryPacket(boolean open) {
        this.open = open;
    }

    public C2SOpenInventoryPacket(FriendlyByteBuf buf) {
        this.open = buf.readBoolean();
    }

    @Override
    public int getPacketId() { return ID; }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(open);
    }

    public boolean isOpen() { return open; }
}
