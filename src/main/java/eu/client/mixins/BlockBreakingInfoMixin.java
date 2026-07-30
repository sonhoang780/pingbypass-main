package eu.client.mixins;

import eu.client.EUClient;
import eu.client.events.impl.PlayerMineEvent;
import net.minecraft.server.level.BlockDestructionProgress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockDestructionProgress.class)
public class BlockBreakingInfoMixin {
    @Inject(method = "compareTo(Lnet/minecraft/server/level/BlockDestructionProgress;)I", at = @At("HEAD"))
    private void compareTo(BlockDestructionProgress blockBreakingInfo, CallbackInfoReturnable<Integer> cir) {
        EUClient.EVENT_HANDLER.post(new PlayerMineEvent(blockBreakingInfo.getId(), blockBreakingInfo.getPos()));
    }
}
