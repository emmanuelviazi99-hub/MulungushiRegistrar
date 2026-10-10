package zm.ac.mulungushi.registrar;

/**
 * Holds the AuthService the app uses. This is the single line to change when the
 * real server is ready: return your ApiAuthService instead of DemoAuthService.
 */
public final class Auth {

    private static AuthService service;

    private Auth() {}

    public static synchronized AuthService get() {
        if (service == null) {
            service = new ApiAuthService(); // <- replace with the real server class
        }
        return service;
    }

    /** For tests or for switching to the real service at start-up. */
    public static synchronized void set(AuthService replacement) {
        service = replacement;
    }
}
