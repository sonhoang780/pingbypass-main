package eu.client.modules.impl.visuals;

import eu.client.events.SubscribeEvent;
import eu.client.events.impl.RenderWorldEvent;
import eu.client.events.impl.TickEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ColorSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.graphics.Renderer3D;
import eu.client.utils.minecraft.EntityUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

@RegisterModule(name = "ESP", description = "Renders a box ESP around entities that you have selected.", category = Module.Category.VISUALS)
public class ESPModule extends Module {
    public BooleanSetting players = new BooleanSetting("Players", "Renders the box ESP on player entities.", true);
    public BooleanSetting hostiles = new BooleanSetting("Hostiles", "Renders the box ESP on hostile entities.", true);
    public BooleanSetting animals = new BooleanSetting("Animals", "Renders the box ESP on animal entities.", true);
    public BooleanSetting ambient = new BooleanSetting("Ambient", "Renders the box ESP on ambient entities.", false);
    public BooleanSetting invisibles = new BooleanSetting("Invisibles", "Renders the box ESP on invisible entities.", true);
    public BooleanSetting items = new BooleanSetting("Items", "Renders the box ESP on item entities.", true);
    public BooleanSetting others = new BooleanSetting("Others", "Renders the box ESP on miscellaneous entities.", false);

    public ModeSetting mode = new ModeSetting("Mode", "The rendering that will be applied to the target entities.", "Both", new String[]{"None", "Fill", "Outline", "Both"});
    public ColorSetting fillColor = new ColorSetting("FillColor", "The color that will be used for the fill rendering.", new ModeSetting.Visibility(mode, "Fill", "Both"), ColorUtils.getDefaultFillColor());
    public ColorSetting outlineColor = new ColorSetting("OutlineColor", "The color that will be used for the outline rendering.", new ModeSetting.Visibility(mode, "Outline", "Both"), ColorUtils.getDefaultOutlineColor());

    private List<Entity> targetEntities = new ArrayList<>();

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.level == null) return;

        List<Entity> targetEntities = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player) continue;
            if (!isValidEntity(entity)) continue;

            targetEntities.add(entity);
        }

        this.targetEntities = targetEntities;
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        if (mc.level == null) return;
        if (targetEntities.isEmpty()) return;

        for (Entity entity : targetEntities) {
            Vec3 pos = EntityUtils.getRenderPos(entity, event.getTickDelta());
            AABB box = new AABB(pos.x - entity.getBoundingBox().getXsize()/2, pos.y, pos.z - entity.getBoundingBox().getZsize()/2, pos.x + entity.getBoundingBox().getXsize()/2, pos.y + entity.getBoundingBox().getYsize(), pos.z + entity.getBoundingBox().getZsize()/2);

            if (mode.getValue().equalsIgnoreCase("Fill") || mode.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBox(event.getMatrices(), box, fillColor.getColor());
            if (mode.getValue().equalsIgnoreCase("Outline") || mode.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBoxOutline(event.getMatrices(), box, outlineColor.getColor());
        }
    }

    private boolean isValidEntity(Entity entity) {
        if (players.getValue() && entity.getType() == EntityType.PLAYER) return true;
        if (hostiles.getValue() && entity.getType().getCategory() == MobCategory.MONSTER) return true;
        if (animals.getValue() && (entity.getType().getCategory() == MobCategory.CREATURE || entity.getType().getCategory() == MobCategory.WATER_CREATURE || entity.getType().getCategory() == MobCategory.WATER_AMBIENT || entity.getType().getCategory() == MobCategory.UNDERGROUND_WATER_CREATURE || entity.getType().getCategory() == MobCategory.AXOLOTLS))
            return true;
        if (ambient.getValue() && entity.getType().getCategory() == MobCategory.AMBIENT) return true;
        if (invisibles.getValue() && entity.isInvisible()) return true;
        if (items.getValue() && (entity.getType() == EntityType.ITEM || entity.getType() == EntityType.EXPERIENCE_BOTTLE)) return true;
        return others.getValue();
    }
}
