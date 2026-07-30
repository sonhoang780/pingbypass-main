package eu.client.mixins.accessors;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Connection.class)
public interface ConnectionAccessor {
    @Accessor("channel")
    Channel getChannel();

    @Accessor("packetListener")
    void setPacketListener(PacketListener listener);

    @Accessor("sendLoginDisconnect")
    void setSendLoginDisconnect(boolean value);
}
