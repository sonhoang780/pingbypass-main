package eu.client.modules.impl.movement;

import eu.client.EUClient;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.InventoryUtils;
import eu.client.utils.minecraft.MovementUtils;
import eu.client.utils.minecraft.NetworkUtils;
import eu.client.utils.minecraft.WorldUtils;
import eu.client.utils.rotations.RotationUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

@RegisterModule(name = "Phase", description = "Phases you inside of a block using pearls.", category = Module.Category.MOVEMENT)
public class PhaseModule extends Module {
    public ModeSetting mode = new ModeSetting("Mode", "The method that will be used for phasing.", "Pearl", new String[]{"Pearl", "Teleport"});
    public ModeSetting autoSwitch = new ModeSetting("Switch", "The mode that will be used for automatically switching to necessary items.", new ModeSetting.Visibility(mode, "Pearl"), "Silent", InventoryUtils.SWITCH_MODES);
    public NumberSetting pitch = new NumberSetting("Pitch", "The pitch at which the pearl will be thrown.", new ModeSetting.Visibility(mode, "Pearl"), 85, 70, 90);

    public BooleanSetting fireCharge = new BooleanSetting("FireCharge", "Uses fire to bypass pearl distance checks.", false);
    public BooleanSetting alternative = new BooleanSetting("Alternative", "Uses a flint and steel rather than a fire charge to perform the phase bypass.", false);
    public ModeSetting fireSwitch = new ModeSetting("FireSwitch", "The mode that will be used for switching to a fire charge.", new BooleanSetting.Visibility(fireCharge, true), "Silent", new String[]{"Normal", "Silent", "AltPickup", "AltSwap"});
    public BooleanSetting autoRemove = new BooleanSetting("AutoRemove", "Automatically removes the fire once you have phased.", new BooleanSetting.Visibility(fireCharge, true), true);

    @Override
    public void onEnable() {
        if (mc.player == null || mc.level == null) {
            setToggled(false);
            return;
        }

        if (mode.getValue().equalsIgnoreCase("Pearl")) {
            if (!mc.level.getBlockState(mc.player.blockPosition()).canBeReplaced()) {
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
            boolean didFireCharge = false;

            if (slot == -1) {
                EUClient.CHAT_MANAGER.tagged("No pearls could be found in your hotbar.", getName());
                setToggled(false);
                return;
            }

            float yaw = Math.round(RotationUtils.getRotations(new Vec3(Math.floor(mc.player.getX()) + 0.5, 0, Math.floor(mc.player.getZ()) + 0.5))[0]) + 180;

            float prevYaw = mc.player.getYRot();
            float prevPitch = mc.player.getXRot();

            BlockPos downPosition = mc.player.blockPosition().below();
            if (fireCharge.getValue() && mc.level.getBlockState(mc.player.blockPosition()).isAir() && !(mc.level.getBlockState(downPosition).canBeReplaced())) {
                int chargeSlot = InventoryUtils.find(alternative.getValue() ? Items.FLINT_AND_STEEL : Items.FIRE_CHARGE, InventoryUtils.HOTBAR_START, fireSwitch.getValue().equalsIgnoreCase("AltSwap") || fireSwitch.getValue().equalsIgnoreCase("AltPickup") ? 35 : 8);

                if (chargeSlot != -1) {
                    EUClient.ROTATION_MANAGER.packetRotate(yaw, 90);

                    InventoryUtils.switchSlot(fireSwitch.getValue(), chargeSlot, previousSlot);
                    NetworkUtils.sendSequencedPacket(sequence -> new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(downPosition).add(0, 1, 0), Direction.UP, downPosition, false), sequence));
                    mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                    InventoryUtils.switchBack(fireSwitch.getValue(), chargeSlot, previousSlot);

                    didFireCharge = true;
                }
            }

            EUClient.ROTATION_MANAGER.packetRotate(yaw, pitch.getValue().intValue());

            InventoryUtils.switchSlot(autoSwitch.getValue(), slot, previousSlot);

            NetworkUtils.sendSequencedPacket(sequence -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, sequence, yaw, pitch.getValue().intValue()));
            mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));

            InventoryUtils.switchBack(autoSwitch.getValue(), slot, previousSlot);

            if (didFireCharge && autoRemove.getValue()) {
                NetworkUtils.sendSequencedPacket(sequence -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, mc.player.blockPosition(), Direction.UP, sequence));
                NetworkUtils.sendSequencedPacket(sequence -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, mc.player.blockPosition(), Direction.UP, sequence));
                mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
            }

            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(prevYaw, prevPitch, mc.player.onGround(), mc.player.horizontalCollision));

            setToggled(false);
        }

        if (mode.getValue().equalsIgnoreCase("Teleport")) {
            if (!mc.player.onGround()) {
                setToggled(false);
                return;
            }

            double[] diagonalOffset = MovementUtils.straightForward(0.44);
            boolean diagonal = mc.player.getYRot() % 90 > 35 && mc.player.getYRot() % 90 < 55;

            mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_SPRINTING));

            if (diagonal) {
                double[] directionVec = MovementUtils.straightForward(0.51);

                int height = mc.level.clip(new ClipContext(mc.player.getEyePosition(), mc.player.getEyePosition().add(diagonalOffset[0],0, diagonalOffset[1]), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player)).getType().equals(HitResult.Type.MISS) ? 1 : 2;

                mc.player.setPos(mc.player.getX() + directionVec[0], mc.player.getY() + height, mc.player.getZ() + directionVec[1]);
                mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(mc.player.getX(), mc.player.getY(), mc.player.getZ(), true, mc.player.horizontalCollision));

                height = mc.level.getBlockState(BlockPos.containing(mc.player.position().add(diagonalOffset[0], -2, diagonalOffset[1]))).isAir() ? 2 : 1;

                mc.player.setPos(mc.player.getX() + directionVec[0], mc.player.getY() - height, mc.player.getZ() + directionVec[1]);
                mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(mc.player.getX(), mc.player.getY(), mc.player.getZ(), true, mc.player.horizontalCollision));
            } else {
                double[] directionVec = MovementUtils.straightForward(0.57);

                int height = mc.level.clip(new ClipContext(mc.player.getEyePosition(), mc.player.getEyePosition().add(diagonalOffset[0],0, diagonalOffset[1]), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player)).getType().equals(HitResult.Type.MISS) ? 1 : 2;

                mc.player.setPos(mc.player.getX() + directionVec[0], mc.player.getY() + height, mc.player.getZ() + directionVec[1]);
                mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(mc.player.getX(), mc.player.getY(), mc.player.getZ(), true, mc.player.horizontalCollision));

                mc.player.setPos(mc.player.getX() + directionVec[0], mc.player.getY(), mc.player.getZ() + directionVec[1]);
                mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(mc.player.getX(), mc.player.getY(), mc.player.getZ(), true, mc.player.horizontalCollision));

                height = mc.level.getBlockState(BlockPos.containing(mc.player.position().add(diagonalOffset[0], -2, diagonalOffset[1]))).isAir() ? 2 : 1;

                mc.player.setPos(mc.player.getX() + directionVec[0], mc.player.getY() - height, mc.player.getZ() + directionVec[1]);
                mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(mc.player.getX(), mc.player.getY(), mc.player.getZ(), true, mc.player.horizontalCollision));
            }

            mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
            setToggled(false);
        }
    }
}
