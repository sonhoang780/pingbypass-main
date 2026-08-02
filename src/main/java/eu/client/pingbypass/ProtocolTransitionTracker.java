package eu.client.pingbypass;

/**
 * Tracks when the proxy's own connection to the real server last got pushed into
 * CONFIGURATION protocol mid-session (resource pack / feature flag push). Whatever the
 * game's normal per-tick PLAY traffic has queued up around that exact moment races the
 * protocol switch and crashes the connection if it loses -- ClientConnectionMixin drops
 * known-racy packets for a short window after this gets marked instead of letting one
 * mistimed packet kill the session.
 */
public class ProtocolTransitionTracker {
    private static volatile long lastTransitionAt = 0;

    public static void mark() {
        lastTransitionAt = System.currentTimeMillis();
    }

    public static long lastTransitionAt() {
        return lastTransitionAt;
    }
}
