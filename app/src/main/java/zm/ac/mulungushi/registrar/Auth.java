package zm.ac.mulungushi.registrar;

/**
 * Holds the AuthService the app uses: ApiAuthService, which talks to the backend
 * at ApiConfig.BASE_URL. Tests can swap in another one with set().
 */
public final class Auth {

    private static AuthService service;

    private Auth() {}

    public static synchronized AuthService get() {
        if (service == null) {
            service = new ApiAuthService();
        }
        return service;
    }

    /** For tests or for switching to the real service at start-up. */
    public static synchronized void set(AuthService replacement) {
        service = replacement;
    }
}
