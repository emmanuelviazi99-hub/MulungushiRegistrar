package zm.ac.mulungushi.registrar;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;

/** Small shared helpers for the newer screens. */
final class RosterFormat {

    private RosterFormat() {}

    static String initialsOf(String name) {
        String t = name == null ? "" : name.trim();
        if (t.isEmpty()) return "?";
        String[] parts = t.split("\\s+");
        String first = String.valueOf(parts[0].charAt(0));
        if (parts.length > 1) return (first + parts[parts.length - 1].charAt(0)).toUpperCase();
        return first.toUpperCase();
    }

    static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }

    /** Shows the initials avatar on the shared top bar; tapping it signs out. */
    static void bindAvatar(Activity a, String name, String detail) {
        TextView v = a.findViewById(R.id.topBarAvatar);
        if (v == null) return;
        v.setText(initialsOf(name));
        v.setVisibility(View.VISIBLE);
        v.setOnClickListener(x -> SignOutSheet.show(a, name, detail));
    }

    /** For the Home screens, whose avatar button has its own id. */
    static void bindHomeAvatar(Activity a, String name, String detail) {
        TextView v = a.findViewById(R.id.buttonSignOut);
        if (v == null) return;
        v.setText(initialsOf(name));
        v.setOnClickListener(x -> SignOutSheet.show(a, name, detail));
    }

    /** Tab screens (My group, Sync) have no back arrow, like the prototype. */
    static void hideBack(Activity a) {
        View back = a.findViewById(R.id.btnBack);
        if (back == null) return;
        back.setVisibility(View.GONE);
        View parent = (View) back.getParent();
        parent.setPadding(dp(a, 20), parent.getPaddingTop(), parent.getPaddingRight(), parent.getPaddingBottom());
    }

    private static final android.view.animation.Interpolator EASE_OUT =
            new android.view.animation.PathInterpolator(0.22f, 0.61f, 0.36f, 1f);

    /** Progress bars grow from the left in 0.5s, like the prototype's bar-grow. */
    static void growBar(View fill) {
        fill.setPivotX(0f);
        fill.setScaleX(0f);
        fill.animate().scaleX(1f).setDuration(500).setInterpolator(EASE_OUT).start();
    }

    /** Children of the screen's scroll content settle in one after another (0.32s, 40ms apart). */
    static void stagger(Activity a) {
        View scroll = a.findViewById(R.id.screenScroll);
        if (!(scroll instanceof android.widget.ScrollView)) return;
        View child = ((android.widget.ScrollView) scroll).getChildAt(0);
        if (!(child instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup g = (android.view.ViewGroup) child;
        float shift = dp(a, 4);
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            v.setAlpha(0f);
            v.setTranslationY(shift);
            v.animate().alpha(1f).translationY(0f)
                    .setStartDelay(Math.min(i, 5) * 40L).setDuration(320).setInterpolator(EASE_OUT).start();
        }
    }

    /** Coloured dot in front of a status pill's text. */
    static void addDot(TextView pill, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        int s = dp(pill.getContext(), 7);
        d.setSize(s, s);
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null);
        pill.setCompoundDrawablePadding(dp(pill.getContext(), 6));
        pill.setGravity(android.view.Gravity.CENTER_VERTICAL);
    }
}
