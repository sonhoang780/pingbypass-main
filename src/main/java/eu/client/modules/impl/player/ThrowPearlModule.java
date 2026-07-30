package eu.client.modules.impl.player;

import eu.client.EUClient;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.utils.minecraft.InventoryUtils;
import eu.client.utils.minecraft.NetworkUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;

@RegisterModule(name = "ThrowPearl", description = "Automatically switches to pearls and throws them.", category = Module.Category.PLAYER)
public class ThrowPearlModule extends Module {
    public ModeSetting autoSwitch = new ModeSetting("Switch", "The mode that will be used for automatically switching to necessary items.", "Silent", InventoryUtils.SWITCH_MODES);
    public BooleanSetting rotate = new BooleanSetting("Rotate", "Sends a packet rotation right before throwing the pearl.", true);

    @Override
    public void onEnable() {
        if (mc.player == null || mc.level == null) {
            setToggled(false);
            return;
        }

        if (autoSwitch.getValue().equalsIgnoreCase("None") && mc.player.getMainHandItem().getItem() != Items.ENDER_PEARL) {
            EUClient.CHAT_MANAGER.tagged("You are currently not holding any pearls.", getName());
            setToggled(false);
            return;
        }

        if (mc.player.getCooldowns().isOnCooldown(new ItemStack(Items.ENDER_PEARL))) {
            setToggled(false);
            return;
        }

        int slot = InventoryUtils.find(Items.ENDER_PEARL, 0, autoSwitch.getValue().equalsIgnoreCase("AltSwap") || autoSwitch.getValue().equalsIgnoreCase("AltPickup") ? 35 : 8);
        int previousSlot = mc.player.getInventory().getSelectedSlot();

        if (slot == -1) {
            EUClient.CHAT_MANAGER.tagged("No pearls could be found in your hotbar.", getName());
            setToggled(false);
            return;
        }

        InventoryUtils.switchSlot(autoSwitch.getValue(), slot, previousSlot);

        EUClient.ROTATION_MANAGER.packetRotate(mc.player.getYRot(), mc.player.getXRot());
        NetworkUtils.sendSequencedPacket(sequence -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, sequence, mc.player.getYRot(), mc.player.getXRot()));
        mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));

        InventoryUtils.switchBack(autoSwitch.getValue(), slot, previousSlot);

        setToggled(false);
    }
}
