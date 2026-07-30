package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.movement.FastWebModule;
import eu.client.utils.IMinecraft;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WebBlock.class)
public class CobwebBlockMixin implements IMinecraft {
    @Inject(method = "onEntityCollision", at = @At("HEAD"), cancellable = true)
    private void onEntityCollision(BlockState state, Level world, BlockPos pos, Entity entity, CallbackInfo info) {
        if (EUClient.MODULE_MANAGER.getModule(FastWebModule.class).isToggled()) {
            if (EUClient.MODULE_MANAGER.getModule(FastWebModule.class).sneak.getValue() && !mc.player.isShiftKeyDown()) return;

            if (EUClient.MODULE_MANAGER.getModule(FastWebModule.class).mode.getValue().equalsIgnoreCase("Ignore")) {
                entity.resetFallDistance();
                info.cancel();
            }

            if (EUClient.MODULE_MANAGER.getModule(FastWebModule.class).mode.getValue().equalsIgnoreCase("Strong")) {
                entity.makeStuckInBlock(state, new Vec3(EUClient.MODULE_MANAGER.getModule(FastWebModule.class).horizontal.getValue().doubleValue(), EUClient.MODULE_MANAGER.getModule(FastWebModule.class).vertical.getValue().doubleValue(), EUClient.MODULE_MANAGER.getModule(FastWebModule.class).horizontal.getValue().doubleValue()));
                info.cancel();
            }
        }
    }
}
