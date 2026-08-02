package eu.client.pingbypass;

/**
 * Global flags for PingBypass proxy state.
 * Separated from mixins to avoid mixin field restrictions.
 */
public class PingBypassFlags {
    /**
     * When true, registry loading errors are tolerated (logged as warnings
     * instead of crashing). Set before the proxy sends ReadyS2CPacket,
     * auto-reset after first use.
     */
    public static volatile boolean tolerateRegistryErrors = false;

    /**
     * When true, UnsupportedOperationException from the Netty encoder (Lambda errors)
     * are suppressed instead of disconnecting. Set during PingBypass proxy connection.
     */
    public static volatile boolean suppressEncoderErrors = false;

    /**
     * When true, ALL disconnects are suppressed. Set during the initial proxy
     * connection handshake and cleared once the client is stable in PLAY state.
     */
    public static volatile boolean suppressAllDisconnects = false;

    /**
     * When true, the proxy is actively forwarding packets for a client.
     * The proxy's own movement/input processing should be suppressed.
     */
    public static volatile boolean proxyForwardingActive = false;

    /**
     * When true, the CLIENT no longer builds/sends its own movement or action packets at all --
     * ClientInputService forwards raw key/mouse input to the proxy instead, and the proxy's own
     * LocalPlayer (via ServerInputService) is the sole source of every gameplay packet.
     * Only meaningful on the client side; the proxy always ticks its player normally.
     */
    public static volatile boolean rawInputForwardingActive = false;

    /**
     * True when this JVM is the real player's own client and a PingBypass proxy is actively
     * driving gameplay via raw-input replay -- combat/target-tracking modules (AutoCrystal,
     * AutoTotem, Surround, ...) must skip their own decision logic entirely here, deferring
     * to the proxy's ServerAutoCrystal/ServerAutoTotem/ServerSurround (which see the same
     * world state via the dumb-pipe S2C forward and would otherwise act on it independently,
     * sending duplicate/conflicting packets to the real server -- these modules build and
     * send packets directly via mc.getConnection(), bypassing the raw-input cancellation in
     * ClientPlayerEntityMixin/ClientPlayerInteractionManagerMixin, which only covers the real
     * player's own hardware-triggered move/attack/dig).
     */
    public static boolean isClientDeferringToProxy() {
        return rawInputForwardingActive
                && eu.client.EUClient.PINGBYPASS_CONFIG != null
                && !eu.client.EUClient.PINGBYPASS_CONFIG.isServer();
    }
}
