package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.miscellaneous.ExtraTabModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Comparator;
import java.util.List;

@Mixin(PlayerTabOverlay.class)
public abstract class PlayerListHudMixin {
    @Shadow @Final private Minecraft minecraft;

    @Shadow @Final private static Comparator<PlayerInfo> PLAYER_COMPARATOR;

    @Shadow private Component decorateName(PlayerInfo entry, MutableComponent name) {
        throw new UnsupportedOperationException();
    }

    // collectPlayerEntries renamed to getPlayerInfos(), no longer takes a scoreboard/limit param (fixed limit 80 upstream)
    // Was getOnlinePlayers() (raw playerInfoMap.values()) -- vanilla's own getPlayerInfos() (the
    // method this replaces) always reads getListedOnlinePlayers() instead. In singleplayer the
    // integrated server pushes tab-list add/remove entries far more densely than a real remote
    // server, and the unlisted map churns/iterates unsafely under that -- crashing the render and
    // dropping back to the title screen (repeated "disconnect"). getListedOnlinePlayers() is the
    // set vanilla already renders from every frame without issue; just raise vanilla's hardcoded
    // limit(80) instead of swapping the source collection.
    @Inject(method = "getPlayerInfos", at = @At("HEAD"), cancellable = true)
    private void collectPlayerEntries(CallbackInfoReturnable<List<PlayerInfo>> info) {
        if (EUClient.MODULE_MANAGER.getModule(ExtraTabModule.class).isToggled()) {
            info.setReturnValue(minecraft.player.connection.getListedOnlinePlayers().stream().sorted(PLAYER_COMPARATOR).limit(EUClient.MODULE_MANAGER.getModule(ExtraTabModule.class).limit.getValue().longValue()).toList());
        }
    }

    // getPlayerName renamed to getNameForDisplay(PlayerInfo); applyGameModeFormatting renamed to decorateName(PlayerInfo, MutableComponent)
    @Inject(method = "getNameForDisplay", at = @At(value = "HEAD"), cancellable = true)
    private void getPlayerName(PlayerInfo entry, CallbackInfoReturnable<Component> info) {
        if (EUClient.MODULE_MANAGER.getModule(ExtraTabModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(ExtraTabModule.class).friends.getValue() && EUClient.FRIEND_MANAGER.contains(entry.getProfile().name())) {
            if (entry.getTabListDisplayName() != null) {
                MutableComponent text = Component.empty();

                for (Component sibling : entry.getTabListDisplayName().getSiblings()) {
                    if (sibling.getString().equals(entry.getProfile().name())) {
                        text.append(Component.literal(entry.getProfile().name()).withStyle(ChatFormatting.AQUA));
                        continue;
                    }

                    if (sibling.getString().equals("] " + entry.getProfile().name())) {
                        text.append(Component.literal("] ").withStyle(ChatFormatting.WHITE).append(Component.literal(entry.getProfile().name()).withStyle(ChatFormatting.AQUA)));
                        continue;
                    }

                    text.append(sibling);
                }

                info.setReturnValue(decorateName(entry, text));
                return;
            }

            info.setReturnValue(decorateName(entry, PlayerTeam.formatNameForTeam(entry.getTeam(), Component.literal(entry.getProfile().name()))));
        }
    }
}
