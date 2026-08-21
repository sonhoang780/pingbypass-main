package eu.client.modules.impl.visuals;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.RenderWorldEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ColorSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.graphics.Renderer3D;
import eu.client.utils.minecraft.EntityUtils;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.player.Player;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.awt.*;

@RegisterModule(name = "Tracers", description = "Renders a line showing where other players are located.", category = Module.Category.VISUALS)
public class TracersModule extends Module {
    public BooleanSetting antiBot = new BooleanSetting("AntiBot", "Prevents bots from having arrow tracers rendered for them.", false);
    public ModeSetting mode = new ModeSetting("Mode", "The mode for the tracers color.", "Distance", new String[]{"Distance", "Custom"});
    public ColorSetting color = new ColorSetting("Color", "The color used for the fill rendering.", ColorUtils.getDefaultOutlineColor());

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        if(getNull()) return;

        if (mc.player == null) {
            return;
        }
        boolean prevBobView = mc.options.bobView().get();
        mc.options.bobView().set(false);

        Vec3 pos = EntityUtils.getRenderPos(mc.player, event.getTickDelta());
        Camera camera = mc.gameRenderer.mainCamera();
        Vec3 cameraPos = new Vec3(0.0, 0.0, 1.0).xRot(-(float) Math.toRadians(camera.xRot())).yRot(-(float) Math.toRadians(camera.yRot())).add(pos.x, mc.player.getEyeHeight(mc.player.getPose()) + pos.y, pos.z);

        for(Player player : mc.level.players()) {
            if (player == mc.player) continue;
            // LogoutSpot ghosts are real RemotePlayer entities added to mc.level (see
            // PopChamsModule's despawn()/mc.level.removeEntity for the matching remove side), so
            // this naive mc.level.players() loop picked them up like any other player -- reported
            // "Tracer vẫn áp tracer lên LogoutSpot". SpeedMine/AutoCrystal already filter these via
            // EntityUtils.isGhost() before iterating; this loop never did.
            if (EntityUtils.isGhost(player)) continue;
            if (EntityUtils.isBot(player) && antiBot.getValue()) continue;

            Vec3 playerPos = EntityUtils.getRenderPos(player, event.getTickDelta());
            Renderer3D.renderLine(event.getMatrices(), cameraPos, playerPos, getColor(player));
        }

        mc.options.bobView().set(prevBobView);
    }

    private Color getColor(Player player) {
        if(EUClient.FRIEND_MANAGER.contains(player.getName().getString())) return EUClient.FRIEND_MANAGER.getDefaultFriendColor(color.getColor().getAlpha());

        if(mode.getValue().equals("Custom")) return color.getColor();

        float maxDistance = 80;
        float distance = Mth.clamp(mc.player.distanceTo(player), 0, maxDistance);
        return new Color(((maxDistance - distance) / maxDistance), 1.0f - (maxDistance - distance) / (float) maxDistance, 0, color.getColor().getAlpha()/255f);
    }
}
