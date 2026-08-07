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
     * The onGround / horizontalCollision the REAL client last reported, captured from its
     * forwarded ServerboundMovePlayerPacket in PbPlayHandler.
     *
     * Proxy-side modules that inject their own rotation packets must send these rather than
     * mc.player.onGround()/horizontalCollision: the proxy's ghost player is teleported with
     * setPos() straight from the client's packets and never runs real physics or collision, so
     * its own onGround is stale/meaningless. Sending a rotation packet carrying a ground flag
     * that contradicts the client's own movement packets (which are being forwarded on the same
     * connection) makes the real server see the player flip between on-ground and airborne
     * several times a second -- it "corrects" that, which is the rubberbanding that only happens
     * while moving and while a module (AutoCrystal/SpeedMine) is actively rotating.
     */
    public static volatile boolean clientOnGround = true;
    public static volatile boolean clientHorizontalCollision = false;

    /**
     * True on the real client, while connected to a PingBypass proxy -- matches earthhack's
     * isPingBypass()/PingBypass.isConnected() check. AutoCrystal and AutoTotem use this to skip
     * their own local calculation/thread while connected, deferring entirely to the proxy's
     * ServerAutoCrystal/ServerAutoTotem -- which see the same dumb-piped world state and would
     * otherwise act on it independently, producing duplicate/conflicting packets. Surround and
     * AutoTrap do NOT use this anymore -- matching earthhack, neither has a proxy-side port at
     * all, so both run unconditionally client-side regardless of PingBypass connection state.
     * The client's own movement/attack/dig packets are never touched by this -- those are always
     * sent normally (dumb pipe), matching
     * CPacketPlayerService's real behavior.
     */
    public static boolean isPingBypassActive() {
        return proxyForwardingActive
                && eu.client.EUClient.PINGBYPASS_CONFIG != null
                && !eu.client.EUClient.PINGBYPASS_CONFIG.isServer();
    }
}
