package eu.client.modules.impl.miscellaneous;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.WorldUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;

//@RegisterModule(name = "TableTroll", description = "Those who know", category = Module.Category.MISCELLANEOUS)
public class TableTrollModule extends Module {
    public NumberSetting limit = new NumberSetting("Limit", "The maximum number of blocks that can be placed each group.", 4, 1, 20);
    public NumberSetting delay = new NumberSetting("Delay", "The delay in ticks between each group of placements.", 0, 0, 20);

    private int ticks = 0;

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (ticks < delay.getValue().intValue()) {
            ticks++;
            return;
        }

        int bps = 0;
        for (int i = 0; i < EUClient.WORLD_MANAGER.getRadius(5); i++) {
            if (bps > limit.getValue().intValue()) break;
            BlockPos position = mc.player.blockPosition().offset(EUClient.WORLD_MANAGER.getOffset(i));

            if (!WorldUtils.isPlaceable(position)) continue;
            if (mc.level.getBlockState(position.below()).canBeReplaced() || mc.level.getBlockState(position.below()).getBlock() == Blocks.ENDER_CHEST) continue;

            WorldUtils.placeBlock(position, WorldUtils.getDirection(position, false), InteractionHand.MAIN_HAND, false, false);

            ticks = delay.getValue().intValue();
            bps++;
        }
    }
}
