package eu.client.modules.impl.movement;

import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PacketReceiveEvent;
import eu.client.events.impl.PacketSendEvent;
import eu.client.events.impl.TickEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.EntityUtils;
import eu.client.utils.system.Timer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;

@RegisterModule(name = "FakeLag", description = "Chokes sent packets to look like you are lagging.", category = Module.Category.MOVEMENT)
public class FakeLagModule extends Module {
    public NumberSetting choke = new NumberSetting("Choke", "The delay to choke packets for.", 2, 1, 5);

    private final ArrayList<ServerboundMovePlayerPacket> packets = new ArrayList<>();
    private final Timer timer = new Timer();
    private final Timer safety = new Timer();
    private boolean sending = false;

    @SubscribeEvent
    public void onPacketReceive(PacketReceiveEvent event) {
        if(getNull()) return;

        if(event.getPacket() instanceof ClientboundPlayerPositionPacket || event.getPacket() instanceof ClientboundDisconnectPacket || event.getPacket() instanceof ClientboundSetHealthPacket packet && packet.getHealth() <= 0) {
            sendPackets();
            safety.reset();
        }
    }

    @SubscribeEvent
    public void onPacketSend(PacketSendEvent event) {
        if (getNull() || sending || !shouldChoke() || !(event.getPacket() instanceof ServerboundMovePlayerPacket packet)) return;

        synchronized (packets) {
            event.setCancelled(true);
            packets.add(packet);
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if(getNull() || packets.isEmpty()) return;

        if(timer.hasTimeElapsed((int) (choke.getValue().floatValue()*100))) {
            sendPackets();
            timer.reset();
        }
    }

    @Override
    public void onEnable() {
        timer.reset();
    }

    @Override
    public void onDisable() {
        if(getNull()) return;
        sendPackets();
    }

    private void sendPackets() {
        synchronized (packets) {
            sending = true;
            for(ServerboundMovePlayerPacket packet : packets) {
                mc.player.connection.send(packet);
            }
            packets.clear();
            sending = false;
        }
    }

    private boolean shouldChoke() {
        return (EntityUtils.getSpeed(mc.player, EntityUtils.SpeedUnit.KILOMETERS) >= 5 || mc.player.fallDistance > 0) && safety.hasTimeElapsed(1000);
    }

    @Override
    public String getMetaData() {
        return (shouldChoke() ? ChatFormatting.GREEN : ChatFormatting.RED) + "Choke";
    }
}
