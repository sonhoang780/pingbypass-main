package eu.client.mixins;

import eu.client.EUClient;
import eu.client.events.impl.ServerConnectEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {
    @Inject(method = "startConnecting", at = @At("HEAD"))
    private static void connect(Screen parent, Minecraft minecraft, ServerAddress address, ServerData data, boolean isQuickPlay, @Nullable TransferState transferState, CallbackInfo info) {
        EUClient.EVENT_HANDLER.post(new ServerConnectEvent(address, data));
    }
}
