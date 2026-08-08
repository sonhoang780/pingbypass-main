package eu.client.modules.impl.core;

import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.CategorySetting;
import eu.client.settings.impl.ModeSetting;

@RegisterModule(name = "Menu", description = "Replaces the default title screen with the client's custom main menu screen.", category = Module.Category.CORE, persistent = true, drawn = false)
public class MenuModule extends Module {
    public CategorySetting mainMenuCategory = new CategorySetting("MainMenu", "The category for settings related to the main menu.");
    public BooleanSetting mainMenu = new BooleanSetting("MainMenu", "Enabled", "Replaces Minecraft's default main menu with a customizable one.", new CategorySetting.Visibility(mainMenuCategory), true);
    // Classic = the original plain dark-gradient menu. Cozy = the atmospheric night-city rework
    // (docs/menu-style-ideas.md) -- a warm rainy-night skyline with drifting embers and a
    // left-anchored panel, hand-written against Renderer2D/FontManager (no video/audio asset,
    // Minecraft's GUI layer can't play either -- see CozyMenuBackground's own doc comment).
    public ModeSetting style = new ModeSetting("Mode", "The visual style used for the custom main menu.", new BooleanSetting.Visibility(mainMenu, true), "Classic", new String[]{"Classic", "Cozy"});
}
