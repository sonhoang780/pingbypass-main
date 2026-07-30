package eu.client.modules.impl.visuals;

import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PacketReceiveEvent;
import eu.client.events.impl.TickEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.phys.Vec3;

import java.util.*;

@SuppressWarnings("unused")
@RegisterModule(name = "DeathEffects", description = "Renders certain effects on players when they die or disconnect.", category = Module.Category.VISUALS)
public class DeathEffectsModule extends Module {
    public ModeSetting mode = new ModeSetting("Mode", "The effect that will be rendered.", "Both", new String[]{"Lightning", "Overlay", "Both"});

    public BooleanSetting lightningOnKill = new BooleanSetting("LightningOnKill", "Spawn lightning bolt when a player dies near you.", true);
    public BooleanSetting lightningOnDisconnect = new BooleanSetting("LightningOnDisconnect", "Spawn lightning bolt when a player disconnects near you.", true);
    public NumberSetting disconnectRange = new NumberSetting("DisconnectRange", "Range to detect player disconnects.", 64.0, 1.0, 256.0);

    public BooleanSetting overlayOnKill = new BooleanSetting("OverlayOnKill", "Show freeze overlay when a player dies near you.", true);
    public BooleanSetting overlayOnDisconnect = new BooleanSetting("OverlayOnDisconnect", "Show freeze overlay when a player disconnects near you.", true);
    public NumberSetting overlayDuration = new NumberSetting("OverlayDuration", "Duration of the freeze overlay in milliseconds.", 3000, 100, 10000);
    public NumberSetting overlayFadeDuration = new NumberSetting("OverlayFadeDuration", "Fade duration of the freeze overlay in milliseconds.", 2000, 100, 5000);
    public NumberSetting overlayAlpha = new NumberSetting("OverlayAlpha", "Transparency of the freeze overlay (0-255, higher = more transparent).", 200, 0, 255);

    private final Map<UUID, Vec3> lastPlayerPositions = new HashMap<>();
    private final Set<UUID> trackedPlayers = new HashSet<>();

    private long freezeEndTime = 0;
    private boolean frostActive = false;

    @Override
    public void onEnable() {
        lastPlayerPositions.clear();
        trackedPlayers.clear();
        freezeEndTime = 0;
        frostActive = false;
    }

    @Override
    public void onDisable() {
        if (mc.player != null) {
            mc.player.setTicksFrozen(0);
        }
        frostActive = false;
        freezeEndTime = 0;
        lastPlayerPositions.clear();
        trackedPlayers.clear();
    }

    @SubscribeEvent
    public void onPacketReceive(PacketReceiveEvent event) {
        if (mc.level == null || mc.player == null) return;

        if (event.getPacket() instanceof ClientboundEntityEventPacket packet) {
            if (packet.getEventId() == 3) { // Death status
                try {
                    net.minecraft.world.entity.Entity entity = packet.getEntity(mc.level);
                    if (entity instanceof Player player && player != mc.player) {
                        double distance = mc.player.distanceToSqr(player);
                        if (distance <= 100) { // 10 block range
                            if ((mode.getValue().equals("Lightning") || mode.getValue().equals("Both")) && lightningOnKill.getValue()) {
                                spawnLightning(new Vec3(player.getX(), player.getY(), player.getZ()));
                            }
                            if ((mode.getValue().equals("Overlay") || mode.getValue().equals("Both")) && overlayOnKill.getValue()) {
                                startOverlay();
                            }
                        }
                    }
                } catch (Exception e) {
                    // Silently fail
                }
            }
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null) return;

        updateFrostEffect();

        Set<UUID> currentPlayerUuids = new HashSet<>();
        for (Player player : mc.level.players()) {
            if (player == mc.player) continue;
            currentPlayerUuids.add(player.getUUID());
            lastPlayerPositions.put(player.getUUID(), new Vec3(player.getX(), player.getY(), player.getZ()));
        }

        for (UUID uuid : new HashSet<>(trackedPlayers)) {
            if (!currentPlayerUuids.contains(uuid)) {
                if (lastPlayerPositions.containsKey(uuid)) {
                    Vec3 lastPos = lastPlayerPositions.get(uuid);
                    double distance = Math.sqrt(
                        Math.pow(mc.player.getX() - lastPos.x, 2) +
                        Math.pow(mc.player.getY() - lastPos.y, 2) +
                        Math.pow(mc.player.getZ() - lastPos.z, 2)
                    );

                    if (distance <= disconnectRange.getValue().doubleValue()) {
                        if ((mode.getValue().equals("Lightning") || mode.getValue().equals("Both")) && lightningOnDisconnect.getValue()) {
                            spawnLightning(lastPos);
                        }
                        if ((mode.getValue().equals("Overlay") || mode.getValue().equals("Both")) && overlayOnDisconnect.getValue()) {
                            startOverlay();
                        }
                    }
                    lastPlayerPositions.remove(uuid);
                }
            }
        }

        trackedPlayers.clear();
        trackedPlayers.addAll(currentPlayerUuids);
    }

    private void updateFrostEffect() {
        if (frostActive && mc.player != null) {
            long currentTime = System.currentTimeMillis();
            long elapsed = currentTime - (freezeEndTime - overlayDuration.getValue().longValue() - overlayFadeDuration.getValue().longValue());
            long totalDuration = overlayDuration.getValue().longValue() + overlayFadeDuration.getValue().longValue();

            if (elapsed < overlayDuration.getValue().longValue()) {
                int frozenTicks = (int) (140.0 * (overlayAlpha.getValue().doubleValue() / 255.0));
                mc.player.setTicksFrozen(frozenTicks);
            } else if (elapsed < totalDuration) {
                long fadeElapsed = elapsed - overlayDuration.getValue().longValue();
                double fadeProgress = 1.0 - ((double) fadeElapsed / overlayFadeDuration.getValue().longValue());
                int frozenTicks = (int) (140.0 * (overlayAlpha.getValue().doubleValue() / 255.0) * fadeProgress);
                mc.player.setTicksFrozen(frozenTicks);
            } else {
                frostActive = false;
                freezeEndTime = 0;
                mc.player.setTicksFrozen(0);
            }
        } else if (mc.player != null && mc.player.getTicksFrozen() > 0) {
            if (mc.level != null && !mc.level.getBlockState(mc.player.blockPosition()).is(Blocks.POWDER_SNOW)) {
                mc.player.setTicksFrozen(0);
            }
        }
    }

    private void startOverlay() {
        frostActive = true;
        freezeEndTime = System.currentTimeMillis();
    }

    private void spawnLightning(Vec3 pos) {
        if (mc.level == null) return;

        LightningBolt lightning = new LightningBolt(EntityType.LIGHTNING_BOLT, mc.level);
        lightning.setPos(pos.x, pos.y, pos.z);
        mc.level.addEntity(lightning);
    }
}

