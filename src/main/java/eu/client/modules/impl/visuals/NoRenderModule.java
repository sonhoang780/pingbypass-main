package eu.client.modules.impl.visuals;

import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;

@RegisterModule(name = "NoRender", description = "Disables the rendering of certain things.", category = Module.Category.VISUALS)
public class NoRenderModule extends Module {
    public BooleanSetting hurtCamera = new BooleanSetting("HurtCamera", "Disables the rendering of the hurt camera.", true);
    public BooleanSetting explosions = new BooleanSetting("Explosions", "Disables the rendering of explosion particles.", true);
    public BooleanSetting fireOverlay = new BooleanSetting("FireOverlay", "Disables the rendering of the fire overlay.", true);
    // Fire/Block/Liquid overlays: real screen-space texture overlays, all three drawn by
    // net.minecraft.client.renderer.ScreenEffectRenderer (renderFire/renderTex/renderWater),
    // NOT chunk mesh or FogEnvironment or the per-entity flame layer (two earlier guesses,
    // both confirmed wrong by live test) -- see ScreenEffectRendererMixin.
    public BooleanSetting blockOverlay = new BooleanSetting("BlockOverlay", "Disables the rendering of the block suffocation overlay.", false);
    public BooleanSetting liquidOverlay = new BooleanSetting("LiquidOverlay", "Disables the rendering of the liquid overlay.", false);
    public BooleanSetting snowOverlay = new BooleanSetting("SnowOverlay", "Disables the rendering of the snow overlay.", false);
    public BooleanSetting pumpkinOverlay = new BooleanSetting("PumpkinOverlay", "Disables the rendering of the pumpkin overlay.", true);
    public BooleanSetting portalOverlay = new BooleanSetting("PortalOverlay", "Disables the rendering of the portal overlay.", false);
    public BooleanSetting totemAnimation = new BooleanSetting("TotemAnimation", "Disables the rendering of the totem pop animation.", false);
    public BooleanSetting totemPop = new BooleanSetting("TotemPop", "Disables the rendering of totem pop particles.", false);
    public BooleanSetting bossBar = new BooleanSetting("BossBar", "Disables the rendering of the boss bar.", false);
    public BooleanSetting vignette = new BooleanSetting("Vignette", "Disables the rendering of the vignette.", true);
    public BooleanSetting blindness = new BooleanSetting("Blindness", "Disables the rendering of the blindness and darkness potion effects.", true);
    public BooleanSetting fog = new BooleanSetting("Fog", "Disables the rendering of the fog.", false);
    public BooleanSetting signText = new BooleanSetting("SignText", "Disables the rendering of sign text.", false);
    public BooleanSetting armor = new BooleanSetting("Armor", "Disables the rendering of armor.", false);
    public BooleanSetting limbSwing = new BooleanSetting("LimbSwing", "Disables the rendering of limb swing animations.", false);
    public BooleanSetting corpses = new BooleanSetting("Corpses", "Disables the rendering of corpses.", false);
    // Requested (2026-08-12), then corrected: a pile of overlapping player models (surrounds/1x1
    // fights) is what actually blocks seeing anything around you -- only hides an OTHER player's
    // model while they're standing on top of/right next to you, not every player on the server.
    // See LivingEntityRendererMixin.submit.
    public BooleanSetting player = new BooleanSetting("Player", "Disables the rendering of other players standing in the same spot as you.", false);
    public BooleanSetting background = new BooleanSetting("Background", "Disables the dark background dimming behind inventory/other screens.", false);
    public ModeSetting tileEntities = new ModeSetting("TileEntities", "Disables the rendering of tile entities, such as chests, when meeting requirements.", "Never", new String[]{"Never", "Distance", "Always"});
    public NumberSetting tileDistance = new NumberSetting("TileDistance", "The distance at which tile entities will stop rendering.", new ModeSetting.Visibility(tileEntities, "Distance"), 10.0f, 0.0f, 100.0f);

    // Items: cap how many dropped-item entities are drawn per frame. Limit only shows while Items
    // is on. Limit = 0 hides all items; higher values render up to that many before culling the
    // rest for the frame (see EntityRendererMixin.submit + LevelRendererMixin.renderEntities).
    public BooleanSetting items = new BooleanSetting("Items", "Limits how many dropped items are rendered in view.", false);
    public NumberSetting limit = new NumberSetting("Limit", "Max number of items to render per frame.", new BooleanSetting.Visibility(items, true), 100, 0, 100);
    public BooleanSetting displays = new BooleanSetting("Displays", "Disables the rendering of Display entities (BlockDisplay, ItemDisplay, TextDisplay).", false);

    // Per-frame counter of item entities already drawn this frame. Reset at the start of each
    // entity render pass; incremented as each item passes shouldRenderItem().
    private int renderedItems = 0;

    /** Called once at the start of every entity render pass to reset the per-frame counter. */
    public void resetItemCounter() {
        renderedItems = 0;
    }

    /**
     * Returns false if this item should be culled this frame (Items on and we've already drawn
     * the allowed number). Returns true and counts the item otherwise.
     */
    public boolean shouldRenderItem() {
        if (!items.getValue()) return true;
        if (renderedItems >= limit.getValue().intValue()) return false;
        renderedItems++;
        return true;
    }
}