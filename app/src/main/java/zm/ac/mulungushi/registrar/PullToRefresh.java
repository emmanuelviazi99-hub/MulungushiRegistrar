package zm.ac.mulungushi.registrar;

import android.app.Activity;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

/**
 * Pull down on a screen's list to reload it from the server now (uploading anything
 * waiting first). The screen redraws through StudentRepository's listeners; if the
 * server can't be reached the person is told, and the saved data stays on screen.
 */
final class PullToRefresh {

    interface Loader {
        void load(RosterApi.Done done);
    }

    private PullToRefresh() {}

    static void attach(Activity a, Loader loader) {
        SwipeRefreshLayout swipe = a.findViewById(R.id.swipeRefresh);
        if (swipe == null) return;
        swipe.setColorSchemeResources(R.color.navy_600);
        swipe.setOnRefreshListener(() -> loader.load(outcome -> {
            swipe.setRefreshing(false);
            if (a.isFinishing() || a.isDestroyed()) return;
            if (!outcome.ok) RosterUi.refreshFailed(a, outcome, false);
        }));
    }

    static void lecturer(Activity a) {
        attach(a, done -> RosterApi.get().loadRoster(done));
    }

    static void student(Activity a) {
        attach(a, done -> RosterApi.get().loadMine(done));
    }
}
