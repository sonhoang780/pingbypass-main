package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.miscellaneous.ExtraTabModule;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerTabOverlay.class)
public abstract class PlayerListHudMixin {
    @Shadow private Component decorateName(PlayerInfo entry, MutableComponent name) {
        throw new UnsupportedOperationException();
    }

    // 2026-08-15 (reported): the old @Inject(HEAD, cancellable) full-body replacement below made
    // player-list head icons intermittently vanish the moment ExtraTab got toggled on, and only
    // toggling it off/on again fixed it -- even though this reimplementation looked byte-for-byte
    // equivalent to vanilla's own getPlayerInfos() (same getListedOnlinePlayers().stream().sorted()
    // call, just a different limit). Since both bodies are logically identical apart from that one
    // number, replacing the whole method was never necessary and evidently wasn't actually
    // equivalent in some way this comment can't fully account for -- @ModifyConstant instead
    // patches ONLY vanilla's hardcoded limit(80L), letting vanilla's real bytecode run unmodified
    // otherwise. Was getOnlinePlayers() (raw playerInfoMap.values()) before that -- caused a
    // different crash (integrated server's denser add/remove churn breaking the unlisted map
    // iteration in singleplayer) that's how getListedOnlinePlayers() ended up as the source; no
    // longer relevant now that vanilla's own body (which already uses it) runs untouched.
    @ModifyConstant(method = "getPlayerInfos", constant = @Constant(longValue = 80L))
    private long extraTabLimit(long original) {
        ExtraTabModule module = EUClient.MODULE_MANAGER.getModule(ExtraTabModule.class);
        return module.isToggled() ? module.limit.getValue().longValue() : original;
    }

    // getPlayerName renamed to getNameForDisplay(PlayerInfo); applyGameModeFormatting renamed to decorateName(PlayerInfo, MutableComponent)
    @Inject(method = "getNameForDisplay", at = @At(value = "HEAD"), cancellable = true)
    private void getPlayerName(PlayerInfo entry, CallbackInfoReturnable<Component> info) {
        if (EUClient.MODULE_MANAGER.getModule(ExtraTabModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(ExtraTabModule.class).friends.getValue() && EUClient.FRIEND_MANAGER.contains(entry.getProfile().name())) {
            if (entry.getTabListDisplayName() != null) {
                MutableComponent text = Component.empty();

                // 2026-08-15 FIX (reported: "ExtraTab thi thoảng làm mất tên players" -- row/icon/
                // ping still show, just the name text renders blank). getSiblings() only returns
                // components APPENDED to the display name, never the display name's OWN content --
                // a server that sends the player's name as a plain, unwrapped Component (no
                // team prefix/suffix, common when the player has no scoreboard team) has NOTHING
                // in getSiblings() at all: the real name lives in the root component itself, so
                // this loop ran zero times and `text` stayed Component.empty(). Names WITH a team
                // prefix/suffix happened to work by accident (the actual name text ends up as one
                // of the siblings alongside the prefix/suffix, purely because of how those servers
                // structure the component tree) -- which is exactly why it only broke "thi thoảng".
                // plainCopy() isolates the root's own content (no siblings) so it can be checked/
                // recolored the same way every sibling already is, instead of being skipped.
                java.util.List<Component> parts = new java.util.ArrayList<>();
                parts.add(entry.getTabListDisplayName().plainCopy());
                parts.addAll(entry.getTabListDisplayName().getSiblings());

                for (Component part : parts) {
                    if (part.getString().equals(entry.getProfile().name())) {
                        text.append(Component.literal(entry.getProfile().name()).withStyle(ChatFormatting.AQUA));
                        continue;
                    }

                    if (part.getString().equals("] " + entry.getProfile().name())) {
                        text.append(Component.literal("] ").withStyle(ChatFormatting.WHITE).append(Component.literal(entry.getProfile().name()).withStyle(ChatFormatting.AQUA)));
                        continue;
                    }

                    text.append(part);
                }

                info.setReturnValue(decorateName(entry, text));
                return;
            }

            info.setReturnValue(decorateName(entry, PlayerTeam.formatNameForTeam(entry.getTeam(), Component.literal(entry.getProfile().name()))));
        }
    }
}
