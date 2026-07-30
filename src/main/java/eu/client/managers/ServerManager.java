package eu.client.managers;

import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import lombok.Getter;
import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.*;
import eu.client.modules.impl.miscellaneous.FastLatencyModule;
import eu.client.utils.IMinecraft;
import eu.client.utils.system.Timer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;

import java.util.Arrays;
import java.util.UUID;

@Getter
public class ServerManager implements IMinecraft {
    private final Timer setbackTimer = new Timer();
    private final Timer responseTimer = new Timer();

    private final float[] tickRates = new float[20];
    private int nextIndex = 0;
    private long lastUpdate = -1;
    private long timeJoined;

    private Pair<ServerAddress, ServerData> lastConnection;

    public ServerManager() {
        EUClient.EVENT_HANDLER.subscribe(this);
    }

    @SubscribeEvent
    public void onPacketReceive(PacketReceiveEvent event) {
        responseTimer.reset();

        if (event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            setbackTimer.reset();
        }

        if (event.getPacket() instanceof ClientboundSetTimePacket) {
            tickRates[nextIndex] = Math.clamp(20.0f / ((System.currentTimeMillis() - lastUpdate) / 1000.0F), 0.0f, 20.0f);
            nextIndex = (nextIndex + 1) % tickRates.length;
            lastUpdate = System.currentTimeMillis();
        }
    }

    @SubscribeEvent
    public void onClientConnect(ClientConnectEvent event) {
        Arrays.fill(tickRates, 0);
        nextIndex = 0;
        timeJoined = System.currentTimeMillis();
        lastUpdate = System.currentTimeMillis();
    }

    @SubscribeEvent
    public void handleConnections(PacketReceiveEvent event) {
        if (mc.level == null) return;

        if (event.getPacket() instanceof ClientboundPlayerInfoUpdatePacket packet) {
            if (packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
                for(ClientboundPlayerInfoUpdatePacket.Entry entry : packet.newEntries()) {
                    EUClient.EVENT_HANDLER.post(new PlayerConnectEvent(entry.profile().id()));
                }
            }
        } else if(event.getPacket() instanceof ClientboundPlayerInfoRemovePacket packet) {
            for(UUID id : packet.profileIds()) {
                EUClient.EVENT_HANDLER.post(new PlayerDisconnectEvent(id));
            }
        }
    }

    @SubscribeEvent
    public void onServerConnect(ServerConnectEvent event) {
        lastConnection = new ObjectObjectImmutablePair<>(event.getAddress(), event.getInfo());
    }

    public float getTickRate() {
        if (mc.player == null) return 0;
        if (System.currentTimeMillis() - timeJoined < 4000) return 20;

        int ticks = 0;
        float tickRates = 0.0f;

        for (float tickRate : this.tickRates) {
            if (tickRate > 0) {
                tickRates += tickRate;
                ticks++;
            }
        }

        return tickRates / ticks;
    }

    public int getPingDelay() {
        return (int) (getPing() / 25.0f);
    }

    public int getPing() {
        if (EUClient.MODULE_MANAGER.getModule(FastLatencyModule.class).isToggled()) {
            return EUClient.MODULE_MANAGER.getModule(FastLatencyModule.class).getLatency();
        }

        if (mc.getConnection() == null || mc.player == null) return 0;

        PlayerInfo entry = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        if (entry != null) return entry.getLatency();

        // Fallback: when connected via PingBypass proxy, the player list entry
        // might be under the proxy's UUID. Find our own entry by name or use
        // the first entry with non-zero latency.
        if (eu.client.pingbypass.PingBypassFlags.proxyForwardingActive) {
            for (PlayerInfo e : mc.getConnection().getOnlinePlayers()) {
                if (e.getProfile().name().equals(mc.player.getName().getString())) {
                    return e.getLatency();
                }
            }
            // Last resort: return the first entry's latency
            for (PlayerInfo e : mc.getConnection().getOnlinePlayers()) {
                if (e.getLatency() > 0) return e.getLatency();
            }
        }

        return 0;
    }

    public String getServerBrand() {
        if (mc.getCurrentServer() == null || mc.getConnection() == null || mc.getConnection().serverBrand() == null) return "Vanilla";
        return mc.getConnection().serverBrand();
    }

    public String getServer() {
        return mc.hasSingleplayerServer() ? "Singleplayer" : ServerAddress.parseString(mc.getCurrentServer().ip).getHost();
    }
}
