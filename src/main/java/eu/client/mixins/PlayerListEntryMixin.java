package eu.client.mixins;

import com.mojang.authlib.GameProfile;
import eu.client.EUClient;
import eu.client.modules.impl.core.CapesModule;
import eu.client.utils.IMinecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerInfo.class)
public class PlayerListEntryMixin implements IMinecraft {
    @Shadow @Final private GameProfile profile;

    @Inject(method = "getSkin", at = @At("TAIL"), cancellable = true)
    private void getSkinTextures(CallbackInfoReturnable<PlayerSkin> info) {
        if (((profile.name().equals(mc.player.getGameProfile().name()) && profile.id().equals(mc.player.getGameProfile().id()))) && EUClient.MODULE_MANAGER.getModule(CapesModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(CapesModule.class).getCapeTexture() != null) {
            Identifier identifier = EUClient.MODULE_MANAGER.getModule(CapesModule.class).getCapeTexture();
            PlayerSkin skin = info.getReturnValue();

            info.setReturnValue(new PlayerSkin(skin.body(), new ClientAsset.ResourceTexture(identifier), skin.elytra(), skin.model(), skin.secure()));
        }
    }
}
