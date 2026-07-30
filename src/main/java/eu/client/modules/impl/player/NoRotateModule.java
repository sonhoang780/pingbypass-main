package eu.client.modules.impl.player;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PacketReceiveEvent;
import eu.client.mixins.accessors.PlayerPositionAccessor;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.utils.minecraft.PositionUtils;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.entity.Relative;

@RegisterModule(name = "NoRotate", description = "Prevents the server from forcing rotations on you.", category = Module.Category.PLAYER)
public class NoRotateModule extends Module {
    public BooleanSetting inBlocks = new BooleanSetting("InBlocks", "Whether or not to stop rotations whenever inside of a block.", false);
    public BooleanSetting spoof = new BooleanSetting("Spoof", "Sends rotation packets once you have been rubberbanded.", false);

    @SubscribeEvent
    public void onPacketReceive(PacketReceiveEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (!inBlocks.getValue() && !mc.level.getBlockState(PositionUtils.getFlooredPosition(mc.player)).canBeReplaced()) return;

        if (event.getPacket() instanceof ClientboundPlayerPositionPacket packet) {
            if (spoof.getValue()) {
                EUClient.ROTATION_MANAGER.packetRotate(packet.change().yRot(), packet.change().xRot());
                EUClient.ROTATION_MANAGER.packetRotate(mc.player.getYRot(), mc.player.getXRot());
            }

            ((PlayerPositionAccessor) (Object) packet.change()).setYaw(mc.player.getYRot());
            ((PlayerPositionAccessor) (Object) packet.change()).setPitch(mc.player.getXRot());

            packet.relatives().remove(Relative.X_ROT);
            packet.relatives().remove(Relative.Y_ROT);
        }
    }
}
