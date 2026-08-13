package eu.client.modules.impl.visuals;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PacketReceiveEvent;
import eu.client.events.impl.RenderWorldEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ColorSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.settings.impl.WhitelistSetting;
import eu.client.utils.graphics.Renderer3D;
import eu.client.utils.minecraft.EntityUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.util.ArrayList;

@RegisterModule(name = "TextESP", description = "Renders text ESP on the world.", category = Module.Category.VISUALS)
public class TextESPModule extends Module {
    public BooleanSetting items = new BooleanSetting("Items", "Renders text ESP on item entities.", true);
    public ModeSetting itemListMode = new ModeSetting("ItemListMode", "All = render on every item. WhiteList = only listed items. BlackList = every item except listed.", new BooleanSetting.Visibility(items, true), "WhiteList", new String[]{"All", "WhiteList", "BlackList"});
    public WhitelistSetting itemWhitelist = new WhitelistSetting("ItemWhitelist", "Items the WhiteList/BlackList mode compares against.", new BooleanSetting.Visibility(items, true), WhitelistSetting.Type.ITEMS);
    public BooleanSetting pearls = new BooleanSetting("Pearls", "Renders text ESP on pearl entities.", true);
    public BooleanSetting chorus = new BooleanSetting("Chorus", "Renders text ESP on chorus sounds.", true);

    public NumberSetting scale = new NumberSetting("Scale", "The scaling that will be applied to the text ESP rendering.", 30, 10, 100);
    public ColorSetting color = new ColorSetting("Color", "The color that will be used for the text ESP rendering.", new ColorSetting.Color(Color.WHITE, false, false));
    // Reuses NameTagsModule's own Threshold/Intensity -- both just arm the same star_glow_text pass.

    private final ArrayList<Chorus> chorusList = new ArrayList<>();

    @SubscribeEvent
    public void onPacketReceive(PacketReceiveEvent event) {
        if(getNull()) return;

        synchronized (chorusList) {
            if (event.getPacket() instanceof ClientboundSoundPacket packet) {
                SoundEvent sound = packet.getSound().value();

                if (sound == SoundEvents.CHORUS_FRUIT_TELEPORT || sound == SoundEvents.ENDERMAN_TELEPORT) {
                    chorusList.add(new Chorus(mc.getSoundManager().getSoundEvent(sound.location()).getSubtitle().getString(), new Vec3(packet.getX(), packet.getY(), packet.getZ())));
                }
            }
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        if(getNull()) return;

        for(Entity e : mc.level.entitiesForRendering()) {
            if(!Renderer3D.isFrustumVisible(e.getBoundingBox())) continue;

            if(e instanceof ItemEntity item && items.getValue() && itemAllowed(item.getItem().getItem())) {
                Vec3 pos = EntityUtils.getRenderPos(item, event.getTickDelta());
                String s = item.getName().getString() + (item.getItem().getCount() > 1 ? " x" + item.getItem().getCount() : "");
                Renderer3D.renderScaledText(event.getMatrices(), s, pos.x, pos.y, pos.z, scale.getValue().intValue(), false, color.getColor());
            } else if(e instanceof ThrownEnderpearl pearl && pearl.getOwner() != null && pearls.getValue()) {
                Vec3 pos = EntityUtils.getRenderPos(pearl, event.getTickDelta());
                Renderer3D.renderScaledText(event.getMatrices(), pearl.getOwner().getName().getString(), pos.x, pos.y, pos.z, scale.getValue().intValue(), false, color.getColor());
            }
        }

        if(chorus.getValue()) {
            synchronized (chorusList) {
                chorusList.removeIf(c -> System.currentTimeMillis() - c.time >= 1500);

                for (Chorus c : chorusList) {
                    Renderer3D.renderScaledText(event.getMatrices(), c.subtitle, c.pos.x, c.pos.y, c.pos.z, scale.getValue().intValue(), false, color.getColor());
                }
            }
        }
    }

    private boolean itemAllowed(net.minecraft.world.item.Item item) {
        boolean listed = itemWhitelist.isWhitelistContains(item);
        return switch (itemListMode.getValue()) {
            case "WhiteList" -> listed;
            case "BlackList" -> !listed;
            default -> true; // All
        };
    }

    private class Chorus {
        private final String subtitle;
        private final Vec3 pos;
        private final long time;

        public Chorus(String subtitle, Vec3 pos) {
            this.subtitle = subtitle;
            this.pos = pos;
            this.time = System.currentTimeMillis();
        }
    }
}
