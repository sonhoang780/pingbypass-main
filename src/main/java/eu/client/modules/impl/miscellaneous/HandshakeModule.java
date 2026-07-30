package eu.client.modules.impl.miscellaneous;

import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PacketSendEvent;
import eu.client.mixins.accessors.CustomPayloadC2SPacketAccessor;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.StringSetting;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

@RegisterModule(name = "Handshake", description = "Spoofs your client handshake to make the server think that you are playing on a different client.", category = Module.Category.MISCELLANEOUS)
public class HandshakeModule extends Module {
    public StringSetting brand = new StringSetting("Brand", "The brand that the server will think you are playing on.", "vanilla");

    @SubscribeEvent
    public void onPacketSend(PacketSendEvent event) {
        if (mc.player == null || mc.level == null) return;

        if (event.getPacket() instanceof ServerboundCustomPayloadPacket packet) {
            if (!packet.payload().type().id().equals(BrandPayload.TYPE.id())) return;
            ((CustomPayloadC2SPacketAccessor) (Object) packet).setPayload(new BrandPayload(brand.getValue()));
        }
    }
}
