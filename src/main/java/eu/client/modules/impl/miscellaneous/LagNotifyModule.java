package eu.client.modules.impl.miscellaneous;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PacketReceiveEvent;
import eu.client.events.impl.RenderOverlayEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ColorSetting;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.system.MathUtils;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.phys.Vec3;

@RegisterModule(name = "LagNotify", description = "Notifies you when you lag.", category = Module.Category.MISCELLANEOUS)
public class LagNotifyModule extends Module {
    BooleanSetting server = new BooleanSetting("Server", "Notifies you when the server stops responding.", true);
    BooleanSetting lagback = new BooleanSetting("Lagback", "Notifies you when you lagback.", true);
    ColorSetting color = new ColorSetting("Color", "The color of the notification text.", ColorUtils.getDefaultOutlineColor());

    Vec3 lagPos = null;
    double lagDistance;
    long lagTime = System.currentTimeMillis();

    @SubscribeEvent
    public void onPacketReceive(PacketReceiveEvent event) {
        if(getNull()) return;

        if(event.getPacket() instanceof ClientboundPlayerPositionPacket packet) {
            lagPos = new Vec3(packet.change().position().x(), packet.change().position().y(), packet.change().position().z());
            lagDistance = mc.player.position().distanceTo(lagPos);
            lagTime = System.currentTimeMillis();
        }
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderOverlayEvent event) {
        if(getNull()) return;

        int width = mc.getWindow().getGuiScaledWidth() / 2, height = mc.getWindow().getGuiScaledHeight() / 4;
        boolean flag = false;

        if(server.getValue() && EUClient.SERVER_MANAGER.getResponseTimer().hasTimeElapsed(1000)) {
            String text = "Detected server not responding for " + MathUtils.round(EUClient.SERVER_MANAGER.getResponseTimer().timeElapsed()/1000f, 1) + "s.";
            EUClient.FONT_MANAGER.drawTextWithShadow(event.getContext(),text, width - EUClient.FONT_MANAGER.getWidth(text) / 2, height - EUClient.FONT_MANAGER.getHeight(), color.getColor());
            flag = true;
        }

        if(lagback.getValue() && System.currentTimeMillis() - lagTime < 3000) {
            String text = "Detected lagback of " + MathUtils.round(lagDistance, 1) + " blocks " + MathUtils.round((System.currentTimeMillis() - lagTime) / 1000f, 1) + "s.";
            EUClient.FONT_MANAGER.drawTextWithShadow(event.getContext(),text, width - EUClient.FONT_MANAGER.getWidth(text) / 2, height - EUClient.FONT_MANAGER.getHeight() + (flag ? EUClient.FONT_MANAGER.getHeight() : 0), color.getColor());
        }
    }
}
