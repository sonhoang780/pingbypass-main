package eu.client.mixins;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import eu.client.EUClient;
import eu.client.events.impl.GameLoopEvent;
import eu.client.events.impl.TickEvent;
import eu.client.gui.special.MainMenuScreen;
import eu.client.modules.impl.core.MenuModule;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin implements IMinecraft {
    @Shadow @Nullable public LocalPlayer player;

    @Shadow private int itemUseCooldown;

    @Shadow @Final public Options options;


    @Shadow public abstract void setScreen(@Nullable Screen screen);

    @Shadow @Nullable public HitResult hitResult;

    @Shadow @Nullable public Entity crosshairPickEntity;

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;setOverlay(Lnet/minecraft/client/gui/screen/Overlay;)V", shift = At.Shift.BEFORE))
    private void init(GameConfig args, CallbackInfo info) {
        EUClient.onPostInitialize();
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runTasks()V", shift = At.Shift.AFTER))
    private void runTickHook(boolean tick, CallbackInfo info) {
        EUClient.EVENT_HANDLER.post(new GameLoopEvent());
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void tick(CallbackInfo info) {
        EUClient.EVENT_HANDLER.post(new TickEvent());
    }

    @Inject(method = "doItemUse", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/LocalPlayer;isRiding()Z", ordinal = 0, shift = At.Shift.BEFORE))
    private void doItemUse(CallbackInfo info) {
        if (EUClient.MODULE_MANAGER != null && EUClient.MODULE_MANAGER.getModule(FastPlaceModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(FastPlaceModule.class).isValidItem(player.getMainHandItem().getItem())) {
            itemUseCooldown = EUClient.MODULE_MANAGER.getModule(FastPlaceModule.class).ticks.getValue().intValue();
        }
    }

    @ModifyExpressionValue(method = "handleBlockBreaking", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/LocalPlayer;isUsingItem()Z"))
    private boolean handleBlockBreaking(boolean original) {
        if (EUClient.MODULE_MANAGER != null && EUClient.MODULE_MANAGER.getModule(MultiTaskModule.class).isToggled()) return false;
        return original;
    }

    @ModifyExpressionValue(method = "doItemUse", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerInteractionManager;isBreakingBlock()Z"))
    private boolean handleInputEvents(boolean original) {
        if (EUClient.MODULE_MANAGER != null && EUClient.MODULE_MANAGER.getModule(MultiTaskModule.class).isToggled()) return false;
        return original;
    }

    @Inject(method = "pick", at = @At("HEAD"), cancellable = true)
    private void pick(float partialTicks, CallbackInfo info) {
        FreecamModule module = EUClient.MODULE_MANAGER.getModule(FreecamModule.class);
        if (module.isToggled()) {
            hitResult = WorldUtils.getRaytraceTarget(module.getFreeYaw(), module.getFreePitch(), module.getFreeX(), module.getFreeY(), module.getFreeZ());
            crosshairPickEntity = hitResult instanceof EntityHitResult entityHitResult ? entityHitResult.getEntity() : null;
            info.cancel();
        }
    }

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void setScreen(Screen screen, CallbackInfo info) {
        if (screen instanceof DeathScreen && player != null && EUClient.MODULE_MANAGER.getModule(AutoRespawnModule.class).isToggled()) {
            player.respawn();
            info.cancel();
        }

        if (screen instanceof TitleScreen) {
            EUClient.checkForUpdates();

            if (EUClient.MODULE_MANAGER.getModule(MenuModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(MenuModule.class).mainMenu.getValue()) {
                this.setScreen(new MainMenuScreen());
                info.cancel();
            }
        }
    }

}