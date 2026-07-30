package eu.client.mixins;

import eu.client.utils.mixins.IChatHudLineVisible;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(GuiMessage.Line.class)
public class ChatHudLineVisibleMixin implements IChatHudLineVisible {
    @Unique private boolean clientMessage = false;
    @Unique private String clientIdentifier = "";

    @Override
    public boolean euclient$isClientMessage() {
        return clientMessage;
    }

    @Override
    public void euclient$setClientMessage(boolean clientMessage) {
        this.clientMessage = clientMessage;
    }

    @Override
    public String euclient$getClientIdentifier() {
        return clientIdentifier;
    }

    @Override
    public void euclient$setClientIdentifier(String clientIdentifier) {
        this.clientIdentifier = clientIdentifier;
    }
}