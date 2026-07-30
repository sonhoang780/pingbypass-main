package eu.client.modules.impl.movement;

import eu.client.events.SubscribeEvent;
import eu.client.events.impl.*;
import eu.client.mixins.accessors.PlayerMoveC2SPacketAccessor;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.MovementUtils;
import eu.client.utils.system.MathUtils;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.world.phys.Vec3;

import java.util.List;

@RegisterModule(name = "ElytraFly", description = "Allows you to fly using an elytra without fireworks.", category = Module.Category.MOVEMENT)
public class ElytraFlyModule extends Module {
    public ModeSetting mode = new ModeSetting("Mode", "The mode that will be used for elytra flying.", "Control", new String[]{"Packet", "Control"});

    public NumberSetting horizontal = new NumberSetting("Horizontal", "The speed at which you will be flying horizontally.", 2.0f, 0.1f, 10.0f);
    public NumberSetting vertical = new NumberSetting("Vertical", "The speed at which you will be flying vertically.", 1.0f, 0.1f, 10.0f);

    public BooleanSetting moveVertically = new BooleanSetting("MoveVertically", "Whether or not to allow for vertical movement.", new ModeSetting.Visibility(mode, "Packet", "Control"), true);

    public BooleanSetting infiniteDurability = new BooleanSetting("InfiniteDurability", "Prevents your elytra from having any durability used up.", new ModeSetting.Visibility(mode, "Packet"), false);
    public BooleanSetting stopOnGround = new BooleanSetting("StopOnGround", "Stops flying when you hit the ground.", new ModeSetting.Visibility(mode, "Packet"), true);
    public ModeSetting ncpStrict = new ModeSetting("NCPStrict", "Makes use of special bypasses for the NoCheatPlus anticheat.", "None", new String[]{"None", "Old", "New", "Motion"});

    private float pitch;

    @SubscribeEvent
    public void onPlayerMove(PlayerMoveEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (!mode.getValue().equalsIgnoreCase("Packet")) return;

        mc.player.getAbilities().flying = false;
        mc.player.getAbilities().setFlyingSpeed(0.05F);

        if ((mc.level.getBlockCollisions(mc.player, mc.player.getBoundingBox().inflate(-0.25, 0.0, -0.25).move(0.0, -0.3, 0.0)).iterator().hasNext() && stopOnGround.getValue()) || mc.player.getInventory().getItem(38).getItem() != Items.ELYTRA)
            return;

        mc.player.getAbilities().flying = true;
        mc.player.getAbilities().setFlyingSpeed(horizontal.getValue().floatValue() / 15.0f);
        event.setCancelled(true);

        if (Math.abs(event.getX()) < 0.05) event.setX(0);
        if (Math.abs(event.getZ()) < 0.05) event.setZ(0);

        event.setY(moveVertically.getValue() ? mc.options.keyJump.isDown() ? vertical.getValue().doubleValue() : mc.options.keyShift.isDown() ? -vertical.getValue().doubleValue() : 0 : 0);

        switch (ncpStrict.getValue().toLowerCase()) {
            case "old" -> event.setY(0.0002 - (mc.player.tickCount % 2 == 0 ? 0 : 0.000001) + MathUtils.random(0.0000009, 0));
            case "new" -> event.setY(-1.000088900582341E-12);
            case "motion" -> event.setY(-4.000355602329364E-12);
        }

        if (mc.player.horizontalCollision && (ncpStrict.getValue().equalsIgnoreCase("New") || ncpStrict.getValue().equalsIgnoreCase("Motion")) && mc.player.tickCount % 2 == 0) event.setY(-0.07840000152587923);

        if (infiniteDurability.getValue() || ncpStrict.getValue().equalsIgnoreCase("Motion")) {
            if (!MovementUtils.isMoving() && Math.abs(event.getX()) < 0.121) {
                float angleToRad = (float) Math.toRadians(4.5 * (mc.player.tickCount % 80));
                event.setX(Math.sin(angleToRad) * 0.12);
                event.setZ(Math.cos(angleToRad) * 0.12);
            }
        }
    }

