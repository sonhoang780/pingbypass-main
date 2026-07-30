package eu.client.managers;

import lombok.Getter;
import lombok.Setter;
import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.*;
import eu.client.utils.IMinecraft;
import eu.client.utils.system.Timer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.core.Vec3i;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WorldManager implements IMinecraft {
    private static final Vec3i[] SPHERE = new Vec3i[4187707];
    private static final int[] INDICES = new int[101];

    @Getter private final Map<UUID, Integer> poppedTotems = new ConcurrentHashMap<>();
    private final List<UUID> deadPlayers = new ArrayList<>();

    @Getter @Setter private float timerMultiplier = 1.0f;

    @Getter private final Timer placeTimer = new Timer();

    public WorldManager() {
        EUClient.EVENT_HANDLER.subscribe(this);

        BlockPos origin = BlockPos.ZERO;
        Set<BlockPos> positions = new TreeSet<>((o, p) -> {
            if (o.equals(p)) return 0;

            int compare = Double.compare(origin.distSqr(o), origin.distSqr(p));
            if (compare == 0) compare = Integer.compare(Math.abs(o.getX()) + Math.abs(o.getY()) + Math.abs(o.getZ()), Math.abs(p.getX()) + Math.abs(p.getY()) + Math.abs(p.getZ()));

            return compare == 0 ? 1 : compare;
        });

        for (int x = origin.getX() - 100; x <= origin.getX() + 100; x++) {
            for (int z = origin.getZ() - 100; z <= origin.getZ() + 100; z++) {
                for (int y = origin.getY() - 100; y < origin.getY() + 100; y++) {
                    double distance = (origin.getX() - x) * (origin.getX() - x) + (origin.getZ() - z) * (origin.getZ() - z) + (origin.getY() - y) * (origin.getY() - y);
                    if (distance < Mth.square(100)) positions.add(new BlockPos(x, y, z));
                }
            }
        }

        int i = 0;
        int currentDistance = 0;

        for (BlockPos position : positions) {
            if (Math.sqrt(origin.distSqr(position)) > currentDistance) INDICES[currentDistance++] = i;
            SPHERE[i++] = position;
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.level == null) return;

        for (UUID uuid : new ArrayList<>(poppedTotems.keySet())) {
            if (mc.level.players().stream().noneMatch(player -> player.getUUID().equals(uuid))) {
                poppedTotems.remove(uuid);
            }
        }

        for (Player player : mc.level.players()) {
            if (player.deathTime <= 0 && player.getHealth() > 0) {
                deadPlayers.remove(player.getUUID());
                continue;
            }

            if (deadPlayers.contains(player.getUUID())) continue;

            EUClient.EVENT_HANDLER.post(new PlayerDeathEvent(player));
            deadPlayers.add(player.getUUID());

            poppedTotems.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public void onPacketReceive(PacketReceiveEvent event) {
        if (mc.level == null) return;

        if (event.getPacket() instanceof ClientboundEntityEventPacket packet && packet.getEventId() == 35) {
            if (!(packet.getEntity(mc.level) instanceof Player player)) return;

            int pops = poppedTotems.getOrDefault(player.getUUID(), 0);
            poppedTotems.put(player.getUUID(), ++pops);

            EUClient.EVENT_HANDLER.post(new PlayerPopEvent(player, pops));
        }
    }

    public int getRadius(double radius) {
        return INDICES[Mth.clamp((int) Math.ceil(radius), 0, INDICES.length)];
    }

    public Vec3i getOffset(int index) {
        return SPHERE[index];
    }
}
