package eu.client.mixins;

import eu.client.gui.PeekScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
    // .peek opens a client-side, read-only container. AbstractContainerScreen forwards every click
    // through handleContainerInput, which sends a ServerboundContainerClickPacket -> the SERVER
    // moves your REAL inventory (its containerMenu is not our client-side peek menu). Block it while
    // a PeekScreen is open so nothing is ever sent or moved.
    @Inject(method = "handleContainerInput", at = @At("HEAD"), cancellable = true)
    private void euclient$blockPeekClicks(int containerId, int slotId, int button, ContainerInput type, Player player, CallbackInfo ci) {
        if (Minecraft.getInstance().screen instanceof PeekScreen) ci.cancel();
    }
}