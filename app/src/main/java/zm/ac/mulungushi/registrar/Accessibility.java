package zm.ac.mulungushi.registrar;

import android.view.View;

import androidx.annotation.StringRes;
import androidx.appcompat.widget.TooltipCompat;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

/**
 * TalkBack helpers. Nothing here changes how a screen looks.
 *
 * "What is this for": every main feature is given a short line saying what it
 * does. With TalkBack on, touching the feature reads its name, then
 * "Double-tap to <what it is for>". Without TalkBack, pressing and holding the
 * feature shows the same line in a small bubble (a tooltip).
 */
final class Accessibility {

    private Accessibility() {}

    /** Makes TalkBack say "Button" for a clickable view that is not a Button (a TextView or a row). */
    static void asButton(View v) {
        install(v, true, null);
    }

    /**
     * A button or tile: TalkBack says "<name>, Button. Double-tap to <whatFor>",
     * and pressing and holding it shows whatFor in a bubble.
     */
    static void explain(View v, @StringRes int whatFor) {
        if (v == null) return;
        CharSequence text = v.getContext().getString(whatFor);
        install(v, true, text);
        TooltipCompat.setTooltipText(v, text);
    }

    /**
     * A list row: TalkBack says "Double-tap to <whatFor>" after the row's own
     * description. No bubble, so pressing and holding a row stays free.
     */
    static void explainRow(View v, CharSequence whatFor) {
        install(v, false, whatFor);
    }

    /** Undoes explain() on a view that has stopped being clickable. */
    static void plain(View v) {
        if (v == null) return;
        ViewCompat.setAccessibilityDelegate(v, null);
        TooltipCompat.setTooltipText(v, null);
    }

    private static void install(View v, boolean button, CharSequence clickLabel) {
        if (v == null) return;
        ViewCompat.setAccessibilityDelegate(v, new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (button) info.setClassName(android.widget.Button.class.getName());
                if (clickLabel != null) {
                    // The words TalkBack uses in "Double-tap to ...".
                    info.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                            AccessibilityNodeInfoCompat.ACTION_CLICK, clickLabel));
                    // The bubble's words are already said by "Double-tap to ...": don't read them twice.
                    info.setTooltipText(null);
                }
            }
        });
    }

    /** "202301045" -> "2 0 2 3 0 1 0 4 5", so TalkBack reads digits, not one huge number. */
    static String spokenDigits(String digits) {
        if (digits == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(digits.charAt(i));
        }
        return sb.toString();
    }
}
