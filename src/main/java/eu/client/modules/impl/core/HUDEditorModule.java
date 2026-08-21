package eu.client.modules.impl.core;

import eu.client.EUClient;
import eu.client.gui.HUDEditorScreen;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;

@RegisterModule(name = "HUDEditor", description = "Lets you drag-and-drop and toggle individual HUD elements directly on screen.", category = Module.Category.CORE, drawn = false)
public class HUDEditorModule extends Module {
    @Override
    public void onEnable() {
        mc.gui.setScreen(new HUDEditorScreen());
    }

    @Override
    public void onDisable() {
        if (mc.gui.screen() instanceof HUDEditorScreen) mc.gui.setScreen(null);
    }
}
