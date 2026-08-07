package eu.client.modules.impl.visuals;

import eu.client.EUClient;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.CategorySetting;
import eu.client.settings.impl.ColorSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.mixins.IChamsCapture;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;

import java.awt.*;

// Full original technique restored (2026-08-06): the model is submitted normally (vanilla texture
// render untouched) AND its geometry is captured a second time at the real flush point --
// ModelFeatureRendererMixin wraps the VertexConsumer passed to Model.renderToBuffer inside
// ModelFeatureRenderer.renderModel (verified via javap: that's where the actual per-quad vertices
// exist for ANY submitModel(...) entity, players/mobs/crystals alike) and, per quad, adds a
// translucent fill copy to Renderer3D.QUADS/SHINE_QUADS and a wireframe line-loop to
// Renderer3D.DEBUG_LINES/SHINE_DEBUG_LINES -- both already no-depth-test (render through walls),
// same infra AutoCrystal/BlockHighlight already use, unchanged since the pre-port original. This
// is the real reason the crystal shows THREE independently wireframed cubes in reference
// screenshots (its Model submits one Model.renderToBuffer call per cube, each producing its own
// set of quads) instead of a single outer silhouette -- a true per-face wireframe, not vanilla's
// blur-based entity_outline post-chain (which ShadersModule/PopChamsModule still use, unchanged,
// since it's the correct/cheaper choice for those). See EntityRenderStateMixin/IChamsCapture for
// how the fill/outline/shine spec rides from extractRenderState through to the flush point.
@RegisterModule(name = "Chams", description = "Adds a customizable render on top of the default Minecraft rendering.", category = Module.Category.VISUALS)
public class ChamsModule extends Module {
    public CategorySetting entitiesCategory = new CategorySetting("Entities", "The category for settings related to chams rendered on living entities.");
    public BooleanSetting players = new BooleanSetting("Players", "Renders the chams on player entities.", new CategorySetting.Visibility(entitiesCategory), true);
    // ItemInHandRendererMixin only ever piggybacked ShadersModule's outline color -- Chams/
    // PopChams being toggled never set anything for the local player's own first-person hands,
    // even with Players targeting on. Separate toggle (not just reusing Players) since some users
    // want Chams on other players' bodies without their own view-model getting recolored too.
    public BooleanSetting hands = new BooleanSetting("Hands", "Renders the chams on your own first-person hands/held item.", new CategorySetting.Visibility(entitiesCategory), true);
    public BooleanSetting hostiles = new BooleanSetting("Hostiles", "Renders the chams on hostile entities.", new CategorySetting.Visibility(entitiesCategory), false);
    public BooleanSetting passives = new BooleanSetting("Passives", "Renders the chams on passive entities.", new CategorySetting.Visibility(entitiesCategory), false);
    public BooleanSetting entityPulse = new BooleanSetting("EntityPulse", "Pulse", "Adds a pulsing effect to the chams opacity.", new CategorySetting.Visibility(entitiesCategory), false);
    public BooleanSetting entityShine = new BooleanSetting("EntityShine", "Shine", "Adds a shine effect to the chams.", new CategorySetting.Visibility(entitiesCategory), false);
    // Independent switch for the SAME whole-screen bloom AtmosphereModule.starGlow uses (see
    // GameRendererMixin.euclient$resolveStarGlow) -- Shine's bright highlight is what actually
    // clears the bloom's white-pixel threshold, so this only does anything visible while Shine is
    // also on. Doesn't scope the bloom to just Chams (it's a screen-space pass, can't isolate a
    // source), just arms it without needing StarGlow itself toggled on.
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
    public BooleanSetting crystalShine = new BooleanSetting("CrystalShine", "Shine", "Adds a shine effect on crystal chams.", new CategorySetting.Visibility(crystalsCategory), false);
    public ModeSetting crystalMode = new ModeSetting("CrystalMode", "Mode", "The rendering that will be applied to crystal entities.", new CategorySetting.Visibility(crystalsCategory), "Both", new String[]{"Fill", "Outline", "Both"});
    public ColorSetting crystalFillColor = new ColorSetting("CrystalFillColor", "FillColor", "The color that will be used for the fill rendering.", new ModeSetting.Visibility(crystalMode, "Fill", "Both"), ColorUtils.getDefaultFillColor());
    public ColorSetting crystalOutlineColor = new ColorSetting("CrystalOutlineColor", "OutlineColor", "The color that will be used for the outline rendering.", new ModeSetting.Visibility(crystalMode, "Outline", "Both"), ColorUtils.getDefaultOutlineColor());

