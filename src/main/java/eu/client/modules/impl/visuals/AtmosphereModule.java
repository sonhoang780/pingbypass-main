package eu.client.modules.impl.visuals;

import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ColorSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.color.ColorUtils;

@RegisterModule(name = "Atmosphere", description = "Modifies the world's atmosphere, such as time and color.", category = Module.Category.VISUALS)
public class AtmosphereModule extends Module {
    public BooleanSetting modifyTime = new BooleanSetting("ModifyTime", "Modifies the world's time.", true);
    public NumberSetting time = new NumberSetting("Time", "The time that the world will be set to.", new BooleanSetting.Visibility(modifyTime, true), 200, -200, 200);
    public BooleanSetting modifyFog = new BooleanSetting("ModifyFog", "Modifies certain things about the world's fog.", false);
    public NumberSetting fogStart = new NumberSetting("FogStart", "The start value of the world's fog.", new BooleanSetting.Visibility(modifyFog, true), 50, 0, 300);
    public NumberSetting fogEnd = new NumberSetting("FogEnd", "The end value of the world's fog.", new BooleanSetting.Visibility(modifyFog, true), 150, 0, 300);
    public ColorSetting fogColor = new ColorSetting("FogColor", "Modifies the color of the world's fog.", new BooleanSetting.Visibility(modifyFog, true), ColorUtils.getDefaultOutlineColor());
    // Client-only visual override (see WeatherMixin) -- overrides isRaining/isThundering/
    // getRainLevel/getThunderLevel, the same read paths particles/sky darkening/ambient sound use.
    // Never touches the server; a real weather change still comes through and overwrites it back.
    // Snow/Dust are NOT part of vanilla's real weather state (there's no such thing as a "Snow"
    // weather -- rain just RENDERS as snow in cold-enough biomes, and "Dust" doesn't exist at
    // all) -- forcing an actual snow render regardless of biome means spoofing biome temperature
    // reads, an unverified mixin target with no existing reference anywhere in this codebase to
    // cross-check against (unlike isRaining/getRainLevel, both already used successfully
    // elsewhere) -- too high a launch-crash risk to guess blind. These two are manually spawned
    // ambient particles around the player instead (onTick below) -- same visual idea, zero mixin
    // risk, doesn't touch the real weather/biome system at all.
    public ModeSetting weather = new ModeSetting("Weather", "Overrides the world's weather (visual only, not sent to the server).", "Unchanged", new String[]{"Unchanged", "Clear", "Rain", "Thunder", "Snow", "Dust"});

    // Real per-pixel star isolation this time (see SkyRendererMixin/StarCapture) -- redirects
    // SkyRenderer.renderStars()'s OWN render pass to an isolated target instead of a whole-screen
    // brightness threshold, so this can't catch snow/bedrock/clouds/anything else anymore.
    public eu.client.settings.impl.CategorySetting starGlowCategory = new eu.client.settings.impl.CategorySetting("StarGlow", "The category for the star glow effect.");
    public BooleanSetting starGlow = new BooleanSetting("StarGlow", "Enabled", "Adds a soft glow to vanilla sky stars.", new eu.client.settings.impl.CategorySetting.Visibility(starGlowCategory), false);
    public NumberSetting starGlowIntensity = new NumberSetting("StarGlowIntensity", "Intensity", "How strong the glow is.", new eu.client.settings.impl.CategorySetting.Visibility(starGlowCategory), 3.0f, 0.0f, 20.0f);

    @eu.client.events.SubscribeEvent
    public void onTick(eu.client.events.impl.TickEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (!weather.getValue().equalsIgnoreCase("Snow") && !weather.getValue().equalsIgnoreCase("Dust")) return;

        boolean snow = weather.getValue().equalsIgnoreCase("Snow");
        net.minecraft.core.particles.ParticleOptions particle = snow ? net.minecraft.core.particles.ParticleTypes.SNOWFLAKE : net.minecraft.core.particles.ParticleTypes.MYCELIUM;

        for (int i = 0; i < 6; i++) {
            double x = mc.player.getX() + (mc.player.getRandom().nextDouble() - 0.5) * 20;
            double y = mc.player.getY() + (snow ? 8 + mc.player.getRandom().nextDouble() * 4 : mc.player.getRandom().nextDouble() * 6 - 1);
            double z = mc.player.getZ() + (mc.player.getRandom().nextDouble() - 0.5) * 20;
            mc.level.addParticle(particle, x, y, z, 0.0, snow ? -0.15 : 0.01, 0.0);
        }
    }
}
