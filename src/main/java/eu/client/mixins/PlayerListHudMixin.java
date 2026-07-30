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
    @Shadow @Final private Minecraft client;

    @Shadow @Final private static Comparator<PlayerInfo> ENTRY_ORDERING;

    @Shadow protected abstract Component applyGameModeFormatting(PlayerInfo entry, MutableComponent name);

    @Inject(method = "collectPlayerEntries", at = @At("HEAD"), cancellable = true)
    private void collectPlayerEntries(CallbackInfoReturnable<List<PlayerInfo>> info) {
        if (EUClient.MODULE_MANAGER.getModule(ExtraTabModule.class).isToggled()) {
            info.setReturnValue(client.player.connection.getOnlinePlayers().stream().sorted(ENTRY_ORDERING).limit(EUClient.MODULE_MANAGER.getModule(ExtraTabModule.class).limit.getValue().longValue()).toList());
        }
    }

    @Inject(method = "getPlayerName", at = @At(value = "HEAD"), cancellable = true)
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

                info.setReturnValue(applyGameModeFormatting(entry, text));
                return;
            }

            info.setReturnValue(applyGameModeFormatting(entry, PlayerTeam.formatNameForTeam(entry.getTeam(), Component.literal(entry.getProfile().name()))));
        }
    }
}
