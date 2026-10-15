package zm.ac.mulungushi.registrar;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

/**
 * The prototype's amber "You are offline" strip and its "Back online. Syncing."
 * toast. Call attach(this) once from onCreate after setContentView: the strip
 * is slotted in above the screen's content while the screen is visible, when
 *   - the phone has no network, or
 *   - it has a network but the server can't be reached (the last load failed),
 * and says the screen shows what was saved on the phone, and when. When the
 * network comes back, waiting changes are uploaded straight away.
 */
final class ConnectivityBanner {

    /** Last state any screen saw, so the "back online" message shows once on whichever screen is open. */
    private static Boolean lastOnline = null;

    private ConnectivityBanner() {}

    static void attach(AppCompatActivity a) {
        View content = a.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup) || ((ViewGroup) content).getChildCount() == 0) return;
        View root = ((ViewGroup) content).getChildAt(0);
        if (!(root instanceof LinearLayout) || ((LinearLayout) root).getOrientation() != LinearLayout.VERTICAL) return;

        TextView banner = new TextView(a);
        banner.setText(R.string.offline_banner);
        banner.setTextSize(12.5f);
        banner.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        banner.setTextColor(0xFF92400E);
        banner.setBackgroundColor(0xFFFFFBEB);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        int h = RosterFormat.dp(a, 16);
        banner.setPadding(h, RosterFormat.dp(a, 8), h, RosterFormat.dp(a, 8));
        banner.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_alert_circle, 0, 0, 0);
        banner.setCompoundDrawablePadding(RosterFormat.dp(a, 8));
        banner.setVisibility(View.GONE);
        ((LinearLayout) root).addView(banner, 0,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final Handler main = new Handler(Looper.getMainLooper());
        final ConnectivityManager cm = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
        // the server could not be reached on the last load: redraw when that changes
        final Runnable serverState = () -> apply(a, banner, isOnline(cm));

        final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(@NonNull Network network) { main.post(() -> apply(a, banner, isOnline(cm))); }
            @Override public void onLost(@NonNull Network network) { main.post(() -> apply(a, banner, isOnline(cm))); }
            @Override public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities caps) {
                main.post(() -> apply(a, banner, isOnline(cm)));
            }
        };

        a.getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override public void onStart(@NonNull LifecycleOwner owner) {
                StudentRepository.getInstance().addListener(serverState);
                apply(a, banner, isOnline(cm));
                try {
                    if (cm != null) cm.registerDefaultNetworkCallback(callback);
                } catch (RuntimeException ignored) { }
            }
            @Override public void onStop(@NonNull LifecycleOwner owner) {
                StudentRepository.getInstance().removeListener(serverState);
                try {
                    if (cm != null) cm.unregisterNetworkCallback(callback);
                } catch (RuntimeException ignored) { }
            }
        });
    }

    private static void apply(Activity a, TextView banner, boolean online) {
        if (a.isFinishing() || a.isDestroyed()) return;
        boolean wasOffline = lastOnline != null && !lastOnline;
        lastOnline = online;
        StudentRepository repo = StudentRepository.getInstance();
        boolean serverDown = online && repo.isOffline() && Session.isSignedIn();
        if (online && wasOffline && Session.isSignedIn()) {
            RosterApi.get().syncNow(null); // upload what waited, and load what changed meanwhile
        }
        long saved = repo.dataSavedAt();
        String when = saved > 0 ? RosterUi.savedAtText(saved) : null;
        if (!online) {
            banner.setText(when == null ? a.getString(R.string.offline_banner)
                    : a.getString(R.string.offline_banner_saved, when));
        } else if (serverDown) {
            banner.setText(when == null ? a.getString(R.string.server_down_banner)
                    : a.getString(R.string.server_down_banner_saved, when));
        }
        if (online && !serverDown) {
            if (banner.getVisibility() == View.VISIBLE) {
                banner.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                    banner.setVisibility(View.GONE);
                    banner.setAlpha(1f);
                }).start();
            }
            if (wasOffline) Feedback.show(a, R.string.back_online);
        } else if (banner.getVisibility() != View.VISIBLE) {
            banner.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            banner.setAlpha(0f);
            banner.setVisibility(View.VISIBLE);
            banner.animate().alpha(1f).setDuration(180).start();
        }
    }

    private static boolean isOnline(ConnectivityManager cm) {
        if (cm == null) return true;
        try {
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (RuntimeException e) {
            return true; // e.g. permission missing: assume online rather than crash
        }
    }
}
