package eu.client.mixins.accessors;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerboundInteractPacket.class)
public interface PlayerInteractEntityC2SPacketAccessor {
    @Accessor("entityId")
    int getEntityId();

    @Invoker("write")
    void invokeWrite(FriendlyByteBuf buf);
}
