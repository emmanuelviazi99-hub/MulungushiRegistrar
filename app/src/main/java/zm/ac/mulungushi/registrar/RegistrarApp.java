package zm.ac.mulungushi.registrar;

import android.app.Application;

/**
 * Runs once when the app process starts, before any screen. Loads the saved
 * sign-in (Session) so every screen can read it.
 */
public class RegistrarApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Session.init(this);
    }
}
