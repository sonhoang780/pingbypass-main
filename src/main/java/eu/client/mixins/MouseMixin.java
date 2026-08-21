package eu.client.mixins;

import eu.client.EUClient;
import eu.client.events.impl.MouseInputEvent;
import eu.client.events.impl.UnfilteredMouseInputEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MouseMixin {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "onButton", at = @At("HEAD"))
    private void onMouseButton(long window, MouseButtonInfo buttonInfo, int action, CallbackInfo info) {
        int button = buttonInfo.button();
        int mods = buttonInfo.modifiers();
        EUClient.EVENT_HANDLER.post(new UnfilteredMouseInputEvent(button, action, mods));
        if (window == minecraft.getWindow().handle() && action == 1 && minecraft.gui.screen() == null) {
            EUClient.EVENT_HANDLER.post(new MouseInputEvent(button));
        }
    }
}
