package zm.ac.mulungushi.registrar;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

/**
 * Keeps an open screen up to date: while it is in front, it quietly reloads from
 * the server every 30 seconds (uploading anything waiting first). New data reaches
 * the screen through StudentRepository's listeners, so a lecturer's approval shows
 * up on the student's home screen without reopening it. Stops when the screen is
 * left. Failures stay quiet here: the offline strip already says when the server
 * can't be reached.
 */
final class LiveRefresh implements DefaultLifecycleObserver {

    static final long EVERY_MS = 30_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable load;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (Session.isSignedIn()) load.run();
            handler.postDelayed(this, EVERY_MS);
        }
    };

    private LiveRefresh(Runnable load) {
        this.load = load;
    }

    /** load: a call such as RosterApi.get().loadRoster(null). Runs first after 30 s; the screen loads on open itself. */
    static void attach(AppCompatActivity a, Runnable load) {
        a.getLifecycle().addObserver(new LiveRefresh(load));
    }

    /** The lecturer screens: roster, totals and open requests. */
    static void lecturer(AppCompatActivity a) {
        attach(a, () -> RosterApi.get().loadRoster(null));
    }

    /** The student screens: own record, requests and group. */
    static void student(AppCompatActivity a) {
        attach(a, () -> RosterApi.get().loadMine(null));
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, EVERY_MS);
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        handler.removeCallbacks(tick);
    }
}
