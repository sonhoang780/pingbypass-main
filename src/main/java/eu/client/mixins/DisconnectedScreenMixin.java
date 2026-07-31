package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.miscellaneous.AutoReconnectModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DisconnectedScreen.class)
public class DisconnectedScreenMixin extends Screen {
    @Shadow @Final private LinearLayout layout;

    @Unique private Button toggleButton;
    @Unique private Button button;
    @Unique private double time = 100.0;

    protected DisconnectedScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/layouts/LinearLayout;arrangeElements()V", shift = At.Shift.BEFORE))
    private void init(CallbackInfo info) {
        if (EUClient.SERVER_MANAGER.getLastConnection() != null) {
            button = new Button.Builder(Component.literal(getText()), button -> tryConnecting()).width(200).build();
            toggleButton = new Button.Builder(Component.literal("Toggle " + (EUClient.MODULE_MANAGER.getModule(AutoReconnectModule.class).isToggled() ? ChatFormatting.GREEN : ChatFormatting.RED) + "AutoReconnect"), button -> {
                EUClient.MODULE_MANAGER.getModule(AutoReconnectModule.class).setToggled(!EUClient.MODULE_MANAGER.getModule(AutoReconnectModule.class).isToggled(), false);

                this.toggleButton.setMessage(Component.literal("Toggle " + (EUClient.MODULE_MANAGER.getModule(AutoReconnectModule.class).isToggled() ? ChatFormatting.GREEN : ChatFormatting.RED) + "AutoReconnect"));
                this.button.setMessage(Component.literal(getText()));

                time = EUClient.MODULE_MANAGER.getModule(AutoReconnectModule.class).delay.getValue().intValue() * 20;
            }).width(200).build();

            layout.addChild(button);
            layout.addChild(toggleButton);
        }
    }

    @Override
    public void tick() {
        if (!EUClient.MODULE_MANAGER.getModule(AutoReconnectModule.class).isToggled() || EUClient.SERVER_MANAGER.getLastConnection() == null) return;

        if (time <= 0) {
            tryConnecting();
        } else {
            time--;
            if (button != null) button.setMessage(Component.literal(getText()));
        }
    }

    @Unique
    private String getText() {
        String reconnectText = "Reconnect";
        if (EUClient.MODULE_MANAGER.getModule(AutoReconnectModule.class).isToggled()) reconnectText += " " + String.format("(" + ChatFormatting.GREEN + "%.1fs" + ChatFormatting.RESET + ")", time / 20);

        return reconnectText;
    }

    @Unique
    private void tryConnecting() {
        ConnectScreen.startConnecting(new TitleScreen(), Minecraft.getInstance(), EUClient.SERVER_MANAGER.getLastConnection().left(), EUClient.SERVER_MANAGER.getLastConnection().right(), false, new net.minecraft.client.multiplayer.TransferState(java.util.Map.of(), java.util.Map.of(), false));
    }
}
