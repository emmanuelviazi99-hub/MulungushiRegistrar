package zm.ac.mulungushi.registrar;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * Shared bottom navigation (Home / My group or Roster / Sync) so every
 * in-app screen has the same bar, as in the prototype. Pushed screens
 * (Edit details, Request group change, Requests) highlight Home.
 */
final class BottomNav {

    static final int HOME = 0;
    static final int MIDDLE = 1;
    static final int SYNC = 2;

    private BottomNav() {}

    static void bindStudent(Activity a, int selected, String studentNumber) {
        style(a);
        boolean root = a instanceof StudentHomeActivity;
        setup(a, selected, a.getString(R.string.nav_my_group), R.drawable.ic_user);

        a.findViewById(R.id.navHome).setOnClickListener(v -> {
            if (root) return;
            Intent i = new Intent(a, StudentHomeActivity.class)
                    .putExtra(StudentHomeActivity.EXTRA_STUDENT_NUMBER, studentNumber)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            go(a, i, false);
        });
        a.findViewById(R.id.navMiddle).setOnClickListener(v -> {
            if (selected == MIDDLE) return;
            Intent i = new Intent(a, MyGroupActivity.class)
                    .putExtra(StudentHomeActivity.EXTRA_STUDENT_NUMBER, studentNumber);
            go(a, i, !root);
        });
        a.findViewById(R.id.navSync).setOnClickListener(v -> {
            if (selected == SYNC) return;
            Intent i = new Intent(a, SyncActivity.class)
                    .putExtra(StudentHomeActivity.EXTRA_STUDENT_NUMBER, studentNumber)
                    .putExtra(SyncActivity.EXTRA_ROLE, SyncActivity.ROLE_STUDENT);
            go(a, i, !root);
        });
    }

    static void bindLecturer(Activity a, int selected) {
        style(a);
        boolean root = a instanceof LecturerDashboardActivity;
        setup(a, selected, a.getString(R.string.nav_roster), R.drawable.ic_list);

        a.findViewById(R.id.navHome).setOnClickListener(v -> {
            if (root) return;
            Intent i = new Intent(a, LecturerDashboardActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            go(a, i, false);
        });
        a.findViewById(R.id.navMiddle).setOnClickListener(v -> {
            if (selected == MIDDLE) return;
            go(a, new Intent(a, LecturerRosterActivity.class), !root);
        });
        a.findViewById(R.id.navSync).setOnClickListener(v -> {
            if (selected == SYNC) return;
            Intent i = new Intent(a, SyncActivity.class)
                    .putExtra(SyncActivity.EXTRA_ROLE, SyncActivity.ROLE_LECTURER);
            go(a, i, !root);
        });
    }

    private static void go(Activity a, Intent intent, boolean finishCurrent) {
        a.startActivity(intent);
        a.overridePendingTransition(R.anim.page_fade_in, R.anim.hold);
        if (finishCurrent) a.finish();
    }

    private static void setup(Activity a, int selected, String middleLabel, int middleIcon) {
        ((TextView) a.findViewById(R.id.navMiddleLabel)).setText(middleLabel);
        ((ImageView) a.findViewById(R.id.navMiddleIcon)).setImageResource(middleIcon);
        tint(a, R.id.navHomeIcon, R.id.navHomeLabel, selected == HOME);
        tint(a, R.id.navMiddleIcon, R.id.navMiddleLabel, selected == MIDDLE);
        tint(a, R.id.navSyncIcon, R.id.navSyncLabel, selected == SYNC);
    }

    private static void tint(Activity a, int iconId, int labelId, boolean on) {
        int color = ContextCompat.getColor(a, on ? R.color.navy_600 : R.color.slate_400);
        ((ImageView) a.findViewById(iconId)).setColorFilter(color);
        TextView label = a.findViewById(labelId);
        label.setTextColor(color);
        label.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    /** Page-coloured status bar with dark icons, like the prototype. */
    private static void style(Activity a) {
        Window w = a.getWindow();
        w.setStatusBarColor(ContextCompat.getColor(a, R.color.page_bg));
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
        c.setAppearanceLightStatusBars(true);
    }
}
