package zm.ac.mulungushi.registrar;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.textfield.TextInputLayout;

/**
 * Keeps the field you are typing in visible when the keyboard opens.
 *
 * Why it is needed: the app targets Android 15+, where screens draw edge to edge and the system
 * no longer shrinks the page for the keyboard by itself. So the keyboard sits on top of the lower
 * fields. LoginActivity already handles this; this class does the same for any other form screen.
 *
 * Use: call KeyboardInsets.apply(this) right after setContentView(...).
 * The first child of the screen must be the ScrollView (true for activity_register.xml).
 */
public final class KeyboardInsets {

    private KeyboardInsets() {}

    public static void apply(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) return;
        View root = content.getChildAt(0);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            // bottom space = keyboard when it is open, otherwise the nav bar
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                v.post(() -> scrollFocusedIntoView(v));
            }
            return WindowInsetsCompat.CONSUMED;
        });

        // moving between fields with Next/tap while the keyboard is open
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            if (newFocus != null) root.post(() -> scrollFocusedIntoView(root));
        });

        ViewCompat.requestApplyInsets(root);
    }

    private static void scrollFocusedIntoView(View root) {
        View focused = root.findFocus();
        if (focused == null) return;

        // scroll the whole field (label + box + error text), not just the text box inside it
        View target = focused;
        for (int i = 0; i < 3 && target.getParent() instanceof View; i++) {
            View parent = (View) target.getParent();
            if (parent instanceof TextInputLayout) {
                target = parent;
                break;
            }
            target = parent;
        }
        if (!(target instanceof TextInputLayout)) target = focused;

        float density = root.getResources().getDisplayMetrics().density;
        Rect r = new Rect(0, 0, target.getWidth(), target.getHeight() + (int) (24 * density));
        target.requestRectangleOnScreen(r, false);
    }
}
