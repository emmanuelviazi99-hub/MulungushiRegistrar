package zm.ac.mulungushi.registrar;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

/**
 * Tells a screen whether the phone is online. watch() calls the listener when the
 * screen starts and again every time the network changes, while the screen is visible.
 */
final class NetworkWatcher {

    interface Listener {
        void onChanged(boolean online);
    }

    private NetworkWatcher() {}

    static boolean isOnline(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        try {
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (RuntimeException e) {
            return true; // e.g. permission missing: assume online rather than crash
        }
    }

    static void watch(AppCompatActivity a, Listener listener) {
        final ConnectivityManager cm = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
        final Handler main = new Handler(Looper.getMainLooper());

        final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(@NonNull Network network) { notifyLater(); }
            @Override public void onLost(@NonNull Network network) { notifyLater(); }
            @Override public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities caps) { notifyLater(); }

            private void notifyLater() {
                main.post(() -> {
                    if (!a.isFinishing() && !a.isDestroyed()) listener.onChanged(isOnline(a));
                });
            }
        };

        a.getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override public void onStart(@NonNull LifecycleOwner owner) {
                listener.onChanged(isOnline(a));
                try {
                    if (cm != null) cm.registerDefaultNetworkCallback(callback);
                } catch (RuntimeException ignored) { }
            }
            @Override public void onStop(@NonNull LifecycleOwner owner) {
                try {
                    if (cm != null) cm.unregisterNetworkCallback(callback);
                } catch (RuntimeException ignored) { }
            }
        });
    }
}
