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
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
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

    // Cells that a block-placement module (SelfFill/Surround/...) wants to place in THIS tick.
    // AutoCrystal reads this before picking a crystal spot so it stops re-filling a position
    // another module is actively trying to place a block into -- without this, AutoCrystal wins
    // every tick (PlayerUpdateEvent fires before its UpdateMovementEvent.Post placement) and the
    // two modules whack-a-mole the same cell forever. Cleared and repopulated every tick.
    private final Set<BlockPos> reservedPlacements = ConcurrentHashMap.newKeySet();

    public void reservePlacement(BlockPos pos) {
        reservedPlacements.add(pos);
    }

    public boolean isReserved(BlockPos pos) {
        return reservedPlacements.contains(pos);
    }

    public Set<BlockPos> getReservedPlacements() {
        return reservedPlacements;
    }

    // Instant retry on confirmed crystal death, keyed by the attacked crystal's entity id --
    // waiting for the caller's own next tick (PlayerUpdateEvent, once per client tick) to retry a
    // blocked placement is still a full tick of dead time. This fires the moment the SERVER
    // confirms the crystal is actually gone (ClientboundRemoveEntitiesPacket), which is the
    // earliest possible signal short of predicting it -- shrinks the race window against an enemy
    // re-placing a crystal on the same cell (crystal-aura-style) to whatever's left of that RTT
    // instead of a full extra client tick, though it still can't outright WIN that race if their
    // ping is lower: both sides are bounded by their own round-trip to the server either way.
    private final Map<Integer, Runnable> pendingCrystalRetries = new ConcurrentHashMap<>();

    public void onCrystalAttacked(int entityId, Runnable retry) {
        pendingCrystalRetries.put(entityId, retry);
    }

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
        // Cleared here (fires at Minecraft.tick() HEAD, before PlayerUpdateEvent/UpdateMovementEvent
        // both fire from the player entity's own tick later this same tick) so each tick starts
        // fresh and a reservation from N ticks ago can't linger and block a cell forever.
        reservedPlacements.clear();

        // Silent switches hold the server's held slot open across a burst now; this lands the
        // restore once the burst stops. See InventoryUtils.tickPendingRestore.
        eu.client.utils.minecraft.InventoryUtils.tickPendingRestore();

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

        // 1.21.2 added this S2C packet as a real server-authoritative sync for the held slot --
        // .mcref confirms ClientPacketListener.handleSetHeldSlot unconditionally calls
        // mc.player.getInventory().setSelectedSlot(packet.slot()). Every "Silent"-style switch in
        // this project (SpeedMine/AutoCrystal/Surround/SelfTrap...) sends a raw
        // ServerboundSetCarriedItemPacket to move the SERVER's held item without ever touching
        // mc.player locally -- on 1.20.x that was fire-and-forget, no echo. On 1.21.2+ the server
        // now echoes the resulting slot straight back, and vanilla's own packet handler applies it
        // unconditionally, defeating every "don't show this switch to the player" fix at the
        // source (not fixable by gating our own setSelectedSlot() calls -- this one is vanilla's,
        // not ours). Confirmed live: no flicker with ViaFabric downgraded to 1.20.x, flickers on
        // 1.21.2+. The only time the server's authoritative slot should legitimately differ from
        // what's currently on screen is when WE just silently changed it out from under our own
        // display -- so drop it whenever it doesn't match what the player is already looking at;
        // a packet that agrees with the current display is a no-op to apply or drop either way.
        if (event.getPacket() instanceof ClientboundSetHeldSlotPacket packet && mc.player != null
                && packet.slot() != mc.player.getInventory().getSelectedSlot()) {
            event.setCancelled(true);
            return;
        }

        if (event.getPacket() instanceof ClientboundEntityEventPacket packet && packet.getEventId() == 35) {
            if (!(packet.getEntity(mc.level) instanceof Player player)) return;

            int pops = poppedTotems.getOrDefault(player.getUUID(), 0);
            poppedTotems.put(player.getUUID(), ++pops);

            EUClient.EVENT_HANDLER.post(new PlayerPopEvent(player, pops));
        }

        if (!pendingCrystalRetries.isEmpty() && event.getPacket() instanceof ClientboundRemoveEntitiesPacket removePacket) {
            for (int id : removePacket.getEntityIds()) {
                Runnable retry = pendingCrystalRetries.remove(id);
                if (retry != null) retry.run();
            }
        }
    }

    public int getRadius(double radius) {
        return INDICES[Mth.clamp((int) Math.ceil(radius), 0, INDICES.length)];
    }

    public Vec3i getOffset(int index) {
        return SPHERE[index];
    }
}
