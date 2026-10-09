package zm.ac.mulungushi.registrar;

/**
 * Configuration class holding the Backend Server Base URL.
 */
public final class ApiConfig {

    private ApiConfig() {}

    /**
     * Change this to your computer's IPv4 address when testing on a physical phone.
     * Example: "http://192.168.1.105:3000/api"
     * 
     * For the Android Emulator on the same computer, use:
     * "http://10.0.2.2:3000/api"
     */
    public static final String BASE_URL = "http://10.245.76.34:3000/api";
}
