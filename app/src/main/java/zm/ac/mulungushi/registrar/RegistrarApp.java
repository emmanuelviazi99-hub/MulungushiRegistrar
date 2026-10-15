package zm.ac.mulungushi.registrar;

import android.app.Application;

/**
 * Runs once when the app process starts, before any screen (and before SyncWorker
 * when WorkManager starts the app in the background). Loads the saved sign-in
 * (Session), then opens the phone's database (RosterApi): the saved data is shown
 * at once and any waiting changes are scheduled for upload.
 */
public class RegistrarApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Session.init(this);
        Notices.init(this);
        RosterApi.init(this);
        Notifier.init(this);
    }
}
