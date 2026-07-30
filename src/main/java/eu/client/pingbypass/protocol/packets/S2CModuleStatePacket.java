package eu.client.pingbypass.protocol.packets;

import eu.client.pingbypass.protocol.PbPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Server → Client: Sync a proxy module's enabled state.
 * Packet ID: 5
 * Payload: moduleName (String), enabled (Boolean)
 */
public class S2CModuleStatePacket extends PbPacket {
    public static final int ID = 5;

    private final String moduleName;
    private final boolean enabled;

    public S2CModuleStatePacket(String moduleName, boolean enabled) {
        this.moduleName = moduleName;
        this.enabled = enabled;
    }

    public S2CModuleStatePacket(FriendlyByteBuf buf) {
        this.moduleName = buf.readUtf();
        this.enabled = buf.readBoolean();
    }

    @Override
    public int getPacketId() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(moduleName);
        buf.writeBoolean(enabled);
    }

    public String getModuleName() {
        return moduleName;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
