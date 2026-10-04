package zm.ac.mulungushi.registrar;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/**
 * Ported from RequestsScreen. Simplified to pending-only — the prototype's
 * "Answered" history tab needs a request log with ids/dates/reasons that
 * this build doesn't keep (see README, "What's simplified"). Approving a
 * group move or number correction re-checks capacity/uniqueness at the
 * moment of approval, same as the brief requires, and declining just clears
 * the request.
 */
public class RequestsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_requests);

        ((TextView) findViewById(R.id.topBarTitle)).setText(R.string.requests_title);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        StudentRepository repo = StudentRepository.getInstance();
        List<Student> pending = repo.getPendingRequests();

        TextView sub = findViewById(R.id.topBarSub);
        sub.setVisibility(View.VISIBLE);
        sub.setText(getString(R.string.pending_requests_count, pending.size()));

        LinearLayout list = findViewById(R.id.requestsList);
        list.removeAllViews();

        if (pending.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.no_pending_requests);
            empty.setTextColor(getResources().getColor(R.color.slate_400));
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(48), 0, dp(48));
            list.addView(empty);
            return;
        }

        for (Student s : pending) {
            if (s.pendingGroup != null) list.addView(buildCard(s, true));
            if (s.pendingNumber != null) list.addView(buildCard(s, false));
        }
    }

    private View buildCard(Student s, boolean isGroup) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card_plain);
        int pad = dp(14);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(12);
        card.setLayoutParams(cardLp);

        // header: avatar + name/number
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(8));

        FrameLayout avatar = new FrameLayout(this);
        avatar.setBackgroundResource(R.drawable.bg_avatar);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView initials = new TextView(this);
        initials.setText(RosterFormat.initialsOf(s.name));
        initials.setTextColor(getResources().getColor(R.color.navy_700));
        initials.setTextSize(13);
        FrameLayout.LayoutParams initialsLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        initialsLp.gravity = Gravity.CENTER;
        avatar.addView(initials, initialsLp);
        header.addView(avatar);

        LinearLayout nameCol = new LinearLayout(this);
        nameCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams nameColLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nameColLp.setMarginStart(dp(10));
        TextView name = new TextView(this);
        name.setText(s.name);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        name.setTextColor(getResources().getColor(R.color.navy_900));
        name.setTextSize(14);
        nameCol.addView(name);
        TextView number = new TextView(this);
        number.setText(s.number);
        number.setTextColor(getResources().getColor(R.color.slate_400));
        number.setTextSize(12);
        nameCol.addView(number);
        header.addView(nameCol, nameColLp);
        card.addView(header);

        // kind badge
        TextView badge = new TextView(this);
        badge.setText(isGroup ? R.string.group_change_label : R.string.number_correction_label);
        badge.setBackgroundResource(isGroup ? R.drawable.bg_badge_navy : R.drawable.bg_badge_melon);
        badge.setTextColor(getResources().getColor(isGroup ? R.color.navy_700 : R.color.melon_600));
        badge.setTextSize(11);
        badge.setTypeface(badge.getTypeface(), android.graphics.Typeface.BOLD);
        int bh = dp(8), bv = dp(3);
        badge.setPadding(bh, bv, bh, bv);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeLp.bottomMargin = dp(6);
        card.addView(badge, badgeLp);

        // from -> to
        TextView change = new TextView(this);
        change.setText(getString(R.string.to_arrow_fmt,
                isGroup ? s.group : s.number, isGroup ? s.pendingGroup : s.pendingNumber));
        change.setTextColor(getResources().getColor(R.color.navy_900));
        change.setTextSize(13);
        LinearLayout.LayoutParams changeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        changeLp.bottomMargin = dp(10);
        card.addView(change, changeLp);

        TextView error = new TextView(this);
        error.setTextColor(getResources().getColor(R.color.red_600));
        error.setTextSize(12);
        error.setVisibility(View.GONE);
        LinearLayout.LayoutParams errorLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        errorLp.bottomMargin = dp(8);
        card.addView(error, errorLp);

        // buttons
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button decline = new Button(this);
        decline.setText(R.string.action_decline);
        decline.setAllCaps(false);
        decline.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(R.color.slate_100)));
        decline.setTextColor(getResources().getColor(R.color.navy_900));
        LinearLayout.LayoutParams declineLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        declineLp.setMarginEnd(dp(6));
        decline.setOnClickListener(v -> {
            if (isGroup) StudentRepository.getInstance().declineGroupChange(s.id);
            else StudentRepository.getInstance().declineNumberCorrection(s.id);
            Toast.makeText(this, R.string.toast_request_declined, Toast.LENGTH_SHORT).show();
            render();
        });
        buttons.addView(decline, declineLp);

        Button approve = new Button(this);
        approve.setText(R.string.action_approve);
        approve.setAllCaps(false);
        approve.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(R.color.navy_600)));
        approve.setTextColor(getResources().getColor(R.color.white));
        LinearLayout.LayoutParams approveLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        approveLp.setMarginStart(dp(6));
        approve.setOnClickListener(v -> {
            boolean ok = isGroup
                    ? StudentRepository.getInstance().approveGroupChange(s.id)
                    : StudentRepository.getInstance().approveNumberCorrection(s.id);
            if (ok) {
                Toast.makeText(this, R.string.toast_request_approved, Toast.LENGTH_SHORT).show();
                render();
            } else {
                error.setText(isGroup ? R.string.toast_group_full_decline : R.string.toast_number_taken_decline);
                error.setVisibility(View.VISIBLE);
            }
        });
        buttons.addView(approve, approveLp);

        card.addView(buttons);
        return card;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
