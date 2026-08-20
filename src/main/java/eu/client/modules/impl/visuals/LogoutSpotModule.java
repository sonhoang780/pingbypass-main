package eu.client.modules.impl.visuals;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.*;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.modules.impl.miscellaneous.FakePlayerModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ColorSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.graphics.Renderer3D;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.text.DecimalFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RegisterModule(name = "LogoutSpot", description = "Renders a frozen chams model and nametag where players logged out.", category = Module.Category.VISUALS)
public class LogoutSpotModule extends Module {
    public ModeSetting mode = new ModeSetting("Mode", "The rendering that will be applied to the logout chams.", "Both", new String[]{"Fill", "Outline", "Both"});
    public ColorSetting fillColor = new ColorSetting("FillColor", "The color used for the fill rendering.", new ModeSetting.Visibility(mode, "Fill", "Both"), ColorUtils.getDefaultFillColor());
    public ColorSetting outlineColor = new ColorSetting("OutlineColor", "The color used for the outline rendering.", new ModeSetting.Visibility(mode, "Outline", "Both"), ColorUtils.getDefaultOutlineColor());

    public BooleanSetting nametag = new BooleanSetting("Nametag", "Renders player info above the logout spot.", true);
    public BooleanSetting health = new BooleanSetting("Health", "Renders the health of the logged out player.", true);
    public BooleanSetting totemPops = new BooleanSetting("TotemPops", "Renders the popped totems count of the logged out player.", true);
    public NumberSetting scale = new NumberSetting("Scale", "The scaling applied to the nametag rendering.", 30, 10, 100);

    private static final EquipmentSlot[] COPIED_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private final Map<UUID, Spot> spots = new ConcurrentHashMap<>();
    private final Map<RemotePlayer, Spot> ghosts = new ConcurrentHashMap<>();
    private final Map<UUID, TrackedPlayer> trackedPlayers = new ConcurrentHashMap<>();

    private int nextId = -80000;

