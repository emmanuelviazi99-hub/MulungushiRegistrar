package zm.ac.mulungushi.registrar;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * The prototype's "Get notified" flow: after a student sends a request we offer
 * alerts; if they allow, an answer to their request rings, buzzes and posts a
 * real Android notification. The choice is kept for this app run only, like the prototype.
 */
final class Notifier {

    static final String ASK = "ask";
    static final String ON = "on";
    static final String LATER = "later";

    private static final String CHANNEL = "request_answers";

    /** ask | on | later */
    static String pref = ASK;
    /** Set when a request was just sent, so Home offers the sheet once it is showing. */
    static boolean askSoon = false;

    private Notifier() {}

    // ---- the sheet ----

    static void showSheet(Activity a) {
        if (a.isFinishing() || a.isDestroyed()) return;
        BottomSheetDialog dialog = new BottomSheetDialog(a);
        int dp20 = RosterFormat.dp(a, 20);

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_sheet_top);
        root.setPadding(dp20, RosterFormat.dp(a, 10), dp20, dp20);

        View handle = new View(a);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(ContextCompat.getColor(a, R.color.slate_200));
        hb.setCornerRadius(RosterFormat.dp(a, 3));
        handle.setBackground(hb);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(RosterFormat.dp(a, 36), RosterFormat.dp(a, 5));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = RosterFormat.dp(a, 16);
        root.addView(handle, hlp);

        FrameLayout bell = new FrameLayout(a);
        GradientDrawable bb = new GradientDrawable();
        bb.setShape(GradientDrawable.OVAL);
        bb.setColor(ContextCompat.getColor(a, R.color.navy_50));
        bell.setBackground(bb);
        ImageView icon = new ImageView(a);
        icon.setImageResource(R.drawable.ic_bell);
        icon.setColorFilter(ContextCompat.getColor(a, R.color.navy_700));
        bell.addView(icon, new FrameLayout.LayoutParams(RosterFormat.dp(a, 22), RosterFormat.dp(a, 22), Gravity.CENTER));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(RosterFormat.dp(a, 44), RosterFormat.dp(a, 44));
        blp.bottomMargin = RosterFormat.dp(a, 12);
        root.addView(bell, blp);

        TextView title = new TextView(a);
        title.setText(R.string.notify_title);
        title.setTextSize(22f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(a, R.color.navy_900));
        root.addView(title);

        TextView body = new TextView(a);
        body.setText(R.string.notify_body);
        body.setTextSize(16f);
        body.setTextColor(ContextCompat.getColor(a, R.color.slate_500));
        body.setPadding(0, RosterFormat.dp(a, 4), 0, RosterFormat.dp(a, 20));
        root.addView(body);

        Button allow = new Button(a);
        allow.setText(R.string.notify_allow);
        allow.setAllCaps(false);
        allow.setTextSize(16f);
        allow.setTypeface(null, Typeface.BOLD);
        allow.setTextColor(Color.WHITE);
        GradientDrawable ab = new GradientDrawable();
        ab.setColor(ContextCompat.getColor(a, R.color.navy_600));
        ab.setCornerRadius(RosterFormat.dp(a, 14));
        allow.setBackground(ab);
        allow.setStateListAnimator(null);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, RosterFormat.dp(a, 52));
        alp.bottomMargin = RosterFormat.dp(a, 8);
        root.addView(allow, alp);

        Button later = new Button(a);
        later.setText(R.string.notify_later);
        later.setAllCaps(false);
        later.setTextSize(16f);
        later.setTypeface(null, Typeface.BOLD);
        later.setTextColor(ContextCompat.getColor(a, R.color.navy_600));
        later.setBackgroundColor(Color.TRANSPARENT);
        later.setStateListAnimator(null);
        root.addView(later, new LinearLayout.LayoutParams(-1, RosterFormat.dp(a, 52)));

        allow.setOnClickListener(v -> {
            pref = ON;
            dialog.dismiss();
            requestPermissionIfNeeded(a);
            Feedback.show(a, R.string.toast_notifications_on);
        });
        later.setOnClickListener(v -> {
            pref = LATER;
            dialog.dismiss();
        });
        dialog.setOnCancelListener(d -> pref = LATER);

        dialog.setContentView(root);
        View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) sheet.setBackgroundColor(Color.TRANSPARENT);
        if (dialog.getWindow() != null) dialog.getWindow().setDimAmount(0.4f);
        dialog.show();
    }

    private static void requestPermissionIfNeeded(Activity a) {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(a, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(a, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 77);
        }
    }

    // ---- the alert itself ----

    /** Rings, buzzes and posts a system notification, but only if the student turned alerts on. */
    static void alert(Context c, String message) {
        if (!ON.equals(pref)) return;
        buzz(c);
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    c.getString(R.string.notify_channel_name), NotificationManager.IMPORTANCE_HIGH);
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(c, LoginActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, flags);

        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_bell)
                .setContentTitle(c.getString(R.string.app_name))
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_SOUND)
                .setAutoCancel(true)
                .setContentIntent(pi);
        try {
            NotificationManagerCompat.from(c).notify(1001, b.build());
        } catch (SecurityException ignored) {
            // permission revoked between the check and the call
        }
    }

    @SuppressWarnings("deprecation")
    private static void buzz(Context c) {
        Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        long[] pattern = {0, 60, 40, 60};
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            v.vibrate(pattern, -1);
        }
    }
}
