package eu.client.pingbypass.protocol.packets;

import eu.client.pingbypass.protocol.PbPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.inventory.ContainerInput;

/**
 * Server (proxy) -> Client: replays a container click the PROXY made on its own (a module's
 * AltSwap/AltPickup slot switch via InventoryUtils.swap() -- AutoCrystal/AutoTotem/SpeedMine's
 * proxy-side switching) onto the real client's own container, so its local prediction of item
 * contents (including enchantments/NBT) actually matches what the proxy (and by extension the
 * real server) has. Without this, only slots the PROXY's click touched get corrected by the
 * server's own broadcastChanges() diffing -- any slot the CLIENT already mispredicted on its own,
 * that the proxy's click didn't happen to also touch, is never corrected and stays wrong until
 * a full relog. Matches earthhack's real PbWindowClickService + S2CWindowClick.
 *
 * Not used for clicks the client made itself (relayed via PbPlayHandler.handleContainerClick) --
 * the client already predicted those locally when it sent them.
 *
 * Packet ID: 16
 */
public class S2CWindowClickPacket extends PbPacket {
    public static final int ID = 16;

    private final int containerId;
    private final int slotNum;
    private final int buttonNum;
    private final String containerInput;

    public S2CWindowClickPacket(int containerId, int slotNum, int buttonNum, ContainerInput containerInput) {
        this.containerId = containerId;
        this.slotNum = slotNum;
        this.buttonNum = buttonNum;
        this.containerInput = containerInput.name();
    }

    public S2CWindowClickPacket(FriendlyByteBuf buf) {
        this.containerId = buf.readVarInt();
        this.slotNum = buf.readVarInt();
        this.buttonNum = buf.readVarInt();
        this.containerInput = buf.readUtf();
    }

    @Override
    public int getPacketId() { return ID; }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(containerId);
        buf.writeVarInt(slotNum);
        buf.writeVarInt(buttonNum);
        buf.writeUtf(containerInput);
    }

    public int getContainerId() { return containerId; }
    public int getSlotNum() { return slotNum; }
    public int getButtonNum() { return buttonNum; }
    public ContainerInput getContainerInput() { return ContainerInput.valueOf(containerInput); }
}
