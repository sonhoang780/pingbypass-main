package eu.client.mixins;

import eu.client.EUClient;
import eu.client.events.impl.MouseInputEvent;
import eu.client.events.impl.UnfilteredMouseInputEvent;
import eu.client.events.impl.UnfilteredMouseMoveEvent;
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
    private double pbLastX;
    private double pbLastY;
    private boolean pbHasLast;

    @Inject(method = "onButton", at = @At("HEAD"))
    private void onMouseButton(long window, MouseButtonInfo buttonInfo, int action, CallbackInfo info) {
        int button = buttonInfo.button();
        int mods = buttonInfo.modifiers();
        EUClient.EVENT_HANDLER.post(new UnfilteredMouseInputEvent(button, action, mods));
        if (window == minecraft.getWindow().handle() && action == 1 && minecraft.screen == null) {
            EUClient.EVENT_HANDLER.post(new MouseInputEvent(button));
        }
    }

    @Inject(method = "onMove", at = @At("HEAD"))
    private void onMouseMove(long window, double x, double y, CallbackInfo info) {
        if (pbHasLast) {
            EUClient.EVENT_HANDLER.post(new UnfilteredMouseMoveEvent(x - pbLastX, y - pbLastY));
        }
        pbLastX = x;
        pbLastY = y;
        pbHasLast = true;
    }
}
