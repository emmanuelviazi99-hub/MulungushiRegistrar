package zm.ac.mulungushi.registrar;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * The prototype's SignOutSheet: tapping the avatar opens an account sheet
 * (who is signed in, a note about unsynced changes, Close / Sign out)
 * instead of signing out immediately.
 */
final class SignOutSheet {

    private SignOutSheet() {}

    static void show(Activity a, String name, String detail) {
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

        // who is signed in
        LinearLayout who = new LinearLayout(a);
        who.setOrientation(LinearLayout.HORIZONTAL);
        who.setGravity(Gravity.CENTER_VERTICAL);

        TextView avatar = new TextView(a);
        avatar.setBackgroundResource(R.drawable.bg_avatar_solid);
        avatar.setGravity(Gravity.CENTER);
        avatar.setText(RosterFormat.initialsOf(name));
        avatar.setTextColor(Color.WHITE);
        avatar.setTextSize(15f);
        avatar.setTypeface(null, Typeface.BOLD);
        who.addView(avatar, new LinearLayout.LayoutParams(RosterFormat.dp(a, 44), RosterFormat.dp(a, 44)));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView n = new TextView(a);
        n.setText(name);
        n.setSingleLine(true);
        n.setTextSize(17f);
        n.setTypeface(null, Typeface.BOLD);
        n.setTextColor(ContextCompat.getColor(a, R.color.navy_900));
        col.addView(n);
        TextView d = new TextView(a);
        d.setText(detail);
        d.setSingleLine(true);
        d.setTextSize(14f);
        d.setTextColor(ContextCompat.getColor(a, R.color.slate_500));
        col.addView(d);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        clp.setMarginStart(RosterFormat.dp(a, 12));
        who.addView(col, clp);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
        wlp.bottomMargin = RosterFormat.dp(a, 14);
        root.addView(who, wlp);

        TextView note = new TextView(a);
        note.setText(R.string.sign_out_note);
        note.setTextSize(14f);
        note.setTextColor(ContextCompat.getColor(a, R.color.slate_500));
        note.setPadding(0, 0, 0, RosterFormat.dp(a, 20));
        root.addView(note);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button close = new Button(a);
        close.setText(R.string.action_close);
        close.setAllCaps(false);
        close.setTextSize(15f);
        close.setTypeface(null, Typeface.BOLD);
        close.setTextColor(ContextCompat.getColor(a, R.color.navy_900));
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(ContextCompat.getColor(a, R.color.slate_100));
        cb.setCornerRadius(RosterFormat.dp(a, 14));
        close.setBackground(cb);
        close.setStateListAnimator(null);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(0, RosterFormat.dp(a, 52), 1f);
        closeLp.setMarginEnd(RosterFormat.dp(a, 5));
        row.addView(close, closeLp);

        Button out = new Button(a);
        out.setText(R.string.action_sign_out);
        out.setAllCaps(false);
        out.setTextSize(15f);
        out.setTypeface(null, Typeface.BOLD);
        out.setTextColor(Color.WHITE);
        GradientDrawable ob = new GradientDrawable();
        ob.setColor(ContextCompat.getColor(a, R.color.red_600));
        ob.setCornerRadius(RosterFormat.dp(a, 14));
        out.setBackground(ob);
        out.setStateListAnimator(null);
        LinearLayout.LayoutParams outLp = new LinearLayout.LayoutParams(0, RosterFormat.dp(a, 52), 1f);
        outLp.setMarginStart(RosterFormat.dp(a, 5));
        row.addView(out, outLp);
        root.addView(row);

        close.setOnClickListener(v -> dialog.dismiss());
        out.setOnClickListener(v -> {
            dialog.dismiss();
            Intent i = new Intent(a, LoginActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            a.startActivity(i);
            a.finish();
        });

        dialog.setContentView(root);
        View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) sheet.setBackgroundColor(Color.TRANSPARENT);
        if (dialog.getWindow() != null) dialog.getWindow().setDimAmount(0.4f);
        dialog.show();
    }

    /** Detail line under the name, like the prototype's account.detail. */
    static String studentDetail(Activity a, String number) {
        return a.getString(R.string.account_student_fmt, number);
    }

    static String lecturerDetail(Activity a) {
        return a.getString(R.string.account_lecturer_fmt, DemoAccounts.LECTURER_EMAIL);
    }
}
