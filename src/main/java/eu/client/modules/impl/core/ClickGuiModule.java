package eu.client.modules.impl.core;

import eu.client.EUClient;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ColorSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import org.lwjgl.glfw.GLFW;

import java.awt.*;

@RegisterModule(name = "ClickGui", description = "Allows you to change and interact with the client's modules and settings through a GUI.", category = Module.Category.CORE, drawn = false, bind = GLFW.GLFW_KEY_RIGHT_SHIFT)
public class ClickGuiModule extends Module {
    public BooleanSetting sounds = new BooleanSetting("Sounds", "Plays Minecraft UI sounds when interacting with the client's GUI.", true);
    public BooleanSetting blur = new BooleanSetting("Blur", "Whether or not to blur the background behind the GUI.", true);
    public NumberSetting scrollSpeed = new NumberSetting("ScrollSpeed", "The speed at which the scrolling of the frames will be at.", 15, 1, 50);
    public ColorSetting color = new ColorSetting("Color", "The color that will be used in the GUI.", new ColorSetting.Color(new Color(160, 120, 255), true, false));
    // All 9 ShadersModule animated patterns (EspShader.MODES minus "None"), not just Neekeri --
    // same shader dispatcher (neekeri_ui.fsh's esp_color), picked by name here instead.
    private static final String[] FILL_MODES = buildFillModes();
    public ModeSetting fillMode = new ModeSetting("FillMode", "How a toggled module's row background fills.", "Default", FILL_MODES);

    private static String[] buildFillModes() {
        String[] shaderModes = eu.client.utils.graphics.EspShader.MODES; // {"None", "Gradient", ..., "Neekeri"}
        String[] modes = new String[shaderModes.length];
        modes[0] = "Default";
        System.arraycopy(shaderModes, 1, modes, 1, shaderModes.length - 1);
        return modes;
    }
    // Visibility must match every non-"Default" mode, not just "Neekeri" -- these dials apply to
    // whichever of the 9 patterns is picked, same as ShaderOpacity/ShaderSpeed do for ALL of
    // ShadersModule's SHADER_ACTIVE_MODES, not just one.
    private static final String[] SHADER_FILL_MODES = java.util.Arrays.copyOfRange(FILL_MODES, 1, FILL_MODES.length);
    public NumberSetting neekeriSpeed = new NumberSetting("NeekeriSpeed", "Speed", "The speed at which the fill animates.", new ModeSetting.Visibility(fillMode, SHADER_FILL_MODES), 1.0f, 0.1f, 10.0f);
    public NumberSetting neekeriOpacity = new NumberSetting("NeekeriOpacity", "Opacity", "The opacity of the fill.", new ModeSetting.Visibility(fillMode, SHADER_FILL_MODES), 90, 0, 100);

    @Override
    public void onEnable() {
        if (mc.player == null) {
            setToggled(false);
            return;
        }

        EUClient.CLICK_GUI.cancelClose();
        mc.setScreen(EUClient.CLICK_GUI);
    }

    @Override
    public void onDisable() {
        // Deferred close: EUClient.CLICK_GUI plays its slide-up animation and removes itself (via
        // Minecraft.setScreen(null)) once that finishes, instead of vanishing instantly here.
        if (mc.screen == EUClient.CLICK_GUI) {
            EUClient.CLICK_GUI.requestClose();
        } else {
            mc.setScreen(null);
        }
    }

    public boolean isRainbow() {
        if(color.isSync()) return EUClient.MODULE_MANAGER.getModule(ColorModule.class).color.isRainbow();
        return color.isRainbow();
    }
}
