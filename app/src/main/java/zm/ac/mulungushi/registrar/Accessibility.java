package zm.ac.mulungushi.registrar;

import android.view.View;

import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

/** TalkBack helpers. Nothing here changes how a screen looks. */
final class Accessibility {

    private Accessibility() {}

    /** Makes TalkBack say "Button" for a clickable view that is not a Button (a TextView or a row). */
    static void asButton(View v) {
        ViewCompat.setAccessibilityDelegate(v, new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Button.class.getName());
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
