package eu.client.modules.impl.visuals;

import eu.client.EUClient;
import eu.client.modules.RegisterModule;
import eu.client.modules.Module;
import eu.client.settings.impl.*;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.graphics.EspShader;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;

import java.awt.*;

// Fill/Outline/Both piggybacks on vanilla's REAL entity-glow post-chain (minecraft:entity_outline,
// an actual edge-detection blur -- not just a flat colored silhouette, contrary to what reading
// EntityRenderState.outlineColor alone suggests) instead of a parallel custom framebuffer. This
// entity captures a solid silhouette (color = getFillColor()'s ARGB, alpha included) into vanilla's
// shared entityOutlineTarget the same way ChamsModule/PopChamsModule already do; WorldRendererMixin
// then swaps WHICH post-chain Identifier gets requested for that buffer this frame, among three
// pre-baked variants (assets/euclient/post_effect/outline_{fill,outline,both}.json +
// shaders/program/outline.fsh -- ported from the original ShaderManager's real GLSL, RenderMode
// baked statically per variant since this renderer generation has no runtime post-chain-uniform
// setter). No separate Opacity setting -- ColorSetting's own alpha channel IS the opacity, carried
// straight through the captured buffer into the shader's fill branch. No separate "Hands" setting
// either -- when Players targeting covers mc.player, the first-person view-model (see
// ItemInHandRendererMixin) picks up the SAME Outline automatically. Its Fill half is still a no-op
// there: items render through submitItem(..., tints, quads, foilType), no single tintedColor-
// multiply equivalent to LivingEntityRenderer's -- tracked separately as a bigger follow-up.
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

    public ModeSetting mode = new ModeSetting("Mode", "The rendering that will be applied to the target entities.", "Both", new String[]{"Fill", "Outline", "Both"});
    public ColorSetting color = new ColorSetting("Color", "The color that will be used for the fill rendering.", ColorUtils.getDefaultColor());
    public ModeSetting friends = new ModeSetting("Friends",  "The color that will be applied to friended entities.", "Default", new String[]{"Default", "Custom", "Sync"});
    public ColorSetting friendColor = new ColorSetting("FriendColor", "The color that will be used for the shader effect on friends.", new ModeSetting.Visibility(friends, "Custom"), new ColorSetting.Color(new Color(85, 255, 255, ColorUtils.getDefaultFillColor().getColor().getAlpha()), false, false));

    // Same nine animated patterns AutoCrystal's ESP box has, reusing EspShader.MODES as the single
    // source of truth. ShaderOpacity is a SEPARATE multiplier from Color's own alpha channel
    // (-> pickVariant's FillOpacity bucket, see getFillColor) -- it only ever multiplies the
    // outline.fsh fragment shader's own alpha output one extra time (shaderOpacity uniform,
    // gated to effect != 0 so it can never touch the Chams/PopChams flat-color fallback), never
    // baked into the captured silhouette's vertex alpha, so it can't compound into the alpha²
    // bug documented on getFillColor -- that bug was specifically about writing REAL alpha at
    // BOTH the vertex-capture stage and the shader stage for the SAME value; this is one value
    // multiplied once, at the shader stage only. No DistanceScaling either -- this is a
    // screen-space post-process over every targeted entity at once, so there is no single
    // position to measure a distance to.
    private static final String[] SHADER_MODES = eu.client.utils.graphics.EspShader.MODES;
    private static final String[] SHADER_ACTIVE_MODES = java.util.Arrays.copyOfRange(SHADER_MODES, 1, SHADER_MODES.length);

    public ModeSetting shader = new ModeSetting("Shader", "The animated shader that will be drawn on the target entities instead of a flat color.", "None", SHADER_MODES);
    public NumberSetting shaderOpacity = new NumberSetting("ShaderOpacity", "Opacity", "The opacity of the animated shader pattern itself, independent of Color's own alpha.", new ModeSetting.Visibility(shader, SHADER_ACTIVE_MODES), 100, 0, 100);
    public NumberSetting shaderSpeed = new NumberSetting("ShaderSpeed", "Speed", "The speed at which the shader animates.", new ModeSetting.Visibility(shader, SHADER_ACTIVE_MODES), 1.0f, 0.1f, 10.0f);
    public NumberSetting shaderStep = new NumberSetting("ShaderStep", "Step", "The size of the gradient bands.", new ModeSetting.Visibility(shader, "Gradient"), 50.0f, 0.1f, 200.0f);
    public ColorSetting shaderColor1 = new ColorSetting("ShaderColor1", "The first gradient color.", new ModeSetting.Visibility(shader, "Gradient"), new ColorSetting.Color(new Color(255, 0, 255, 255), false, false));
    public ColorSetting shaderColor2 = new ColorSetting("ShaderColor2", "The second gradient color.", new ModeSetting.Visibility(shader, "Gradient"), new ColorSetting.Color(new Color(255, 0, 0, 255), false, false));
    public ColorSetting shaderColor3 = new ColorSetting("ShaderColor3", "The third gradient color.", new ModeSetting.Visibility(shader, "Gradient"), new ColorSetting.Color(new Color(0, 255, 0, 255), false, false));
    public ColorSetting shaderColor4 = new ColorSetting("ShaderColor4", "The fourth gradient color.", new ModeSetting.Visibility(shader, "Gradient"), new ColorSetting.Color(new Color(0, 0, 255, 255), false, false));
    public ColorSetting shaderGlowColor = new ColorSetting("ShaderGlowColor", "GlowColor", "The color that the glow shader will be tinted with.", new ModeSetting.Visibility(shader, "Glowing"), new ColorSetting.Color(new Color(255, 0, 255, 255), false, false));

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

    public boolean shouldOutline() {
        return mode.getValue().equalsIgnoreCase("Outline") || mode.getValue().equalsIgnoreCase("Both");
    }

    public boolean shouldFill() {
        return mode.getValue().equalsIgnoreCase("Fill") || mode.getValue().equalsIgnoreCase("Both");
    }

    // No separate Opacity dial -- ColorSetting's own picker already has an alpha channel (the bar
    // under the color square in the GUI), and WorldRendererMixin already reads it to pick an
    // opacity-bucketed post-chain variant (FillOpacity uniform). That's the ONLY place opacity
    // gets applied -- forcing full alpha (255) here, same as the original ShaderManager's own
    // vertexConsumerProvider.setColor(r, g, b, 255), matters: this int becomes the CAPTURED
    // silhouette's vertex alpha, which the shader's fill branch reads BACK OUT as `current.a` and
    // multiplies by FillOpacity AGAIN -- baking the real alpha in here too was applying it twice
    // (alpha * bucketAlpha, roughly alpha² instead of alpha), which is why higher Color alpha
    // values looked like they lost opacity entirely instead of getting more solid.
    public int getFillColor(Entity entity) {
        return getColor(entity).getRGB() | 0xFF000000;
    }

    public Color getColor(Entity entity) {
        if (entity instanceof Player player && EUClient.FRIEND_MANAGER.contains(player.getName().getString()) && !friends.getValue().equals("Sync")) {
            return friends.getValue().equals("Default") ? EUClient.FRIEND_MANAGER.getDefaultFriendColor() : friendColor.getColor();
        }

        return color.getColor();
    }

    // Priority picker (Shaders > PopChams > Chams entities > Chams crystals, first one actually
    // toggled) for WHICH pre-baked post_effect variant should run this frame -- shared by
    // WorldRendererMixin (blocks vanilla's own in-FrameGraph run) and GameRendererMixin (runs it
    // manually after the hand-outline flush). Plain static methods on a regular class, NOT inside
    // a @Mixin class: Sponge Mixin rejects any non-private method in a mixin body outright
    // (InvalidMixinException at class-load, hard crash) -- shared cross-mixin logic has to live
    // somewhere that isn't itself a @Mixin target.
    public static Identifier pickActiveOutlineChain() {
        ShadersModule shaders = EUClient.MODULE_MANAGER.getModule(ShadersModule.class);
        if (shaders.isToggled()) {
            return pickVariant(shaders.shouldFill(), shaders.shouldOutline(), shaders.color.getColor().getAlpha());
        }

        ChamsModule chams = EUClient.MODULE_MANAGER.getModule(ChamsModule.class);
        if (chams.isToggled()) {
            boolean entities = chams.players.getValue() || chams.hostiles.getValue() || chams.passives.getValue();
            String mode = entities ? chams.entityMode.getValue() : chams.crystalMode.getValue();
            int alpha = entities ? chams.entityFillColor.getColor().getAlpha() : chams.crystalFillColor.getColor().getAlpha();
            boolean fill = mode.equalsIgnoreCase("Fill") || mode.equalsIgnoreCase("Both");
            boolean outline = mode.equalsIgnoreCase("Outline") || mode.equalsIgnoreCase("Both");
            return pickVariant(fill, outline, alpha);
        }

        PopChamsModule popChams = EUClient.MODULE_MANAGER.getModule(PopChamsModule.class);
        if (popChams.isToggled()) {
            boolean fill = popChams.mode.getValue().equalsIgnoreCase("Fill") || popChams.mode.getValue().equalsIgnoreCase("Both");
            boolean outline = popChams.mode.getValue().equalsIgnoreCase("Outline") || popChams.mode.getValue().equalsIgnoreCase("Both");
            return pickVariant(fill, outline, popChams.fillColor.getColor().getAlpha());
        }

        return null;
    }

    // Companion to pickActiveOutlineChain: WHAT to animate into the picked chain's EspSettings UBO
    // this frame. Never null -- effect 0 (the "None" default, and every frame where PopChams/Chams
    // rather than Shaders owns the chain) still has to be written so the shader falls back to the
    // captured flat silhouette color exactly as before.
    public static EspShader.Settings shaderSettings() {
        ShadersModule shaders = EUClient.MODULE_MANAGER.getModule(ShadersModule.class);
        // Priority is Shaders > Chams > PopChams (pickActiveOutlineChain() above) -- Shaders wins
        // outright whenever it's toggled, same as that method, so its animated pattern only needs
        // to gate on its own isToggled() now. (Previously required Chams to be OFF too, matching
        // the old Chams > Shaders > PopChams order -- left stale after the priority flip, which is
        // why turning Chams on made Shaders' animated pattern vanish even though Shaders was
        // supposed to still win.)
        int effect = shaders.isToggled() ? EspShader.modeIndex(shaders.shader.getValue()) : 0;

        float step = shaders.shaderStep.getValue().floatValue();
        // Sydney folded speed (and, for Gradient only, step) into `time` rather than uploading them
        // as separate rates -- same as RenderManager does for AutoCrystal's box.
        float time = (System.currentTimeMillis() % 2000000L) / 1000.0f
                * shaders.shaderSpeed.getValue().floatValue() * (effect == 1 ? step : 1.0f);

        return new EspShader.Settings(effect, time, step, shaders.shaderOpacity.getValue().floatValue() / 100.0f, 1.0f,
                effect == 7 ? shaders.shaderGlowColor.getColor() : shaders.shaderColor1.getColor(),
                shaders.shaderColor2.getColor(),
                shaders.shaderColor3.getColor(),
                shaders.shaderColor4.getColor());
    }

    public static Identifier pickVariant(boolean fill, boolean outline, int alpha) {
        if (outline && !fill) return Identifier.fromNamespaceAndPath("euclient", "outline_outline");

        int bucket = Math.max(20, Math.min(100, (int) Math.round(alpha / 255.0 * 5) * 20));
        String prefix = fill && outline ? "outline_both_" : "outline_fill_";
        return Identifier.fromNamespaceAndPath("euclient", prefix + bucket);
    }
}