    // Reported: "hoạt động xuyên dimension" -- PlayerDisconnectEvent fires off
    // ClientboundPlayerInfoRemovePacket (tab-list removal, see ServerManager#handleConnections),
    // NOT an actual disconnect. Velocity/Bungee-style networked servers (crash log: "Server brand:
    // Crystal (Velocity)") commonly remove-then-re-add a player's tab entry across a dimension/
    // sub-server hop -- indistinguishable from a real logout at the packet level. Treating every
    // one of those as a genuine disconnect spawned a ghost for a player who never actually left,
    // and since they reappear under the SAME uuid almost immediately, onPlayerConnect's cleanup
    // raced it -- crash log showed several still-connected players present as BOTH their real
    // entity and a leftover ghost simultaneously. Debounce instead: don't create the ghost the
    // instant the disconnect event fires -- record it and only actually spawn it after
    // DISCONNECT_DEBOUNCE_MS has passed with no matching reconnect. A genuine reconnect (or a
    // same-tick dimension-hop tab flicker) cancels the pending entry outright in onPlayerConnect,
    // so no ghost is ever created for it at all.
    private static final long DISCONNECT_DEBOUNCE_MS = 1500L;
    private final Map<UUID, Long> pendingDisconnects = new ConcurrentHashMap<>();

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.level == null || mc.player == null) return;

        FakePlayerModule fakePlayer = EUClient.MODULE_MANAGER != null ? EUClient.MODULE_MANAGER.getModule(FakePlayerModule.class) : null;
        Player fake = fakePlayer != null && fakePlayer.isToggled() ? fakePlayer.getPlayer() : null;

        // Defensive copy -- mc.level.players() is the level's own live list; a ghost getting
        // added/removed (mc.level.addEntity/removeEntity, both same-thread but not necessarily
        // outside this exact call frame depending on event ordering) while this loop iterates it
        // directly throws ConcurrentModificationException (crash log: LogoutSpotModule.java:61).
        for (Player player : new java.util.ArrayList<>(mc.level.players())) {
            if (player == mc.player || player == fake || isGhost(player)) continue;
            if (!player.isAlive()) continue;

            UUID uuid = player.getUUID();

            // Real entity is back (or never actually left) -- cancel any pending ghost-spawn and
            // clean up an existing one, same as onPlayerConnect below.
            pendingDisconnects.remove(uuid);
            if (spots.containsKey(uuid)) {
                removeSpot(uuid);
            }

            int pops = EUClient.WORLD_MANAGER != null ? EUClient.WORLD_MANAGER.getPoppedTotems().getOrDefault(uuid, 0) : 0;
            trackedPlayers.put(uuid, new TrackedPlayer(player, pops));
        }

        if (!pendingDisconnects.isEmpty()) {
            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, Long> entry : pendingDisconnects.entrySet()) {
                if (now - entry.getValue() < DISCONNECT_DEBOUNCE_MS) continue;
                pendingDisconnects.remove(entry.getKey());
                spawnGhost(entry.getKey());
            }
        }
    }

    @SubscribeEvent
    public void onPlayerDisconnect(PlayerDisconnectEvent event) {
        if (!isToggled() || mc.level == null || mc.player == null) return;

        UUID id = event.getId();
        if (id == null || id.equals(mc.player.getUUID())) return;

        FakePlayerModule fakePlayer = EUClient.MODULE_MANAGER != null ? EUClient.MODULE_MANAGER.getModule(FakePlayerModule.class) : null;
        if (fakePlayer != null && fakePlayer.isToggled() && fakePlayer.getPlayer() != null && id.equals(fakePlayer.getPlayer().getUUID())) return;

        if (!trackedPlayers.containsKey(id)) return;

        pendingDisconnects.put(id, System.currentTimeMillis());
    }

    // Actually spawns the ghost -- called from onTick once DISCONNECT_DEBOUNCE_MS has passed with
    // no reconnect. Reads trackedPlayers fresh (not a value captured back at the original disconnect
    // event) so the ghost freezes at the most recent state we actually saw, not a tick-stale one.
    private void spawnGhost(UUID id) {
        TrackedPlayer tracked = trackedPlayers.get(id);
        if (tracked == null) return;

        mc.execute(() -> {
            if (mc.level == null || spots.containsKey(id)) return;

            GameProfile profile = new GameProfile(UUID.randomUUID(), tracked.name);
            RemotePlayer ghost = new RemotePlayer(mc.level, profile);
            ghost.setId(nextId--);

            ghost.setPos(tracked.x, tracked.y, tracked.z);
            ghost.xo = tracked.xo;
            ghost.yo = tracked.yo;
            ghost.zo = tracked.zo;
            ghost.xOld = tracked.x;
            ghost.yOld = tracked.y;
            ghost.zOld = tracked.z;

            ghost.setYRot(tracked.yRot);
            ghost.setXRot(tracked.xRot);
            ghost.yRotO = tracked.yRot;
            ghost.xRotO = tracked.xRot;
            ghost.setYHeadRot(tracked.yHeadRot);
            ghost.yHeadRotO = tracked.yHeadRot;
            ghost.yBodyRot = tracked.yBodyRot;
            ghost.yBodyRotO = tracked.yBodyRot;

            ghost.setPose(tracked.pose);
            ghost.setShiftKeyDown(tracked.shift);
            ghost.setSwimming(tracked.swim);
            ghost.setSprinting(tracked.sprint);
            ghost.refreshDimensions();

            ghost.walkAnimation.setSpeed(tracked.walkSpeed);
            ((eu.client.mixins.accessors.LimbAnimatorAccessor) ghost.walkAnimation).setPos(tracked.walkPos);

            ghost.swinging = tracked.swinging;
            ghost.swingTime = tracked.swingTime;
            ghost.swingingArm = tracked.swingingArm;
            ghost.attackAnim = tracked.attackAnim;
            ghost.oAttackAnim = tracked.oAttackAnim;

            // Reported: "vẫn cầm item mà họ cầm trước khi log out, không chỉ riêng totem" -- the
            // ghost is a frozen snapshot of where/how the player stood, not a re-simulation of
            // them, so a held item (totem, sword, whatever) just sits there statically forever --
            // reads as a stuck/broken pose rather than an accurate freeze-frame. Hands empty
            // instead; armor (below) still copies since that's a genuinely static visual (what
            // they were wearing), unlike a held item mid-action.
            ghost.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            ghost.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            for (EquipmentSlot slot : COPIED_SLOTS) {
                ghost.setItemSlot(slot, tracked.armor.getOrDefault(slot, ItemStack.EMPTY).copy());
            }

            mc.level.addEntity(ghost);

            Spot spot = new Spot(ghost, tracked);
            spots.put(id, spot);
            ghosts.put(ghost, spot);
        });
    }

    @SubscribeEvent
    public void onPlayerConnect(PlayerConnectEvent event) {
        UUID id = event.getId();
        if (id == null) return;

        // Cancel a debounced disconnect outright -- see the field's own doc. Most reconnects on a
        // networked server are exactly this: a dimension/sub-server hop's tab-list flicker, so the
        // ghost this would have spawned never gets created at all.
        pendingDisconnects.remove(id);

        if (spots.containsKey(id)) {
            mc.execute(() -> removeSpot(id));
        }
    }

    @SubscribeEvent
    public void onClientDisconnect(ClientDisconnectEvent event) {
        clearAll();
    }

    @SubscribeEvent
    public void onServerConnect(ServerConnectEvent event) {
        clearAll();
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent.Post event) {
        if (!isToggled() || mc.level == null || mc.player == null) return;
        if (!nametag.getValue() || spots.isEmpty()) return;

        PoseStack matrices = event.getMatrices();
        MultiBufferSource.BufferSource vertexConsumers = mc.renderBuffers().bufferSource();

        for (Spot spot : spots.values()) {
            RemotePlayer ghost = spot.ghost;
            if (ghost == null) continue;

            double x = ghost.getX();
            double y = ghost.getY() + (ghost.isShiftKeyDown() ? 1.9f : 2.1f);
            double z = ghost.getZ();

            Vec3 vec3d = new Vec3(
                    x - mc.getEntityRenderDispatcher().camera.position().x,
                    y - mc.getEntityRenderDispatcher().camera.position().y,
                    z - mc.getEntityRenderDispatcher().camera.position().z
            );

            float distance = (float) Math.sqrt(mc.getEntityRenderDispatcher().camera.position().distanceToSqr(x, y, z));
            float scaling = 0.0018f + (scale.getValue().intValue() / 10000.0f) * distance;
            if (distance <= 8.0f) scaling = 0.0245f;

            matrices.pushPose();
            matrices.translate(vec3d.x, vec3d.y, vec3d.z);
            matrices.mulPose(mc.getEntityRenderDispatcher().camera.rotation());
            matrices.scale(scaling, -scaling, scaling);

            // Was: per-segment ChatFormatting colors (health green->red gradient, totem-count
            // color) baked into the string, overriding whatever base color the draw call below
            // used regardless. Requested: sync everything to the same FillColor the ghost model
            // itself renders with -- dropped the embedded color codes so the single base color
            // passed to drawTextWithShadow (now fillColor.getColor() instead of Color.WHITE)
            // applies uniformly across name/health/totem-pops.
            StringBuilder text = new StringBuilder(spot.data.name);
            if (health.getValue()) {
                float totalHp = spot.data.health + spot.data.absorption;
                text.append(" ").append(new DecimalFormat("0.0").format(totalHp));
            }
            if (totemPops.getValue() && spot.data.totemPops > 0) {
                text.append(" -").append(spot.data.totemPops);
            }

            String fullText = text.toString();
            int width = EUClient.FONT_MANAGER.getWidth(fullText);
            int fontHeight = EUClient.FONT_MANAGER.getHeight();

            Renderer3D.renderQuad(matrices, -width / 2.0f - 2, -fontHeight - 1, width / 2.0f + 2, 1, new Color(0, 0, 0, 120));
            Renderer3D.renderOutline(matrices, -width / 2.0f - 2, -fontHeight - 1, width / 2.0f + 2, 1, new Color(0, 0, 0, 160));

            EUClient.FONT_MANAGER.drawTextWithShadow(matrices, fullText, -width / 2, -fontHeight, vertexConsumers, fillColor.getColor());

            matrices.popPose();

            Renderer3D.draw(Renderer3D.QUADS, Renderer3D.DEBUG_LINES, false);
            Renderer3D.QUADS.clear();
            Renderer3D.DEBUG_LINES.clear();
            vertexConsumers.endBatch();
        }
    }

    @Override
    public void onDisable() {
        clearAll();
    }

    private void removeSpot(UUID id) {
        Spot spot = spots.remove(id);
        if (spot != null) {
            ghosts.remove(spot.ghost);
            despawn(spot.ghost);
        }
    }

    private void clearAll() {
        spots.values().forEach(s -> despawn(s.ghost));
        spots.clear();
        ghosts.clear();
        trackedPlayers.clear();
        pendingDisconnects.clear();
    }

    private void despawn(RemotePlayer ghost) {
        if (mc.level != null && ghost != null) {
            mc.level.removeEntity(ghost.getId(), Entity.RemovalReason.DISCARDED);
        }
    }

    public boolean isGhost(Entity entity) {
        return entity instanceof RemotePlayer && ghosts.containsKey(entity);
    }

    public java.util.Collection<RemotePlayer> getGhosts() {
        return ghosts.keySet();
    }

    public Spot getSpot(RemotePlayer ghost) {
        return ghosts.get(ghost);
    }

    public boolean shouldFill() {
        return mode.getValue().equals("Fill") || mode.getValue().equals("Both");
    }

    public boolean shouldOutline() {
        return mode.getValue().equals("Outline") || mode.getValue().equals("Both");
    }

    public Color getFillColor(RemotePlayer ghost) {
        return fillColor.getColor();
    }

    public Color getOutlineColor(RemotePlayer ghost) {
        return outlineColor.getColor();
    }

    public static class Spot {
        public final RemotePlayer ghost;
        public final TrackedPlayer data;
        public final long timestamp;

        public Spot(RemotePlayer ghost, TrackedPlayer data) {
            this.ghost = ghost;
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public static class TrackedPlayer {
        public final String name;
        public final double x, y, z, xo, yo, zo;
        public final float yRot, xRot, yHeadRot, yBodyRot;
        public final Pose pose;
        public final boolean shift, swim, sprint;
        public final float walkSpeed, walkPos;
        public final boolean swinging;
        public final int swingTime;
        public final net.minecraft.world.InteractionHand swingingArm;
        public final float attackAnim, oAttackAnim;
        public final ItemStack mainHand, offHand;
        public final Map<EquipmentSlot, ItemStack> armor = new ConcurrentHashMap<>();
        public final float health, absorption;
        public final int totemPops;

        public TrackedPlayer(Player player, int totemPops) {
            this.name = player.getName().getString();
            this.x = player.getX();
            this.y = player.getY();
            this.z = player.getZ();
            this.xo = player.xo;
            this.yo = player.yo;
            this.zo = player.zo;

            this.yRot = player.getYRot();
            this.xRot = player.getXRot();
            this.yHeadRot = player.getYHeadRot();
            this.yBodyRot = player.yBodyRot;

            this.pose = player.getPose();
            this.shift = player.isShiftKeyDown();
            this.swim = player.isSwimming();
            this.sprint = player.isSprinting();

            this.walkSpeed = player.walkAnimation.speed();
            this.walkPos = player.walkAnimation.position();

            this.swinging = player.swinging;
            this.swingTime = player.swingTime;
            this.swingingArm = player.swingingArm;
            this.attackAnim = player.attackAnim;
            this.oAttackAnim = player.oAttackAnim;

            this.mainHand = player.getMainHandItem().copy();
            this.offHand = player.getOffhandItem().copy();
            for (EquipmentSlot slot : COPIED_SLOTS) {
                this.armor.put(slot, player.getItemBySlot(slot).copy());
            }

            this.health = player.getHealth();
            this.absorption = player.getAbsorptionAmount();
            this.totemPops = totemPops;
        }
    }
}
