package zm.ac.mulungushi.registrar;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;

/** Dark rounded confirmation pill with a green tick, like the prototype's toast. */
final class Feedback {

    private static String nextScreenMessage;

    private Feedback() {}

    /** For screens that close right after an action: the message appears on the screen we land on. */
    static void postForNext(String message) {
        nextScreenMessage = message;
    }

    /** Shows and clears any message left by the previous screen. */
    static void showPending(Activity a) {
        if (nextScreenMessage == null) return;
        String m = nextScreenMessage;
        nextScreenMessage = null;
        show(a, m);
    }

    static void show(Activity a, String message) {
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        try {
            showStyled(a, message);
        } catch (RuntimeException e) {
            // never let a confirmation message take the app down
            android.widget.Toast.makeText(a, message, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private static void showStyled(Activity a, String message) {
        View root = a.findViewById(android.R.id.content);
        Snackbar bar = Snackbar.make(root, message, 2300);
        bar.setAnimationMode(Snackbar.ANIMATION_MODE_FADE);
        View nav = a.findViewById(R.id.bottomNav);
        if (nav != null) bar.setAnchorView(nav);

        View bv = bar.getView();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ContextCompat.getColor(a, R.color.navy_900));
        bg.setCornerRadius(RosterFormat.dp(a, 28));
        bv.setBackground(bg);
        bv.setPadding(RosterFormat.dp(a, 8), 0, RosterFormat.dp(a, 8), 0);
        bv.setElevation(RosterFormat.dp(a, 4));

        // Snackbars default to full width. Shrink to the content and centre it.
        ViewGroup.LayoutParams lp = bv.getLayoutParams();
        if (lp instanceof CoordinatorLayout.LayoutParams) {
            CoordinatorLayout.LayoutParams c = (CoordinatorLayout.LayoutParams) lp;
            c.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            c.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
            bv.setLayoutParams(c);
        } else if (lp instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams f = (FrameLayout.LayoutParams) lp;
            f.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            f.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
            bv.setLayoutParams(f);
        }

        TextView tv = bv.findViewById(com.google.android.material.R.id.snackbar_text);
        if (tv != null) {
            tv.setTextColor(ContextCompat.getColor(a, R.color.white));
            tv.setTextSize(13.5f);
            tv.setTypeface(null, Typeface.BOLD);
            tv.setMaxLines(2);
            android.graphics.drawable.Drawable tick = ContextCompat.getDrawable(a, R.drawable.ic_check_circle);
            if (tick != null) {
                tick = tick.mutate();
                tick.setTint(0xFF4ADE80);
                tick.setBounds(0, 0, RosterFormat.dp(a, 16), RosterFormat.dp(a, 16));
                tv.setCompoundDrawablesRelative(tick, null, null, null);
                tv.setCompoundDrawablePadding(RosterFormat.dp(a, 8));
            }
        }
        bar.show();
    }

    static void show(Activity a, int messageRes) {
        show(a, a.getString(messageRes));
    }
}
