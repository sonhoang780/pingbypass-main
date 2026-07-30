package eu.client.modules.impl.combat;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.utils.minecraft.InventoryUtils;
import eu.client.utils.minecraft.PositionUtils;
import eu.client.utils.minecraft.WorldUtils;
import eu.client.utils.rotations.RotationUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;

@RegisterModule(name = "SelfFill", description = "Automatically places a block in the spot that you were previously in.", category = Module.Category.COMBAT, proxyEnhanced = true)
public class SelfFillModule extends Module {
    public ModeSetting autoSwitch = new ModeSetting("Switch", "The mode that will be used for automatically switching to necessary items.", "Silent", InventoryUtils.SWITCH_MODES);
    public ModeSetting jumpMode = new ModeSetting("JumpMode", "The mode that will be used for jumping.", "Normal", new String[]{"Normal", "Packet"});
    public ModeSetting burrow = new ModeSetting("Burrow", "Teleports you inside of the block that was placed.", new ModeSetting.Visibility(jumpMode, "Packet"), "None", new String[]{"None", "Bypass"});
    public BooleanSetting obsidianOnly = new BooleanSetting("ObsidianOnly", "Only places using obsidian.", false);
    public BooleanSetting rotate = new BooleanSetting("Rotate", "Whether or not to rotate when placing the block.", true);
    public BooleanSetting strictDirection = new BooleanSetting("StrictDirection", "Only places using directions that face you.", false);
    public BooleanSetting crystalDestruction = new BooleanSetting("CrystalDestruction", "Whether or not to destroy crystals that obstruct the block's placement.", true);

    public BooleanSetting render = new BooleanSetting("Render", "Whether or not to render the place position.", true);

    private BlockPos lastPosition = null;

    private boolean jumped = false;
    private boolean rotatedBypass = false;
    private int ticks;

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (shouldRunOnProxy()) return;
        if (lastPosition == null) {
            setToggled(false);
            return;
        }

        if (jumpMode.getValue().equalsIgnoreCase("Normal")) {
            if (!jumped) {
                mc.player.jumpFromGround();
                jumped = true;
                ticks = 0;
                return;
            }

            if (ticks++ < 3) return;
        }

        Direction direction = WorldUtils.getDirection(lastPosition, strictDirection.getValue());
        if (direction == null) {
            setToggled(false);
            return;
        }

        if (jumpMode.getValue().equalsIgnoreCase("Packet") && burrow.getValue().equalsIgnoreCase("Bypass")) {
            if (!rotatedBypass) {
                EUClient.ROTATION_MANAGER.rotate(RotationUtils.getRotations(WorldUtils.getHitVector(lastPosition, direction)), this);
                rotatedBypass = true;
                ticks = 0;
                return;
            }

            if (ticks++ < 3) return;
        }

        if (autoSwitch.getValue().equalsIgnoreCase("None") && (!(mc.player.getMainHandItem().getItem() instanceof BlockItem) || (obsidianOnly.getValue() && mc.player.getMainHandItem().getItem() != Items.OBSIDIAN))) {
            EUClient.CHAT_MANAGER.tagged("You are currently not holding any " + (obsidianOnly.getValue() ? "valid " : "") + "blocks.", getName());
            setToggled(false);
            return;
        }

        if (!mc.level.getBlockState(lastPosition).canBeReplaced()) return;
        if (!mc.level.getEntities((Entity) null, new AABB(lastPosition), e -> e != mc.player && !(e instanceof ExperienceOrb) && !(e instanceof ItemEntity) && !(e instanceof EndCrystal)).isEmpty()) return;

        int slot = InventoryUtils.findHardestBlock(0, autoSwitch.getValue().equalsIgnoreCase("AltSwap") || autoSwitch.getValue().equalsIgnoreCase("AltPickup") ? 35 : 8);
        int previousSlot = mc.player.getInventory().getSelectedSlot();

        if (obsidianOnly.getValue()) slot = InventoryUtils.find(Items.OBSIDIAN, 0, autoSwitch.getValue().equalsIgnoreCase("AltSwap") || autoSwitch.getValue().equalsIgnoreCase("AltPickup") ? 35 : 8);

        if (!autoSwitch.getValue().equalsIgnoreCase("None") && slot == -1) {
            EUClient.CHAT_MANAGER.tagged("There are currently no " + (obsidianOnly.getValue() ? "valid " : "") + "blocks in your hotbar.", getName());
            setToggled(false);
            return;
        }

        if (jumpMode.getValue().equals("Packet")) {
            mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(mc.player.getX(), mc.player.getY() + 0.4, mc.player.getZ(), false, mc.player.horizontalCollision));
            mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(mc.player.getX(), mc.player.getY() + 0.75, mc.player.getZ(), false, mc.player.horizontalCollision));
            mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(mc.player.getX(), mc.player.getY() + 1.01, mc.player.getZ(), false, mc.player.horizontalCollision));
            mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(mc.player.getX(), mc.player.getY() + (jumpMode.getValue().equalsIgnoreCase("Packet") && burrow.getValue().equalsIgnoreCase("Bypass") ? 0.99999992 : 1.15), mc.player.getZ(), false, mc.player.horizontalCollision));
        }

        InventoryUtils.switchSlot(autoSwitch.getValue(), slot, previousSlot);
        WorldUtils.placeBlock(lastPosition, direction, InteractionHand.MAIN_HAND, !(jumpMode.getValue().equalsIgnoreCase("Packet") && burrow.getValue().equalsIgnoreCase("Bypass")) && rotate.getValue(), crystalDestruction.getValue(), render.getValue());
        InventoryUtils.switchBack(autoSwitch.getValue(), slot, previousSlot);

        if (jumpMode.getValue().equals("Packet") && burrow.getValue().equalsIgnoreCase("Bypass")) {
            mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(mc.player.getX(), mc.player.getY() + 1.15, mc.player.getZ(), mc.player.onGround(), mc.player.horizontalCollision));
            mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYRot(), mc.player.getXRot(), mc.player.onGround(), mc.player.horizontalCollision));
            mc.player.setPos(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        }

        setToggled(false);

        jumped = false;
        rotatedBypass = false;
    }

    @Override
    public void onEnable() {
        if (mc.player == null || mc.level == null) {
            setToggled(false);
            return;
        }

        if (!mc.player.onGround()) {
            EUClient.CHAT_MANAGER.tagged("You are currently in the air.", getName());
            setToggled(false);
            return;
        }

        lastPosition = PositionUtils.getFlooredPosition(mc.player);
    }
}
