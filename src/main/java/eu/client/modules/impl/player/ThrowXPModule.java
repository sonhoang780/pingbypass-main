package eu.client.modules.impl.player;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.InventoryUtils;
import eu.client.utils.minecraft.NetworkUtils;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;

@RegisterModule(name = "ThrowXP", description = "Automatically switches to experience bottles and throws them.", category = Module.Category.PLAYER)
public class ThrowXPModule extends Module {
    public ModeSetting autoSwitch = new ModeSetting("Switch", "The mode that will be used for automatically switching to necessary items.", "Silent", InventoryUtils.SWITCH_MODES);
    public NumberSetting delay = new NumberSetting("Delay", "The delay in ticks between throwing experience bottles.", 1, 0, 20);
    public NumberSetting repeat = new NumberSetting("Repeat", "Allows you to throw a lot more XP bottles at once.", 1, 1, 15);
    public ModeSetting antiWaste = new ModeSetting("AntiWaste", "How wasting of experience bottles should be prevented.", "Avoid", new String[]{"None", "Avoid", "Disable"});

    public BooleanSetting itemDisable = new BooleanSetting("ItemDisable", "Automatically disables the module when you run out of XP.", true);

    private int ticks = 0;

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (autoSwitch.getValue().equalsIgnoreCase("None") && !(mc.player.getMainHandItem().getItem() instanceof BlockItem)) {
            EUClient.CHAT_MANAGER.tagged("You are currently not holding any experience bottles.", getName());
            setToggled(false);
            return;
        }

        if (ticks < delay.getValue().intValue()) {
            ticks++;
            return;
        }

        if (!needsExperience() && !antiWaste.getValue().equals("None")) {
            if (antiWaste.getValue().equals("Disable")) setToggled(false);
            return;
        }

        int slot = InventoryUtils.find(Items.EXPERIENCE_BOTTLE, 0, autoSwitch.getValue().equalsIgnoreCase("AltSwap") || autoSwitch.getValue().equalsIgnoreCase("AltPickup") ? 35 : 8);
        int previousSlot = mc.player.getInventory().getSelectedSlot();

        if (slot == -1) {
            EUClient.CHAT_MANAGER.tagged("No experience bottles could be found in your hotbar.", getName());
            setToggled(false);
            return;
        }

        InventoryUtils.switchSlot(autoSwitch.getValue(), slot, previousSlot);

        for (int i = 0; i < repeat.getValue().intValue(); i++) NetworkUtils.sendSequencedPacket(sequence -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, sequence, mc.player.getYRot(), mc.player.getXRot()));
        mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));

        InventoryUtils.switchBack(autoSwitch.getValue(), slot, previousSlot);

        ticks = 0;
    }

    @Override
    public void onEnable() {
        if (mc.player == null || mc.level == null) setToggled(false);
    }

    private boolean needsExperience() {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD}) {
            ItemStack stack = mc.player.getItemBySlot(slot);
            if (!stack.isEmpty() && (stack.is(ItemTags.FOOT_ARMOR) || stack.is(ItemTags.LEG_ARMOR) || stack.is(ItemTags.CHEST_ARMOR) || stack.is(ItemTags.HEAD_ARMOR)) && (Math.round(((stack.getMaxDamage() - stack.getDamageValue()) * 100.0f) / stack.getMaxDamage()) < 100.0f)) {
                return true;
            }
        }

        return false;
    }
}
