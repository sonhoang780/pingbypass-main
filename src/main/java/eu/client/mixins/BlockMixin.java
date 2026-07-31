package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.movement.NoSlowModule;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Block.class)
public class BlockMixin {
    @Inject(method = "getFriction", at = @At("HEAD"), cancellable = true)
    private void getSlipperiness(CallbackInfoReturnable<Float> info) {
        if ((Object) this == Blocks.SLIME_BLOCK && EUClient.MODULE_MANAGER.getModule(NoSlowModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(NoSlowModule.class).slimeBlocks.getValue()) {
            info.setReturnValue(0.6f);
        }
    }
}
