package eu.client.pingbypass.protocol.packets;

import eu.client.pingbypass.protocol.PbPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Server → Client: Sync a proxy module setting's value.
 * Packet ID: 6
 * Payload: moduleName (String), settingName (String), value (String)
 */
public class S2CSettingStatePacket extends PbPacket {
    public static final int ID = 6;

    private final String moduleName;
    private final String settingName;
    private final String value;

    public S2CSettingStatePacket(String moduleName, String settingName, String value) {
        this.moduleName = moduleName;
        this.settingName = settingName;
        this.value = value;
    }

    public S2CSettingStatePacket(FriendlyByteBuf buf) {
        this.moduleName = buf.readUtf();
        this.settingName = buf.readUtf();
        this.value = buf.readUtf();
    }

    @Override
    public int getPacketId() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(moduleName);
        buf.writeUtf(settingName);
        buf.writeUtf(value);
    }

    public String getModuleName() {
        return moduleName;
    }

    public String getSettingName() {
        return settingName;
    }

    public String getValue() {
        return value;
    }
}
