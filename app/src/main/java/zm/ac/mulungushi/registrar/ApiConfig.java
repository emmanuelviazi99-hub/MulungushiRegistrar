package zm.ac.mulungushi.registrar;

/**
 * Where the backend is (the Node.js server in mulungushi-registrar-backend/).
 * Every request from ApiAuthService goes to BASE_URL + a path like "/auth/login".
 */
public final class ApiConfig {

    private ApiConfig() {}

    /**
     * Android Emulator on the same computer as the backend: "http://10.0.2.2:3000/api"
     * (10.0.2.2 is how the emulator reaches the computer it runs on).
     *
     * A real phone on the same Wi-Fi: your computer's IPv4 address, e.g.
     * "http://192.168.1.105:3000/api" (run ipconfig to find it; it changes between networks).
     *
     * Plain http only works because AndroidManifest.xml allows cleartext traffic. Use an
     * https address for anything beyond testing on your own network.
     */
    public static final String BASE_URL = "http://127.0.0.1:3000/api";
}
