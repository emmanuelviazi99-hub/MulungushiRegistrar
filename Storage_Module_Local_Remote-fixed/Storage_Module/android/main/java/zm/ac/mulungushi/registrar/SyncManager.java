package zm.ac.mulungushi.registrar;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Offline-first sync. The phone always works from local storage; this pushes changed
 * students to the server and pulls the server's list back whenever the network is available.
 *
 * When it runs:
 *  - at app start and every time the phone regains a connection
 *  - about 1.5 s after any local save (a burst of edits becomes one upload)
 *  - straight away after a registration (StudentRepository.addRegistered -> syncNow)
 *  - after a failed upload, again after 15 s, 30 s, 1 min, 2 min, then every 5 min while
 *    anything is still waiting (so a server that was down is retried without a network change)
 *
 * A failed upload never deletes anything: local records stay saved and stay marked as
 * waiting until the server holds them. Uploads are keyed by record id and the server keeps
 * one row per id and per student number, so a retried upload never creates a duplicate.
 *
 * All repository access happens on the main thread; only the HTTP call runs in the background.
 */
public final class SyncManager {

    public interface Listener { void onSyncFinished(boolean ok, String message); }

    private static final long[] RETRY_DELAYS_MS = {15_000L, 30_000L, 60_000L, 120_000L, 300_000L};

    private static SyncManager instance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable debounced = this::syncNow;
    private final Runnable retry = this::syncNow;
    private boolean running;
    private boolean again;
    private int failures;
    private long lastSyncAt;
    private String lastError;
    private String lastRejected;
    private Listener listener;

    private SyncManager(Context context) { app = context.getApplicationContext(); }

    public static synchronized void initialize(Context context) {
        if (instance == null) {
            instance = new SyncManager(context);
            instance.watchNetwork();
        }
    }

    /** The manager, or null before RegistrarApplication has started it. */
    static synchronized SyncManager peek() { return instance; }

    public static synchronized SyncManager getInstance() {
        if (instance == null) throw new IllegalStateException("SyncManager not initialized");
        return instance;
    }

    public void setListener(Listener l) { listener = l; }
    public long getLastSyncAt() { return lastSyncAt; }
    public String getLastError() { return lastError; }
    /** Why the server refused records on the last sync, or null. */
    public String getLastRejected() { return lastRejected; }
    public boolean isRunning() { return running; }
    public int pendingCount() { return StudentRepository.getInstance().pendingSyncCount(); }

    /** Called after every local save: waits a moment so a burst of edits becomes one upload. */
    public void requestSync() {
        main.removeCallbacks(debounced);
        main.postDelayed(debounced, 1500);
    }

    /** Sync now if online. Safe to call from any screen's main thread. */
    public void syncNow() {
        main.removeCallbacks(debounced);
        main.removeCallbacks(retry);
        if (running) { again = true; return; }
        if (!isOnline()) {
            lastError = "Offline";
            notifyListener(false, "Offline - changes are saved on this phone and will upload when you reconnect");
            return; // the network callback starts the next sync when the connection returns
        }
        running = true;
        again = false;
        final JSONArray changed;
        try {
            changed = StudentRepository.getInstance().dirtyAsJson();
        } catch (RuntimeException e) {
            running = false;
            lastError = e.getMessage();
            notifyListener(false, "Could not prepare changes: " + e.getMessage());
            return;
        }
        io.execute(() -> {
            ApiRemoteStorage.Result result = null;
            String error = null;
            try {
                result = ApiRemoteStorage.sync(changed);
            } catch (Exception e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            final ApiRemoteStorage.Result r = result;
            final String err = error;
            main.post(() -> finish(r, err));
        });
    }

    private void finish(ApiRemoteStorage.Result result, String error) {
        running = false;
        boolean ok = error == null;
        String message;
        if (ok) {
            try {
                int n = StudentRepository.getInstance().applyRemote(result.students);
                lastSyncAt = System.currentTimeMillis();
                lastError = null;
                failures = 0;
                lastRejected = describeRejected(result.rejected);
                if (lastRejected != null) {
                    message = "Some records were not accepted by the server and are kept on this phone: " + lastRejected;
                } else {
                    message = n == 0 ? "Up to date" : "Synced - " + n + " updated from server";
                }
            } catch (Exception e) {
                ok = false;
                lastError = "Could not apply server data: " + e.getMessage();
                message = lastError;
            }
        } else {
            lastError = error;
            message = "Sync failed - your changes are saved on this phone and will be retried";
        }
        notifyListener(ok && lastRejected == null, message);

        if (again) {
            again = false;
            syncNow();
        } else if (!ok && StudentRepository.getInstance().hasDirty()) {
            long delay = RETRY_DELAYS_MS[Math.min(failures, RETRY_DELAYS_MS.length - 1)];
            failures++;
            main.postDelayed(retry, delay);
        }
    }

    private static String describeRejected(JSONArray rejected) {
        if (rejected == null || rejected.length() == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rejected.length(); i++) {
            JSONObject o = rejected.optJSONObject(i);
            if (o == null) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(o.optString("message", o.optString("error", "rejected")));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private void notifyListener(boolean ok, String message) {
        if (listener != null) listener.onSyncFinished(ok, message);
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities c = cm.getNetworkCapabilities(n);
        return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /** Syncs at launch and every time the phone regains a connection. */
    private void watchNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    main.post(() -> {
                        failures = 0;
                        syncNow();
                    });
                }
            });
        } catch (Exception ignored) {
            /* no connectivity info: manual and save-triggered syncs still work */
            main.post(this::syncNow);
        }
    }
}