    @SubscribeEvent
    public void onSendMovement(SendMovementEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (!mode.getValue().equalsIgnoreCase("Packet")) return;

        if ((!mc.level.getBlockCollisions(mc.player, mc.player.getBoundingBox().inflate(-0.25, 0.0, -0.25).move(0.0, -0.3, 0.0)).iterator().hasNext() || !stopOnGround.getValue()) && mc.player.getInventory().getItem(38).getItem() == Items.ELYTRA) {
            if (infiniteDurability.getValue() || !mc.player.isFallFlying()) mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            if (mc.player.tickCount % 3 != 0 && ncpStrict.getValue().equalsIgnoreCase("Motion")) event.setCancelled(true);
        }
    }

    @SubscribeEvent
    public void onPlayerTravel(PlayerTravelEvent event) {
        if (mc.player == null || mc.level == null || !mc.player.isFallFlying()) return;

        if (mode.getValue().equalsIgnoreCase("Control")) {
            event.setCancelled(true);

            if (mc.player.input.getMoveVector().y == 0.0f && mc.player.input.getMoveVector().x == 0.0f) {
                mc.player.setDeltaMovement(new Vec3(0.0, mc.player.getDeltaMovement().y, 0.0));
            } else {
                pitch = 12;

                double cos = Math.cos(Math.toRadians(mc.player.getYRot() + 90.0f));
                double sin = Math.sin(Math.toRadians(mc.player.getYRot() + 90.0f));

                mc.player.setDeltaMovement(new Vec3(((mc.player.input.getMoveVector().y * horizontal.getValue().doubleValue() * cos) + (mc.player.input.getMoveVector().x * horizontal.getValue().doubleValue() * sin)), mc.player.getDeltaMovement().y, (mc.player.input.getMoveVector().y * horizontal.getValue().doubleValue() * sin) - (mc.player.input.getMoveVector().x * horizontal.getValue().doubleValue() * cos)));
            }

            mc.player.setDeltaMovement(new Vec3(mc.player.getDeltaMovement().x, 0.0, mc.player.getDeltaMovement().z));

            if (moveVertically.getValue()) {
                if (mc.options.keyJump.isDown()) {
                    mc.player.setDeltaMovement(new Vec3(mc.player.getDeltaMovement().x, vertical.getValue().doubleValue(), mc.player.getDeltaMovement().z));
                    pitch = -51;
                } else if (mc.options.keyShift.isDown()) {
                    mc.player.setDeltaMovement(new Vec3(mc.player.getDeltaMovement().x, -vertical.getValue().doubleValue(), mc.player.getDeltaMovement().z));
                    pitch = 0;
                }
            }
        }
    }

    @SubscribeEvent
    public void onPacketReceive(PacketReceiveEvent event) {
        if (mc.player == null || mc.level == null) return;

        if (mode.getValue().equalsIgnoreCase("Packet")) {
            if (event.getPacket() instanceof ClientboundSetEntityDataPacket packet && packet.id() == mc.player.getId()) {
                List<SynchedEntityData.DataValue<?>> values = packet.packedItems();
                if (values.isEmpty()) return;

                for (SynchedEntityData.DataValue<?> value : values) {
                    if (value.value().toString().equals("FALL_FLYING") || (value.id() == 0 && (value.value().toString().equals("-120") || value.value().toString().equals("-128") || value.value().toString().equals("-126")))) {
                        event.setCancelled(true);
                    }
                }
            }
        }

        if (mode.getValue().equalsIgnoreCase("Control")) {
            if (event.getPacket() instanceof ServerboundMovePlayerPacket packet && packet.hasRotation() && mc.player.isFallFlying()) {
                if (mode.getValue().equalsIgnoreCase("Control")) {
                    if (mc.options.keyLeft.isDown()) ((PlayerMoveC2SPacketAccessor) packet).setYaw(packet.getYRot(0.0f) - 90.0f);
                    if (mc.options.keyRight.isDown()) ((PlayerMoveC2SPacketAccessor) packet).setYaw(packet.getYRot(0.0f) + 90.0f);
                }

                ((PlayerMoveC2SPacketAccessor) packet).setPitch(pitch);
            }
        }
    }

    @Override
    public void onDisable() {
        if (mc.player == null) return;

        mc.player.getAbilities().flying = false;
        mc.player.getAbilities().setFlyingSpeed(0.05F);
    }

    @Override
    public String getMetaData() {
        return mode.getValue();
    }
}