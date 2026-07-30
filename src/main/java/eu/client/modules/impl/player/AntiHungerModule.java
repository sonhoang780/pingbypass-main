package eu.client.modules.impl.player;

import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PacketSendEvent;
import eu.client.events.impl.UpdateMovementEvent;
import eu.client.mixins.accessors.PlayerMoveC2SPacketAccessor;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

@RegisterModule(name = "AntiHunger", description = "Reduces the amount of hunger consumption.", category = Module.Category.PLAYER)
public class AntiHungerModule extends Module {
    public BooleanSetting ground = new BooleanSetting("Ground", "Modifies movement packets to decrease hunger consumption.", true);
    public BooleanSetting sprint = new BooleanSetting("Sprint", "Spoofs sprinting packets to decrease hunger consumption.", true);

    private boolean lastOnGround = false;
    private boolean ignore = false;

    @SubscribeEvent
    public void onPacketSend(PacketSendEvent event) {
        if (mc.player == null || mc.level == null) return;

        if (ignore && event.getPacket() instanceof ServerboundMovePlayerPacket) {
            ignore = false;
            return;
        }

        if (mc.player.isPassenger() || mc.player.isInWater() || mc.player.isUnderWater()) return;

        if (event.getPacket() instanceof ServerboundMovePlayerPacket packet && ground.getValue()) {
            if (mc.player.onGround() && mc.player.fallDistance <= 0.0 && !mc.gameMode.isDestroying()) {
                ((PlayerMoveC2SPacketAccessor) packet).setOnGround(false);
            }
        }

        if (event.getPacket() instanceof ServerboundPlayerCommandPacket packet && sprint.getValue()) {
            if (packet.getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING) {
                event.setCancelled(true);
            }
        }
    }

    @SubscribeEvent
    public void onUpdateMovement(UpdateMovementEvent event) {
        if (mc.player == null || mc.level == null) return;

        if (mc.player.onGround() && !lastOnGround && ground.getValue()) ignore = true;
        lastOnGround = mc.player.onGround();
    }

    @Override
    public void onEnable() {
        if (mc.player == null) return;
        lastOnGround = mc.player.onGround();
    }
}
