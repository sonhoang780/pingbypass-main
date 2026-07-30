package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.core.MenuModule;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.graphics.Renderer2D;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.*;

@Mixin(LevelLoadingScreen.class)
public class DownloadingTerrainScreenMixin extends Screen {
    @Shadow @Final private LevelLoadingScreen.Reason reason;

    protected DownloadingTerrainScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void renderBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if(reason.equals(LevelLoadingScreen.Reason.OTHER) && EUClient.MODULE_MANAGER.getModule(MenuModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(MenuModule.class).mainMenu.getValue()) {
            Renderer2D.renderQuad(context, 0, 0, width, height, new Color(25, 25, 25, 255));

            for (int i = 0; i < width; i++) {
                Color color = ColorUtils.getRainbow(2L, 0.7f, 1.0f, 255, i * 5L);
                Renderer2D.renderQuad(context, i, 0, i + 1, 1, color);
            }

            ci.cancel();
        }
    }
}
