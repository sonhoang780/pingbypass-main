package eu.client.modules.impl.visuals;

import com.mojang.authlib.GameProfile;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerPopEvent;
import eu.client.events.impl.TickEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.ColorSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.animations.Easing;
import eu.client.utils.color.ColorUtils;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// Rewritten (2026-08-08) to render a FROZEN SNAPSHOT of the popper's pose at the moment they
// popped, not a recolor of their live/moving entity -- explicitly requested: PopChams "chỉ vẽ theo
// pose tại thời điểm player pop totem". Riding the live player's own IChamsCapture (previous
// version) also meant PopChams and ChamsModule fought over the SAME single fill+outline slot on
// the SAME entity render state whenever both applied to the popper, one always stomping the other.
// Spawning a separate, real, client-side ghost entity (same RemotePlayer-clone pattern
// FakePlayerCommand already uses) sidesteps both problems at once: it's frozen because nothing
// ever updates its position/pose after spawn, and it's a genuinely different Entity with its own
// EntityRenderState, so Chams (on the still-alive real player) and PopChams (on this ghost) render
// fully independently -- no shared slot, no priority needed.
@RegisterModule(name = "PopChams", description = "Renders chams on a frozen snapshot of the entity's pose the moment they pop a totem.", category = Module.Category.VISUALS)
public class PopChamsModule extends Module {
    public NumberSetting duration = new NumberSetting("Duration", "The duration for the pop chams fade.", 1500, 0, 5000);
    public ModeSetting mode = new ModeSetting("Mode", "The rendering that will be applied to the pop chams.", "Both", new String[]{"Fill", "Outline", "Both"});
    public ColorSetting fillColor = new ColorSetting("FillColor", "The color used for the fill rendering.", new ModeSetting.Visibility(mode, "Fill", "Both"), ColorUtils.getDefaultFillColor());
    public ColorSetting outlineColor = new ColorSetting("OutlineColor", "The color used for the outline rendering.", new ModeSetting.Visibility(mode, "Outline", "Both"), ColorUtils.getDefaultOutlineColor());

    private static final EquipmentSlot[] COPIED_SLOTS = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private final Map<RemotePlayer, Long> ghosts = new HashMap<>();
    // Own negative-ID range, well clear of FakePlayerCommand's (-13337 and counting down) so a
    // pop-ghost and a manually spawned fake player can never collide on the same client-side ID.
    private int nextId = -90000;

    @SubscribeEvent
    public void onPlayerPop(PlayerPopEvent event) {
        if (!isToggled() || mc.level == null || event.getPlayer() == mc.player) return;

        Player player = event.getPlayer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), player.getName().getString());
        RemotePlayer ghost = new RemotePlayer(mc.level, profile);
        ghost.setId(nextId--);
        ghost.copyPosition(player);
        ghost.setYHeadRot(player.getYHeadRot());
        ghost.setPose(player.getPose());
        ghost.refreshDimensions();
        for (EquipmentSlot slot : COPIED_SLOTS) {
            ghost.setItemSlot(slot, player.getItemBySlot(slot).copy());
        }

        mc.level.addEntity(ghost);
        ghosts.put(ghost, System.currentTimeMillis());
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        long now = System.currentTimeMillis();
        ghosts.entrySet().removeIf(entry -> {
            if (now - entry.getValue() <= duration.getValue().intValue()) return false;
            despawn(entry.getKey());
            return true;
        });
    }

    @Override
    public void onDisable() {
        ghosts.keySet().forEach(this::despawn);
        ghosts.clear();
    }

    private void despawn(RemotePlayer ghost) {
        if (mc.level != null) mc.level.removeEntity(ghost.getId(), Entity.RemovalReason.DISCARDED);
    }

    public boolean isGhost(Entity entity) {
        return ghosts.containsKey(entity);
    }

    public boolean shouldFill() {
        return mode.getValue().equals("Fill") || mode.getValue().equals("Both");
    }

    public boolean shouldOutline() {
        return mode.getValue().equals("Outline") || mode.getValue().equals("Both");
    }

    public Color getFillColor(RemotePlayer ghost) {
        return withFade(fillColor.getColor(), ghost);
    }

    public Color getOutlineColor(RemotePlayer ghost) {
        return withFade(outlineColor.getColor(), ghost);
    }

    private Color withFade(Color color, RemotePlayer ghost) {
        Long startTime = ghosts.get(ghost);
        if (startTime == null) return color;
        float ease = 1.0f - Easing.toDelta(startTime, duration.getValue().intValue());
        return ColorUtils.getColor(color, (int) (color.getAlpha() * ease));
    }
}
