package eu.client.modules.impl.player;

import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PacketSendEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;

@RegisterModule(name = "XCarry", description = "Allows you to carry items in your crafting slots.", category = Module.Category.PLAYER)
public class XCarryModule extends Module {
    @SubscribeEvent
    public void onPacketSend(PacketSendEvent event) {
        if (mc.player == null) return;

        if (event.getPacket() instanceof ServerboundContainerClosePacket) {
            event.setCancelled(true);
        }
    }

    @Override
    public void onDisable() {
        if (mc.player == null) return;

        mc.getConnection().send(new ServerboundContainerClosePacket(mc.player.inventoryMenu.containerId));
    }
}
