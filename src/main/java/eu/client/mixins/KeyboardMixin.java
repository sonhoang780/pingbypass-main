package eu.client.mixins;

import eu.client.EUClient;
import eu.client.events.impl.KeyInputEvent;
import eu.client.events.impl.UnfilteredKeyInputEvent;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class KeyboardMixin {
    @Shadow @Final private Minecraft client;

    @Inject(method = "keyPress", at = @At("HEAD"))
    private void keyPress(long handle, int action, KeyEvent event, CallbackInfo info) {
        EUClient.EVENT_HANDLER.post(new UnfilteredKeyInputEvent(event.key(), event.scancode(), action, event.modifiers()));
        if (handle == client.getWindow().handle() && action == 1 && client.screen == null) {
            EUClient.EVENT_HANDLER.post(new KeyInputEvent(event.key(), event.modifiers()));
        }
    }
}
