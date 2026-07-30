package eu.client.modules.impl.player;

import eu.client.EUClient;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.ModeSetting;
import eu.client.utils.minecraft.InventoryUtils;
import eu.client.utils.minecraft.NetworkUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;

@RegisterModule(name = "ThrowFirework", description = "Automatically switches to fireworks and throws them.", category = Module.Category.PLAYER)
public class ThrowFireworkModule extends Module {
    public ModeSetting autoSwitch = new ModeSetting("Switch", "The mode that will be used for automatically switching to necessary items.", "Silent", InventoryUtils.SWITCH_MODES);

    @Override
    public void onEnable() {
        if (mc.player == null || mc.level == null) {
            setToggled(false);
            return;
        }

        if (autoSwitch.getValue().equalsIgnoreCase("None") && mc.player.getMainHandItem().getItem() != Items.FIREWORK_ROCKET) {
            EUClient.CHAT_MANAGER.tagged("You are currently not holding any fireworks.", getName());
            setToggled(false);
            return;
        }

        if (mc.player.getCooldowns().isOnCooldown(new ItemStack(Items.FIREWORK_ROCKET))) {
            setToggled(false);
            return;
        }

        int slot = InventoryUtils.find(Items.FIREWORK_ROCKET, 0, autoSwitch.getValue().equalsIgnoreCase("AltSwap") || autoSwitch.getValue().equalsIgnoreCase("AltPickup") ? 35 : 8);
        int previousSlot = mc.player.getInventory().getSelectedSlot();

        if (slot == -1) {
            EUClient.CHAT_MANAGER.tagged("No fireworks could be found in your hotbar.", getName());
            setToggled(false);
            return;
        }

        InventoryUtils.switchSlot(autoSwitch.getValue(), slot, previousSlot);
        NetworkUtils.sendSequencedPacket(sequence -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, sequence, mc.player.getYRot(), mc.player.getXRot()));
        InventoryUtils.switchBack(autoSwitch.getValue(), slot, previousSlot);

        setToggled(false);
    }
}
