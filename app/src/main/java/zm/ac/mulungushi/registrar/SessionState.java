package zm.ac.mulungushi.registrar;

/**
 * Whether the signed-in session has expired.
 * The network layer calls expire() when the server answers 401 (token expired).
 * Screens check isExpired() and show the "session expired" state instead of data.
 * Only the lecturer roster checks it so far.
 */
final class SessionState {

    private static volatile boolean expired = false;

    private SessionState() {}

    static boolean isExpired() { return expired; }

    static void expire() { expired = true; }

    /** Call when the user signs in again. */
    static void reset() { expired = false; }
}
