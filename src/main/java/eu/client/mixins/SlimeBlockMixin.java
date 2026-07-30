package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.movement.NoSlowModule;
import eu.client.utils.IMinecraft;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SlimeBlock.class)
public class SlimeBlockMixin implements IMinecraft {
    @Inject(method = "onSteppedOn", at = @At("HEAD"), cancellable = true)
    private void onSteppedOn(Level world, BlockPos pos, BlockState state, Entity entity, CallbackInfo info) {
        if (entity == mc.player && EUClient.MODULE_MANAGER.getModule(NoSlowModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(NoSlowModule.class).slimeBlocks.getValue()) {
            info.cancel();
        }
    }
}
