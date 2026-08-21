package eu.client.mixins;

import com.llamalad7.mixinextras.sugar.Local;
import eu.client.EUClient;
import eu.client.events.impl.RenderOverlayEvent;
import eu.client.gui.special.MainMenuScreen;
import eu.client.modules.impl.core.MenuModule;
import eu.client.modules.impl.miscellaneous.AutoRespawnModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// PORT (26.2): the other 5 injections that used to live here (extractEffects/extractPortalOverlay/
// extractVignette/extractCameraOverlays/extractItemHotbar) moved to HudMixin (@Mixin(Hud.class)) --
// those methods moved off Gui onto a new Hud sub-object in 26.2, confirmed via real source once
// this file's stale method-name strings were caught by a runtime MixinApplyError (see HudMixin's
// own PORT comment for the full trail). extractRenderState and setScreen below are still genuinely
// on Gui itself.
@Mixin(Gui.class)
public class InGameHudMixin {
    @Shadow @Final private Minecraft minecraft;

    // PORT (26.2): real signature is (DeltaTracker, boolean shouldRenderLevel, boolean
    // resourcesLoaded) -- no GuiGraphicsExtractor param, one is built as a LOCAL right after
    // profiler.push("gui") (confirmed via real source: `GuiGraphicsExtractor graphics = new
    // GuiGraphicsExtractor(...)`), a MixinApplyError caught this at runtime (stale descriptor from
    // before the GuiGraphicsExtractor split, invisible to compileJava). First fix moved this to
    // TAIL to reach past the local's construction point -- wrong draw order (2026-08-21 reported:
    // "HUD đè lên chat và Options menu"), TAIL is AFTER chat/screen/toasts/debug-overlay too, not
    // just after the local exists. Real order inside extractRenderState: graphics created -> (if
    // shouldRenderLevel) hud.extractRenderState draws vanilla HUD -> overlay OR screen (chat/menus)
    // -> toasts -> debug overlay. Hooking right after that `hud.extractRenderState` INVOKE lands
    // exactly where the old HEAD injection always drew relative to vanilla content: on top of the
    // vanilla HUD, but before chat/any screen gets its turn.
    @Inject(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Hud;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
            shift = At.Shift.AFTER))
    private void render(DeltaTracker tickCounter, boolean shouldRenderLevel, boolean resourcesLoaded, CallbackInfo info, @Local(ordinal = 0) GuiGraphicsExtractor context) {
        // PORT (26.2): Options.hideGui field removed -- the F1 hide-HUD toggle state moved to
        // Gui's own `hud` field (real net/minecraft/client/gui/Hud.java, read in full: `toggle()`
        // flips `isHidden`, real public getter `isHidden()`), not a persisted Options value anymore.
        if (minecraft.gui.hud.isHidden()) return;
        EUClient.EVENT_HANDLER.post(new RenderOverlayEvent(context, tickCounter.getGameTimeDeltaPartialTick(true)));
    }

    // PORT (26.2): moved from MinecraftClientMixin -- Minecraft.setScreen(Screen) is gone entirely,
    // real screen-change entry point is Gui.setScreen(Screen) now (confirmed via real source), a
    // different target class than a Minecraft-targeted mixin can hook.
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void euclient$setScreen(Screen screen, CallbackInfo info) {
        if (screen instanceof net.minecraft.client.gui.screens.social.SocialInteractionsScreen) {
            info.cancel();
            return;
        }

        // PORT (26.2): real crash caught via runtime test -- Gui.setScreen(TitleScreen) fires
        // during the client's own boot sequence (buildInitialScreens), before
        // EUClient.MODULE_MANAGER finishes initializing. Same guard pattern already used
        // elsewhere in this codebase for exactly this race (see HudMixin.renderStatusEffectOverlay).
        if (EUClient.MODULE_MANAGER == null) return;

        if (screen instanceof DeathScreen && minecraft.player != null) {
            AutoRespawnModule autoRespawn = EUClient.MODULE_MANAGER.getModule(AutoRespawnModule.class);
            if (autoRespawn != null && autoRespawn.isToggled()) {
                minecraft.player.respawn();
                info.cancel();
                return;
            }
        }

        if (screen instanceof TitleScreen) {
            EUClient.checkForUpdates();

            MenuModule menu = EUClient.MODULE_MANAGER.getModule(MenuModule.class);
            if (menu != null && menu.isToggled() && menu.mainMenu.getValue()) {
                minecraft.gui.setScreen(new MainMenuScreen());
                info.cancel();
            }
        }
    }
}
