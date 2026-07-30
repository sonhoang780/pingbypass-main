package eu.client.modules.impl.visuals;

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
import net.minecraft.world.entity.player.Player;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// ponytail: rewritten to piggyback on the live player entity's own outline-color slot
// (see EntityRendererMixin.euclient$chams / ChamsModule's class doc) instead of manually
// re-rendering a synthetic StaticPlayerEntity snapshot -- the popped player is still a
// real world entity after saving with a totem, so there is no need to build a standalone
// render state for it anymore.
@RegisterModule(name = "PopChams", description = "Renders chams when an entity pops a totem.", category = Module.Category.VISUALS)
public class PopChamsModule extends Module {
    public NumberSetting duration = new NumberSetting("Duration", "The duration for the pop chams fade.", 1500, 0, 5000);
    public ModeSetting mode = new ModeSetting("Mode", "The rendering that will be applied to the pop chams.", "Both", new String[]{"Fill", "Outline", "Both"});
    public ColorSetting fillColor = new ColorSetting("FillColor", "The color used for the fill rendering.", new ModeSetting.Visibility(mode, "Fill", "Both"), ColorUtils.getDefaultFillColor());
    public ColorSetting outlineColor = new ColorSetting("OutlineColor", "The color used for the outline rendering.", new ModeSetting.Visibility(mode, "Outline", "Both"), ColorUtils.getDefaultOutlineColor());

    private final Map<UUID, Long> poppedPlayers = new HashMap<>();

    @SubscribeEvent
    public void onPlayerPop(PlayerPopEvent event) {
        if (event.getPlayer() == mc.player) return;
        poppedPlayers.put(event.getPlayer().getUUID(), System.currentTimeMillis());
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        poppedPlayers.entrySet().removeIf(entry -> System.currentTimeMillis() - entry.getValue() > duration.getValue().intValue());
    }

    public boolean isActive(Player player) {
        return isToggled() && poppedPlayers.containsKey(player.getUUID());
    }

    public Color getColor(Player player) {
        long startTime = poppedPlayers.get(player.getUUID());
        float ease = 1.0f - Easing.toDelta(startTime, duration.getValue().intValue());
        boolean fill = mode.getValue().equals("Fill") || mode.getValue().equals("Both");
        Color color = fill ? fillColor.getColor() : outlineColor.getColor();
        return ColorUtils.getColor(color, (int) (color.getAlpha() * ease));
    }
}
