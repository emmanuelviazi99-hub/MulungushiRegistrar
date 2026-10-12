package zm.ac.mulungushi.registrar;

/** Remote storage settings. The server address is shared with ApiConfig.BASE_URL. */
public final class RemoteConfig {
    private RemoteConfig() {}

    /** Must match SYNC_KEY in the backend's .env. Change both before sharing the app with anyone. */
    public static final String SYNC_KEY = "change-me";
}
