package eu.client.managers;

import lombok.Getter;
import eu.client.EUClient;
import eu.client.modules.impl.core.FriendModule;

import java.awt.*;
import java.util.ArrayList;

@Getter
public class FriendManager {
    private final ArrayList<String> friends = new ArrayList<>();

    public boolean contains(String name) {
        if (getFriendFire()) return false;
        return friends.stream().anyMatch(name::equalsIgnoreCase);
    }

    public void add(String name) {
        if (contains(name)) return;
        friends.add(name);
        syncToProxy();
    }

    public void remove(String name) {
        friends.removeIf(name::equalsIgnoreCase);
        syncToProxy();
    }

    public void clear() {
        friends.clear();
        syncToProxy();
    }

    /**
     * Keeps the proxy's copy of the friends list in sync whenever it changes locally, not just
     * at initial connect -- see C2SFriendSyncPacket for why this needs to exist at all.
     */
    private void syncToProxy() {
        if (eu.client.pingbypass.PingBypassFlags.proxyForwardingActive
                && EUClient.PINGBYPASS_CONFIG != null && !EUClient.PINGBYPASS_CONFIG.isServer()) {
            var pingBypass = EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.core.PingBypassModule.class);
            if (pingBypass != null) pingBypass.syncFriendsToProxy();
        }
    }

    public boolean getFriendFire() {
        return EUClient.MODULE_MANAGER.getModule(FriendModule.class).friendlyFire.getValue();
    }

    public void sendFriendMessage(String name) {
        EUClient.MODULE_MANAGER.getModule(FriendModule.class).sendFriendMessage(name);
    }

    public Color getDefaultFriendColor() {
        return getDefaultFriendColor(255);
    }

    public Color getDefaultFriendColor(int alpha) {
        return new Color(85, 255, 255, alpha);
    }
}
