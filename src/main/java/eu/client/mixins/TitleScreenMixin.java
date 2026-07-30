package eu.client.mixins;

import eu.client.EUClient;
import eu.client.utils.IMinecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.LogoRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.*;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen implements IMinecraft {
    @Shadow private boolean doBackgroundFade;

    @Shadow private float backgroundAlpha;

    @Shadow private long backgroundFadeStart;

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void init(CallbackInfo ci) {
        // PingBypass button is in MainMenuScreen instead
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void render$drawTextWithShadow(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo info) {
        if (EUClient.UPDATE_STATUS.equalsIgnoreCase("none")) return;

        String primaryText = "";
        String secondaryText = "";
        Color color = Color.WHITE;

        if (EUClient.UPDATE_STATUS.equalsIgnoreCase("update-available")) {
            secondaryText = "An update is available for EUClient.";
            primaryText = "Please restart the game to apply changes.";
            color = Color.ORANGE;
        }

        if (EUClient.UPDATE_STATUS.equalsIgnoreCase("failed-connection")) {
            secondaryText = "Failed to connect to EUClient's servers.";
            primaryText = "Please make sure you have a working internet connection.";
            color = Color.RED;
        }

        if (EUClient.UPDATE_STATUS.equalsIgnoreCase("failed")) {
            secondaryText = "Failed to update EUClient.";
            primaryText = "Please make sure the auto-updater is working properly.";
            color = Color.RED;
        }

        if (EUClient.UPDATE_STATUS.equalsIgnoreCase("up-to-date")) {
            primaryText = "EUClient is on the latest version.";
        }

        if (primaryText.isEmpty()) return;

        float f = 1.0F;
        if (doBackgroundFade) {
            float g = (float) (Util.getMillis() - backgroundFadeStart) / 2000.0F;
            if (g > 1.0F) {
                doBackgroundFade = false;
                backgroundAlpha = 1.0F;
            } else {
                g = Mth.clamp(g, 0.0F, 1.0F);
                f = Mth.clampedMap(g, 0.5F, 1.0F, 0.0F, 1.0F);
                this.backgroundAlpha = Mth.clampedMap(g, 0.0F, 0.5F, 0.0F, 1.0F);
            }
        }

        int i = Mth.ceil(f * 255.0F) << 24;

        if ((i & -67108864) != 0) {
            if (!secondaryText.isEmpty()) context.text(mc.font, secondaryText, width / 2 - mc.font.width(secondaryText) / 2, height - 30, color.getRGB() | i, true);
            context.text(mc.font, primaryText, width / 2 - mc.font.width(primaryText) / 2, height - 20, color.getRGB() | i, true);
        }
    }
}
