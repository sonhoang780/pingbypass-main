package eu.client.modules.impl.movement;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.*;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.NetworkUtils;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

@RegisterModule(name = "NoSlow", description = "Removes the slowness effect that you receive when doing certain actions.", category = Module.Category.MOVEMENT)
public class NoSlowModule extends Module {
    public BooleanSetting items = new BooleanSetting("Items", "Removes the slowness effect from eating or using items.", true);
    public BooleanSetting soulSand = new BooleanSetting("SoulSand", "Removes the slowness effect from walking on soul sand.", false);
    public BooleanSetting slimeBlocks = new BooleanSetting("SlimeBlocks", "Removes the slowness effect from walking on slime blocks.", false);
    public BooleanSetting honeyBlocks = new BooleanSetting("HoneyBlocks", "Removes the slowness effect from walking on honey blocks.", false);

    public BooleanSetting ncpStrict = new BooleanSetting("NCPStrict", "Makes use of ground bypasses for the NoCheatPlus anticheat.", false);
    public BooleanSetting airStrict = new BooleanSetting("AirStrict", "Makes use of air bypasses for the NoCheatPlus anticheat.", false);
    public BooleanSetting grimStrict = new BooleanSetting("GrimStrict", "Makes use of bypasses for the Grim anticheat.", false);

    private boolean sneaking = false;

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null) return;

        if (airStrict.getValue() && sneaking && !mc.player.isUsingItem()) {
            mc.getConnection().send(new ServerboundPlayerInputPacket(new Input(false, false, false, false, false, false, false)));
            sneaking = false;
        }
    }

    @SubscribeEvent
    public void onUpdateMovement(UpdateMovementEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (!grimStrict.getValue()) return;
        if (!mc.player.isUsingItem() || mc.player.isPassenger() || mc.player.isFallFlying()) return;

        if (mc.player.getUsedItemHand() == InteractionHand.OFF_HAND) {
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(mc.player.getInventory().getSelectedSlot() % 8 + 1));
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(mc.player.getInventory().getSelectedSlot() % 7 + 2));
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(mc.player.getInventory().getSelectedSlot()));
        } else {
            NetworkUtils.sendSequencedPacket(id -> new ServerboundUseItemPacket(InteractionHand.OFF_HAND, id, mc.player.getYRot(), mc.player.getXRot()));
        }
    }

    @SubscribeEvent
    public void onPacketSend(PacketSendEvent event) {
        if (mc.player == null || mc.level == null) return;

        if (ncpStrict.getValue()) {
            if (event.getPacket() instanceof ServerboundMovePlayerPacket.PosRot || event.getPacket() instanceof ServerboundMovePlayerPacket.Pos || event.getPacket() instanceof ServerboundMovePlayerPacket.Rot || event.getPacket() instanceof ServerboundMovePlayerPacket.StatusOnly) {
                mc.player.connection.send(new ServerboundSetCarriedItemPacket(mc.player.getInventory().getSelectedSlot()));
            }

            if (event.getPacket() instanceof ServerboundContainerClickPacket) {
                if (mc.player.isUsingItem()) mc.player.stopUsingItem();
                if (mc.player.isSprinting()) mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
                if (mc.player.isShiftKeyDown()) mc.player.connection.send(new ServerboundPlayerInputPacket(new Input(false, false, false, false, false, false, false)));
            }
        }
    }

    @SubscribeEvent
    public void onChangeHand(ChangeHandEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (airStrict.getValue() && !sneaking && (!mc.player.isPassenger() && !mc.player.isShiftKeyDown() && (mc.player.isUsingItem() && items.getValue() && !grimStrict.getValue()))) {
            mc.getConnection().send(new ServerboundPlayerInputPacket(new Input(false, false, false, false, false, true, false)));
            sneaking = true;
        }
    }

    @Override
    public void onDisable() {
        EUClient.WORLD_MANAGER.setTimerMultiplier(1.0f);

        if (mc.player == null) return;

        if (airStrict.getValue() && sneaking) {
            mc.getConnection().send(new ServerboundPlayerInputPacket(new Input(false, false, false, false, false, false, false)));
        }

        sneaking = false;
    }

    public boolean shouldSlow() {
        return grimStrict.getValue() && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND && (mc.player.getOffhandItem().get(DataComponents.FOOD) != null || mc.player.getOffhandItem().getItem() == Items.SHIELD);
    }
}
