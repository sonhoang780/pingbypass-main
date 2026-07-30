package eu.client.modules.impl.visuals;

import eu.client.EUClient;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.CategorySetting;
import eu.client.settings.impl.ColorSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.utils.color.ColorUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;

import java.awt.*;

// ponytail: 26.1.2 renders entities via a submit-based pipeline (EntityRenderState.outlineColor
// consumed by LivingEntityRenderer/EndCrystalRenderer.submit -> OutlineBufferSource), the same
// mechanism vanilla uses for the Glowing effect/spectator highlight. Setting state.outlineColor
// in EntityRendererMixin.euclient$chams gets a real colored silhouette through walls "for free"
// from vanilla's own post-chain -- no more need for this module to manually re-render every
// entity's model as captured flat quads (the old VertexConsumer-capture trick in ModelRenderer.java
// is gone: model geometry submission is now high-level `submitModel(...)` calls, not per-vertex
// VertexConsumer draws, so there is no vertex-level interception point anymore).
// Dropped in this rewrite: independent Fill vs Outline color layering (now a single outline-style
// highlight; Fill/Both modes use fillColor, Outline-only uses outlineColor), and the Shine additive
// layer (was a second Renderer3D blend pass over the captured geometry, no longer applicable).
@RegisterModule(name = "Chams", description = "Adds a customizable render on top of the default Minecraft rendering.", category = Module.Category.VISUALS)
public class ChamsModule extends Module {
    public CategorySetting entitiesCategory = new CategorySetting("Entities", "The category for settings related to chams rendered on living entities.");
    public BooleanSetting players = new BooleanSetting("Players", "Renders the chams on player entities.", new CategorySetting.Visibility(entitiesCategory), true);
    public BooleanSetting hostiles = new BooleanSetting("Hostiles", "Renders the chams on hostile entities.", new CategorySetting.Visibility(entitiesCategory), false);
    public BooleanSetting passives = new BooleanSetting("Passives", "Renders the chams on passive entities.", new CategorySetting.Visibility(entitiesCategory), false);
    public BooleanSetting entityPulse = new BooleanSetting("EntityPulse", "Pulse", "Adds a pulsing effect to the chams opacity.", new CategorySetting.Visibility(entitiesCategory), false);
    public ModeSetting entityMode = new ModeSetting("EntityMode", "Mode", "The rendering that will be applied to living entities.", new CategorySetting.Visibility(entitiesCategory), "Both", new String[]{"Fill", "Outline", "Both"});
    public ColorSetting entityFillColor = new ColorSetting("EntityFillColor", "FillColor", "The color that will be used for the fill rendering.", new ModeSetting.Visibility(entityMode, "Fill", "Both"), ColorUtils.getDefaultFillColor());
    public ColorSetting entityOutlineColor = new ColorSetting("EntityOutlineColor", "OutlineColor", "The color that will be used for the outline rendering.", new ModeSetting.Visibility(entityMode, "Outline", "Both"), ColorUtils.getDefaultOutlineColor());
    public ModeSetting friendMode = new ModeSetting("FriendMode",  "The mode for the friend color.", new ModeSetting.Visibility(entityMode, "Fill", "Both"), "Default", new String[]{"Default", "Custom", "Sync"});
    public ColorSetting friendFillColor = new ColorSetting("FriendFillColor", "The color that will be used for the fill rendering on friends.", new ModeSetting.Visibility(entityMode, "Fill", "Both"), new ColorSetting.Color(new Color(85, 255, 255, ColorUtils.getDefaultFillColor().getColor().getAlpha()), false, false));
    public ColorSetting friendOutlineColor = new ColorSetting("FriendOutlineColor", "The color that will be used for the outline rendering on friends.", new ModeSetting.Visibility(entityMode, "Outline", "Both"), new ColorSetting.Color(new Color(85, 255, 255, ColorUtils.getDefaultOutlineColor().getColor().getAlpha()), false, false));
    public BooleanSetting damageModify = new BooleanSetting("DamageModify", "Changes the color of the chams when a player takes damage.", new ModeSetting.Visibility(entityMode, "Fill", "Both"), false);
    public ColorSetting damageColor = new ColorSetting("DamageColor", "The color to apply on chams when a player takes damage.", new BooleanSetting.Visibility(damageModify, true), new ColorSetting.Color(new Color(255, 0, 0), false, false));

    public CategorySetting crystalsCategory = new CategorySetting("Crystals", "The category for settings related to crystal chams.");
    public BooleanSetting crystals = new BooleanSetting("Crystals", "Enabled", "Renders the chams on crystal entities.", new CategorySetting.Visibility(crystalsCategory), true);
    public BooleanSetting crystalPulse = new BooleanSetting("CrystalPulse", "Pulse", "Adds a pulsing effect to the chams opacity.", new CategorySetting.Visibility(crystalsCategory), false);
    public ModeSetting crystalMode = new ModeSetting("CrystalMode", "Mode", "The rendering that will be applied to crystal entities.", new CategorySetting.Visibility(crystalsCategory), "Both", new String[]{"Fill", "Outline", "Both"});
    public ColorSetting crystalFillColor = new ColorSetting("CrystalFillColor", "FillColor", "The color that will be used for the fill rendering.", new ModeSetting.Visibility(crystalMode, "Fill", "Both"), ColorUtils.getDefaultFillColor());
    public ColorSetting crystalOutlineColor = new ColorSetting("CrystalOutlineColor", "OutlineColor", "The color that will be used for the outline rendering.", new ModeSetting.Visibility(crystalMode, "Outline", "Both"), ColorUtils.getDefaultOutlineColor());

    public boolean isValidEntity(Entity entity) {
        if (players.getValue() && entity.getType() == EntityType.PLAYER) return true;
        if (hostiles.getValue() && entity.getType().getCategory() == MobCategory.MONSTER) return true;
        return passives.getValue() && (entity.getType().getCategory() == MobCategory.CREATURE || entity.getType().getCategory() == MobCategory.WATER_CREATURE || entity.getType().getCategory() == MobCategory.WATER_AMBIENT || entity.getType().getCategory() == MobCategory.UNDERGROUND_WATER_CREATURE || entity.getType().getCategory() == MobCategory.AXOLOTLS);
    }

    public Color getEntityColor(LivingEntity livingEntity) {
        boolean flag = damageModify.getValue() && livingEntity.hurtTime > 0;
        boolean fill = entityMode.getValue().equals("Fill") || entityMode.getValue().equals("Both");

        Color friendColor = fill ? friendFillColor.getColor() : friendOutlineColor.getColor();
        Color baseColor = fill ? entityFillColor.getColor() : entityOutlineColor.getColor();

        Color color;
        if (livingEntity instanceof Player player && EUClient.FRIEND_MANAGER.contains(player.getName().getString()) && !friendMode.getValue().equals("Sync")) {
            color = flag ? ColorUtils.getColor(damageColor.getColor(), friendColor.getAlpha()) : (friendMode.getValue().equals("Default") ? EUClient.FRIEND_MANAGER.getDefaultFriendColor(friendColor.getAlpha()) : friendColor);
        } else {
            color = flag ? ColorUtils.getColor(damageColor.getColor(), baseColor.getAlpha()) : baseColor;
        }

        return entityPulse.getValue() ? ColorUtils.getPulse(color) : color;
    }

    public Color getCrystalColor() {
        boolean fill = crystalMode.getValue().equals("Fill") || crystalMode.getValue().equals("Both");
        Color color = fill ? crystalFillColor.getColor() : crystalOutlineColor.getColor();
        return crystalPulse.getValue() ? ColorUtils.getPulse(color) : color;
    }
}
