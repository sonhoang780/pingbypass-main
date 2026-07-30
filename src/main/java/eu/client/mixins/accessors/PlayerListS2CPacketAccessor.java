package eu.client.mixins.accessors;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.EnumSet;
import java.util.List;

@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public interface PlayerListS2CPacketAccessor {
    @Accessor("actions")
    @Mutable
    void setActions(EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions);

    @Accessor("entries")
    @Mutable
    void setEntries(List<ClientboundPlayerInfoUpdatePacket.Entry> entries);
}
