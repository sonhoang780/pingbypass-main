package eu.client.modules.impl.visuals;

import eu.client.EUClient;
import eu.client.modules.RegisterModule;
import eu.client.modules.Module;
import eu.client.settings.impl.*;
import eu.client.utils.color.ColorUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;

import java.awt.*;

// ponytail: rewritten onto the same EntityRenderState.outlineColor mechanism as ChamsModule
// (see EntityRendererMixin.euclient$chams) -- the old ShaderManager postchain/framebuffer
// approach it used is gone. "Hands" (first-person view-model highlight) is dropped: that's
// HeldItemRenderer's own submit path, a different (still-deferred) mixin, not an EntityRenderer
// subclass this hook can reach.
@RegisterModule(name = "Shaders", description = "Overlays specified entities with a customizable shader.", category = Module.Category.VISUALS)
public class ShadersModule extends Module {
    public CategorySetting targets = new CategorySetting("Targets", "The things that the shader rendering will be applied onto.");
    public BooleanSetting players = new BooleanSetting("Players", "Renders the shader effect on player entities.", new CategorySetting.Visibility(targets), true);
    public BooleanSetting hostiles = new BooleanSetting("Hostiles", "Renders the shader effect on hostile entities.", new CategorySetting.Visibility(targets), true);
    public BooleanSetting animals = new BooleanSetting("Animals", "Renders the shader effect on animal entities.", new CategorySetting.Visibility(targets), true);
    public BooleanSetting ambient = new BooleanSetting("Ambient", "Renders the shader effect on ambient entities.", new CategorySetting.Visibility(targets), false);
    public BooleanSetting invisibles = new BooleanSetting("Invisibles", "Renders the shader effect on invisible entities.", new CategorySetting.Visibility(targets), true);
    public BooleanSetting items = new BooleanSetting("Items", "Renders the shader effect on item entities.", new CategorySetting.Visibility(targets), true);
    public BooleanSetting crystals = new BooleanSetting("Crystals", "Renders the shader effect on crystal entities.", new CategorySetting.Visibility(targets), true);
    public BooleanSetting others = new BooleanSetting("Others", "Renders the shader effect on miscellaneous entities.", new CategorySetting.Visibility(targets), false);

    public ColorSetting color = new ColorSetting("Color", "The color that will be used for the fill rendering.", ColorUtils.getDefaultColor());
    public ModeSetting friends = new ModeSetting("Friends",  "The color that will be applied to friended entities.", "Default", new String[]{"Default", "Custom", "Sync"});
    public ColorSetting friendColor = new ColorSetting("FriendColor", "The color that will be used for the shader effect on friends.", new ModeSetting.Visibility(friends, "Custom"), new ColorSetting.Color(new Color(85, 255, 255, ColorUtils.getDefaultFillColor().getColor().getAlpha()), false, false));

    public boolean isValidEntity(Entity entity) {
        if (players.getValue() && entity.getType() == EntityType.PLAYER) return true;
        if (hostiles.getValue() && entity.getType().getCategory() == MobCategory.MONSTER) return true;
        if (animals.getValue() && (entity.getType().getCategory() == MobCategory.CREATURE || entity.getType().getCategory() == MobCategory.WATER_CREATURE || entity.getType().getCategory() == MobCategory.WATER_AMBIENT || entity.getType().getCategory() == MobCategory.UNDERGROUND_WATER_CREATURE || entity.getType().getCategory() == MobCategory.AXOLOTLS))
            return true;
        if (ambient.getValue() && entity.getType().getCategory() == MobCategory.AMBIENT) return true;
        if (invisibles.getValue() && entity.isInvisible()) return true;
        if (items.getValue() && (entity.getType() == EntityType.ITEM || entity.getType() == EntityType.EXPERIENCE_BOTTLE)) return true;
        if (crystals.getValue() && entity.getType() == EntityType.END_CRYSTAL) return true;
        return others.getValue();
    }

    public Color getColor(Entity entity) {
        if (entity instanceof Player player && EUClient.FRIEND_MANAGER.contains(player.getName().getString()) && !friends.getValue().equals("Sync")) {
            return friends.getValue().equals("Default") ? EUClient.FRIEND_MANAGER.getDefaultFriendColor() : friendColor.getColor();
        }

        return color.getColor();
    }
}
