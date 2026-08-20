package eu.client.modules.impl.visuals;

import eu.client.events.SubscribeEvent;
import eu.client.events.impl.RenderOverlayEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ColorSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.graphics.Renderer2D;
import eu.client.utils.graphics.Renderer3D;
import eu.client.utils.minecraft.EntityUtils;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.awt.*;

@RegisterModule(name = "ArrowTracers", description = "Renders arrows towards players who are off screen.", category = Module.Category.VISUALS)
public class ArrowTracersModule extends Module {
    public NumberSetting width = new NumberSetting("Width", "The width of the arrows being rendered.", 4.0f, 0.5f, 15.0f);
    public NumberSetting height = new NumberSetting("Height", "The height of the arrows being rendered.", 8.0f, 0.5f, 15.0f);
    public NumberSetting distance = new NumberSetting("Distance", "The distance that will be between the crosshair and the arrows.", 45, 5, 200);
    public BooleanSetting antiBot = new BooleanSetting("AntiBot", "Prevents bots from having arrow tracers rendered for them.", false);
    public BooleanSetting onScreen = new BooleanSetting("OnScreen", "Renders the arrow tracers even for players who are on screen.", false);

    public ModeSetting alpha = new ModeSetting("Alpha", "The alpha that will be used in the arrow rendering.", "Fade", new String[]{"Default", "Fade"});
    public NumberSetting fadeDistance = new NumberSetting("FadeDistance", "The distance at which the arrows will start fading.", new ModeSetting.Visibility(alpha, "Fade"), 100.0f, 10.0f, 200.0f);

    public ModeSetting mode = new ModeSetting("Mode", "The rendering that will be applied to the arrows", "Both", new String[]{"Fill", "Outline", "Both"});
    public ColorSetting fillColor = new ColorSetting("FillColor", "The color used for the fill rendering.", new ModeSetting.Visibility(mode, "Fill", "Both"), ColorUtils.getDefaultOutlineColor());
    public ColorSetting outlineColor = new ColorSetting("OutlineColor", "The color used for the outline rendering.", new ModeSetting.Visibility(mode, "Outline", "Both"), new ColorSetting.Color(new Color(0, 0, 0), true, false));

    @SubscribeEvent
    public void onRenderOverlay(RenderOverlayEvent event) {
        if (mc.player == null || mc.level == null) return;

        GuiGraphicsExtractor context = event.getContext();
        float cx = mc.getWindow().getGuiScaledWidth() / 2.0f;
        float cy = mc.getWindow().getGuiScaledHeight() / 2.0f;
        float dist = distance.getValue().floatValue();
        float arrowWidth = width.getValue().floatValue();
        float arrowHeight = height.getValue().floatValue();

        Vec3 pos = EntityUtils.getRenderPos(mc.player, event.getTickDelta());

        for (Player player : mc.level.players()) {
            if (player == mc.player) continue;
            // Same gap as TracersModule -- LogoutSpot ghosts are real level entities, not filtered
            // by a naive mc.level.players() loop.
            if (EntityUtils.isGhost(player)) continue;
            if (EntityUtils.isBot(player) && antiBot.getValue()) continue;
            if (!onScreen.getValue() && Renderer3D.isFrustumVisible(player.getBoundingBox())) continue;

            Vec3 playerPos = EntityUtils.getRenderPos(player, event.getTickDelta());

            double diffX = playerPos.x - pos.x;
            double diffZ = playerPos.z - pos.z;
            double targetYaw = Math.toDegrees(Math.atan2(diffZ, diffX)) - 90.0;
            double deltaYaw = Mth.wrapDegrees(targetYaw - mc.player.getYRot());

            int a = (int) Mth.clamp(255.0f - 255.0f / fadeDistance.getValue().floatValue() * mc.player.distanceTo(player), 80.0f, 255.0f);
            Color fill = this.alpha.getValue().equalsIgnoreCase("Fade") ? ColorUtils.getColor(fillColor.getColor(), a) : fillColor.getColor();
            Color outline = this.alpha.getValue().equalsIgnoreCase("Fade") ? ColorUtils.getColor(outlineColor.getColor(), a) : outlineColor.getColor();

            context.pose().pushMatrix();
            context.pose().translate(cx, cy);
            context.pose().rotate((float) Math.toRadians(deltaYaw));
            context.pose().translate(-cx, -cy);

            if (mode.getValue().equalsIgnoreCase("Fill") || mode.getValue().equalsIgnoreCase("Both")) {
                Renderer2D.renderArrow(context, cx, cy - dist, arrowWidth, arrowHeight, fill);
            }
            if (mode.getValue().equalsIgnoreCase("Outline") || mode.getValue().equalsIgnoreCase("Both")) {
                Renderer2D.renderArrowOutline(context, cx, cy - dist, arrowWidth, arrowHeight, outline);
            }

            context.pose().popMatrix();
        }
    }
}