    public boolean isValidEntity(Entity entity) {
        if (players.getValue() && entity.getType() == EntityType.PLAYER) return true;
        if (hostiles.getValue() && entity.getType().getCategory() == MobCategory.MONSTER) return true;
        return passives.getValue() && (entity.getType().getCategory() == MobCategory.CREATURE || entity.getType().getCategory() == MobCategory.WATER_CREATURE || entity.getType().getCategory() == MobCategory.WATER_AMBIENT || entity.getType().getCategory() == MobCategory.UNDERGROUND_WATER_CREATURE || entity.getType().getCategory() == MobCategory.AXOLOTLS);
    }

    // Fill and Outline are independent layers now (both can be on at once, "Both" mode), matching
    // the pre-port original -- picks each color the same way getColor(...) used to pick ONE, just
    // called twice (fillAlpha, outlineAlpha) instead of once.
    public void applyEntityChams(LivingEntity livingEntity, IChamsCapture capture) {
        boolean fill = entityMode.getValue().equals("Fill") || entityMode.getValue().equals("Both");
        boolean outline = entityMode.getValue().equals("Outline") || entityMode.getValue().equals("Both");
        capture.euclient$setChams(fill, pickEntityColor(livingEntity, entityFillColor.getColor(), friendFillColor.getColor()).getRGB(),
                outline, pickEntityColor(livingEntity, entityOutlineColor.getColor(), friendOutlineColor.getColor()).getRGB(),
                entityShine.getValue());
    }

    // For ItemInHandRendererMixin -- same solid-alpha-fill-color convention as ShadersModule's
    // getFillColor(entity) (real vertex alpha always 255, FillOpacity/mode applied later by the
    // shader, never baked in twice).
    public int getHandsFillColor(LivingEntity self) {
        return pickEntityColor(self, entityFillColor.getColor(), friendFillColor.getColor()).getRGB() | 0xFF000000;
    }

    public void applyCrystalChams(IChamsCapture capture) {
        boolean fill = crystalMode.getValue().equals("Fill") || crystalMode.getValue().equals("Both");
        boolean outline = crystalMode.getValue().equals("Outline") || crystalMode.getValue().equals("Both");
        Color fillColor = crystalPulse.getValue() ? ColorUtils.getPulse(crystalFillColor.getColor()) : crystalFillColor.getColor();
        Color outlineColor = crystalPulse.getValue() ? ColorUtils.getPulse(crystalOutlineColor.getColor()) : crystalOutlineColor.getColor();
        capture.euclient$setChams(fill, fillColor.getRGB(), outline, outlineColor.getRGB(), crystalShine.getValue());
    }

    private Color pickEntityColor(LivingEntity livingEntity, Color baseColor, Color friendBaseColor) {
        boolean flag = damageModify.getValue() && livingEntity.hurtTime > 0;

        Color color;
        if (livingEntity instanceof Player player && EUClient.FRIEND_MANAGER.contains(player.getName().getString()) && !friendMode.getValue().equals("Sync")) {
            color = flag ? ColorUtils.getColor(damageColor.getColor(), friendBaseColor.getAlpha()) : (friendMode.getValue().equals("Default") ? EUClient.FRIEND_MANAGER.getDefaultFriendColor(friendBaseColor.getAlpha()) : friendBaseColor);
        } else {
            color = flag ? ColorUtils.getColor(damageColor.getColor(), baseColor.getAlpha()) : baseColor;
        }

        return entityPulse.getValue() ? ColorUtils.getPulse(color) : color;
    }
}
