package eu.client.mixins;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import eu.client.EUClient;
import eu.client.events.impl.GameLoopEvent;
import eu.client.events.impl.TickEvent;
import eu.client.gui.special.MainMenuScreen;
import eu.client.modules.impl.core.MenuModule;
import eu.client.modules.impl.core.NoMiddleClickModule;
import eu.client.modules.impl.miscellaneous.AutoEscapeModule;
import eu.client.modules.impl.miscellaneous.AutoRespawnModule;
import eu.client.modules.impl.player.FastPlaceModule;
import eu.client.modules.impl.player.MultiTaskModule;
import eu.client.modules.impl.visuals.FreecamModule;
import eu.client.utils.IMinecraft;
import eu.client.utils.minecraft.WorldUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.Options;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin implements IMinecraft {
    @Shadow @Nullable public LocalPlayer player;

    @Shadow private int rightClickDelay;

    @Shadow @Final public Options options;


    @Shadow @Nullable public HitResult hitResult;

    @Shadow @Nullable public Entity crosshairPickEntity;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void init(GameConfig args, CallbackInfo info) {
        // ConfigManager can re-toggle modules (e.g. PingBypass) whose onEnable() reconnects via
        // ConnectScreen.startConnecting -> Minecraft.disconnect -> ... -> renderFrame(). In 26.1.2
        // that render call now happens synchronously and reads fields (framerateLimitTracker) that
        // are only assigned later in this constructor, so this must run after <init> fully completes
        // rather than mid-construction (BEFORE setOverlay, where 1.21.4 originally hooked it).
        EUClient.onPostInitialize();
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V", shift = At.Shift.AFTER))
    private void runTickHook(boolean tick, CallbackInfo info) {
        EUClient.EVENT_HANDLER.post(new GameLoopEvent());
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void tick(CallbackInfo info) {
        EUClient.EVENT_HANDLER.post(new TickEvent());
    }

    // Verified against .mcref: Minecraft.tick() has no early return, and runTick() calls
    // mouseHandler.handleAccumulatedMovement() (-> turnPlayer) and renderFrame() only AFTER the
    // whole tick loop -- so TAIL here is after level.tickEntities() and before either. See
    // TickEvent.Post's own doc.
    @Inject(method = "tick", at = @At("TAIL"))
    private void tick$post(CallbackInfo info) {
        EUClient.EVENT_HANDLER.post(new TickEvent.Post());
    }

    // Was HEAD -- verified via .mcref that startUseItem()'s own body unconditionally sets
    // rightClickDelay = 4 as its SECOND statement (right after the isDestroying() guard), so a
    // HEAD injection's override got immediately clobbered by vanilla's own assignment a few lines
    // later in the SAME call, every time (reported: FastPlace not matching its description at
    // all). Injecting right after that specific field write instead -- still fires before any of
    // the method's later early-return branches, since it's the first thing the method body does.
    @Inject(method = "startUseItem", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;rightClickDelay:I", shift = At.Shift.AFTER))
    private void doItemUse(CallbackInfo info) {
        if (EUClient.MODULE_MANAGER != null && EUClient.MODULE_MANAGER.getModule(FastPlaceModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(FastPlaceModule.class).isValidItem(player.getMainHandItem().getItem())) {
            rightClickDelay = EUClient.MODULE_MANAGER.getModule(FastPlaceModule.class).ticks.getValue().intValue();
        }
    }

    @ModifyExpressionValue(method = "continueAttack", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z"))
    private boolean handleBlockBreaking(boolean original) {
        if (EUClient.MODULE_MANAGER != null && EUClient.MODULE_MANAGER.getModule(MultiTaskModule.class).isToggled()) return false;
        return original;
    }

    @ModifyExpressionValue(method = "startUseItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;isDestroying()Z"))
    private boolean handleInputEvents(boolean original) {
        if (EUClient.MODULE_MANAGER != null && EUClient.MODULE_MANAGER.getModule(MultiTaskModule.class).isToggled()) return false;
        return original;
    }

    // AutoEscapeModule's root cause: it calls mc.gameMode.useItem(...) programmatically to start
    // eating the chorus fruit, but handleKeybinds() runs EVERY tick and unconditionally releases
    // any in-progress item use the instant it sees `player.isUsingItem() && !keyUse.isDown()` --
    // exactly what's true here, since nothing ever actually held the real right-click key down.
    // Vanilla itself was cancelling the eat one tick after we started it, every time, before the
    // 1.6s consume could ever finish -- the module then just waited out its own fake timer over
    // nothing and reported "escaped" regardless. Verified against the real compiled Minecraft.class
    // (javap): exactly one releaseUsingItem(...) call exists in this method, immediately gated by
    // this isDown() check, so redirecting it here can't accidentally skip anything else.
    @Redirect(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;releaseUsingItem(Lnet/minecraft/world/entity/player/Player;)V"))
    private void euclient$dontReleaseFakedUse(net.minecraft.client.multiplayer.MultiPlayerGameMode instance, net.minecraft.world.entity.player.Player releasedPlayer) {
        if (EUClient.MODULE_MANAGER.getModule(AutoEscapeModule.class).isEating()) return;
        instance.releaseUsingItem(releasedPlayer);
    }

    @Inject(method = "pick", at = @At("HEAD"), cancellable = true)
    private void pick(float partialTicks, CallbackInfo info) {
        // level/player can go null mid-disconnect (Minecraft.disconnect() still calls into a
        // pending renderFrame -> pick() before teardown finishes) while Freecam is still toggled
        // -- WorldUtils.getRaytraceTarget unconditionally uses mc.level.clip(...)/mc.player, NPEs
        // otherwise. Not our raytrace to make in that state anyway; let vanilla's own pick run.
        if (mc.level == null || player == null) return;

        FreecamModule module = EUClient.MODULE_MANAGER.getModule(FreecamModule.class);
        if (module.isToggled()) {
            hitResult = WorldUtils.getRaytraceTarget(module.getFreeYaw(), module.getFreePitch(), module.getFreeX(), module.getFreeY(), module.getFreeZ());
            crosshairPickEntity = hitResult instanceof EntityHitResult entityHitResult ? entityHitResult.getEntity() : null;
            info.cancel();
        }
    }

    // PORT (26.2): moved to InGameHudMixin (@Mixin(Gui.class)) -- Minecraft.setScreen(Screen) is
    // gone entirely, real screen-change entry point is now Gui.setScreen(Screen) (confirmed via
    // real source), a different target class than this mixin's own (Minecraft.class), so the
    // @Inject had to move to a mixin actually targeting Gui.

    @Inject(method = {"pickBlock", "pickBlockOrEntity"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void euclient$onPickBlock(CallbackInfo info) {
        if (EUClient.MODULE_MANAGER != null) {
            NoMiddleClickModule module = EUClient.MODULE_MANAGER.getModule(NoMiddleClickModule.class);
            if (module != null && module.isToggled()) {
                info.cancel();
            }
        }
    }

}