package zm.ac.mulungushi.registrar;

import android.app.Application;

/**
 * Starts persistent local storage and background sync once per app process, before any screen
 * opens. Registered in AndroidManifest.xml (android:name=".RegistrarApplication").
 */
public class RegistrarApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        StudentRepository.initialize(this);
        SyncManager.initialize(this);
    }
}
