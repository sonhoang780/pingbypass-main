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
import net.minecraft.world.level.block.Blocks;
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
    // Ported from example-addon-master's PearlPhase (dev.leonetic's PhaseModule, via boze-api) --
    // that version aims at an explicit XYZ target and retargets its Y from playerY-0.5 to
    // blockPosition().below().getY() under Crawl, because crawling lowers eye height and the
    // fixed target undershoots/overshoots from the wrong eye position. This module doesn't build
    // an explicit target at all normally (fixed Pitch setting, straight down); Crawl here instead
    // aims properly at the exact block-below center via RotationUtils, same fix adapted to this
    // module's simpler fixed-pitch throw instead of PearlPhase's variable-charge/solved-pitch one.
    public BooleanSetting crawl = new BooleanSetting("Crawl", "Aims at the exact block underfoot instead of a fixed pitch -- needed to phase while in the crawling pose, where the lower eye height throws a fixed-pitch pearl off target.", false);

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
            // Scaffolding sitting on the player's own position used to just abort Phase entirely
            // (canBeReplaced() is false for it, same as any other real block) -- but scaffolding
            // breaks in a single hit for any tool (hardness ~0), so break it out of the way first
            // instead of giving up. Any OTHER real block still aborts same as before -- this
            // isn't a general "clear my own position" feature, just the one block type cheap
            // enough to instantly clear.
            if (!mc.level.getBlockState(mc.player.blockPosition()).canBeReplaced()) {
                if (!mc.level.getBlockState(mc.player.blockPosition()).is(Blocks.SCAFFOLDING)) {
                    setToggled(false);
                    return;
                }

                BlockPos selfPosition = mc.player.blockPosition();
                NetworkUtils.sendSequencedPacket(sequence -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, selfPosition, Direction.UP, sequence));
                NetworkUtils.sendSequencedPacket(sequence -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, selfPosition, Direction.UP, sequence));
                mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                // Instant local feedback, same reasoning as SpeedMine's own client-side removal --
                // don't wait on the server's block-update round-trip before continuing below.
                mc.level.removeBlock(selfPosition, false);
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

            // Crawl: aim at the block underfoot's actual center instead of the fixed Pitch
            // setting -- the crawling pose's lower eye height means a pitch tuned for standing
            // no longer points at the same spot relative to the block below.
            float throwPitch = crawl.getValue()
                    ? RotationUtils.getRotations(Vec3.atCenterOf(downPosition))[1]
                    : pitch.getValue().intValue();
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

            EUClient.ROTATION_MANAGER.packetRotate(yaw, throwPitch);

            InventoryUtils.switchSlot(autoSwitch.getValue(), slot, previousSlot);

            NetworkUtils.sendSequencedPacket(sequence -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, sequence, yaw, throwPitch));
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
