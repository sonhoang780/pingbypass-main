package eu.client.modules.impl.combat;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.NetworkUtils;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;

@RegisterModule(name = "AutoBowRelease", description = "Automatically releases your bow after a certain amount of time has passed.", category = Module.Category.COMBAT)
public class AutoBowReleaseModule extends Module {
    public NumberSetting ticks = new NumberSetting("Ticks", "The number of ticks that have to be waited for before releasing the bow.", 3, 0, 20);

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (EUClient.MODULE_MANAGER.getModule(SelfBowModule.class).isToggled()) return;
        if ((mc.player.getOffhandItem().getItem() == Items.BOW || mc.player.getMainHandItem().getItem() == Items.BOW) && mc.player.isUsingItem()) {
            if (mc.player.getTicksUsingItem() >= ticks.getValue().intValue()) {
                mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, mc.player.getDirection()));
                NetworkUtils.sendSequencedPacket(id -> new ServerboundUseItemPacket(mc.player.getOffhandItem().getItem() == Items.BOW ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, id, mc.player.getYRot(), mc.player.getXRot()));
                mc.player.stopUsingItem();
            }
        }
    }

    @Override
    public String getMetaData() {
        return String.valueOf(ticks.getValue().intValue());
    }
}
