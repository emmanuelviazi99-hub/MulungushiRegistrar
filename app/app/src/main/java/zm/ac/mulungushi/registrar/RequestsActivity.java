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
 * Ported from RequestsScreen, with the Pending / Answered tabs. Answered reads
 * the in-memory log kept by StudentRepository. Approving a
 * group move or number correction re-checks capacity/uniqueness at the
 * moment of approval, same as the brief requires, and declining just clears
 * the request.
 */
public class RequestsActivity extends AppCompatActivity {

    private boolean showAnswered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_requests);

        ((TextView) findViewById(R.id.topBarTitle)).setText(R.string.requests_title);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        RosterFormat.bindAvatar(this, "Bella Nyirenda", SignOutSheet.lecturerDetail(this));
        BottomNav.bindLecturer(this, BottomNav.HOME);

        findViewById(R.id.tabPending).setOnClickListener(v -> { showAnswered = false; render(); });
        findViewById(R.id.tabAnswered).setOnClickListener(v -> { showAnswered = true; render(); });

        render();
        RosterFormat.stagger(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void styleTab(TextView tab, boolean active) {
        tab.setBackgroundResource(active ? R.drawable.bg_segment_active : 0);
        tab.setTextColor(getResources().getColor(active ? R.color.navy_900 : R.color.slate_500));
    }

    private void render() {
        StudentRepository repo = StudentRepository.getInstance();
        List<Student> pending = repo.getPendingRequests();
        int pendingTotal = 0;
        for (Student s : pending) {
            if (s.pendingGroup != null) pendingTotal++;
            if (s.pendingNumber != null) pendingTotal++;
        }

        TextView sub = findViewById(R.id.topBarSub);
        sub.setVisibility(View.VISIBLE);
        sub.setText(getString(R.string.pending_requests_count, pendingTotal));

        TextView tabPending = findViewById(R.id.tabPending);
        TextView tabAnswered = findViewById(R.id.tabAnswered);
        tabPending.setText(pendingTotal > 0
                ? getString(R.string.tab_pending_count_fmt, pendingTotal)
                : getString(R.string.tab_pending));
        styleTab(tabPending, !showAnswered);
        styleTab(tabAnswered, showAnswered);

        LinearLayout list = findViewById(R.id.requestsList);
        list.removeAllViews();

        if (showAnswered) {
            List<StudentRepository.RequestRecord> done = repo.getAnswered();
            if (done.isEmpty()) {
                list.addView(emptyText(R.string.no_answered_requests));
                return;
            }
            for (StudentRepository.RequestRecord r : done) list.addView(buildAnsweredCard(r));
            return;
        }

        if (pending.isEmpty()) {
            list.addView(emptyText(R.string.no_pending_requests));
            return;
        }

        for (Student s : pending) {
            if (s.pendingGroup != null) list.addView(buildCard(s, true));
            if (s.pendingNumber != null) list.addView(buildCard(s, false));
        }
    }

    private TextView emptyText(int res) {
        TextView empty = new TextView(this);
        empty.setText(res);
        empty.setTextColor(getResources().getColor(R.color.slate_400));
        empty.setTextSize(16f);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(48), 0, dp(48));
        return empty;
    }

    /** A read-only card for a request that has already been approved or declined. */
    private View buildAnsweredCard(StudentRepository.RequestRecord r) {
        boolean isGroup = StudentRepository.RequestRecord.GROUP.equals(r.kind);
        boolean approved = StudentRepository.RequestRecord.APPROVED.equals(r.status);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card_plain);
        int pad = dp(14);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(12);
        card.setLayoutParams(cardLp);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(8));

        FrameLayout avatar = new FrameLayout(this);
        avatar.setBackgroundResource(R.drawable.bg_avatar);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView initials = new TextView(this);
        initials.setText(RosterFormat.initialsOf(r.studentName));
        initials.setTextColor(getResources().getColor(R.color.navy_700));
        initials.setTextSize(14.5f);
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
        name.setText(r.studentName);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        name.setTextColor(getResources().getColor(R.color.navy_900));
        name.setTextSize(16f);
        nameCol.addView(name);
        TextView number = new TextView(this);
        number.setText(r.studentNumber);
        number.setTextColor(getResources().getColor(R.color.slate_400));
        number.setTextSize(13.5f);
        nameCol.addView(number);
        header.addView(nameCol, nameColLp);

        TextView result = new TextView(this);
        result.setText(approved ? R.string.badge_approved : R.string.badge_declined);
        result.setBackgroundResource(approved ? R.drawable.bg_pill_green : R.drawable.bg_pill_red);
        result.setTextColor(getResources().getColor(approved ? R.color.green_700 : R.color.red_600));
        result.setTextSize(12.5f);
        result.setTypeface(result.getTypeface(), android.graphics.Typeface.BOLD);
        result.setPadding(dp(10), dp(3), dp(10), dp(3));
        header.addView(result);
        card.addView(header);

        TextView badge = new TextView(this);
        badge.setText(isGroup ? R.string.group_change_label : R.string.number_correction_label);
        badge.setBackgroundResource(isGroup ? R.drawable.bg_badge_navy : R.drawable.bg_badge_melon);
        badge.setTextColor(getResources().getColor(isGroup ? R.color.navy_700 : R.color.melon_600));
        badge.setTextSize(12.5f);
        badge.setTypeface(badge.getTypeface(), android.graphics.Typeface.BOLD);
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeLp.bottomMargin = dp(6);
        card.addView(badge, badgeLp);

        TextView change = new TextView(this);
        change.setText(getString(R.string.to_arrow_fmt, r.from, r.to));
        change.setTextColor(getResources().getColor(R.color.navy_900));
        change.setTextSize(14.5f);
        card.addView(change);

        if (r.reason != null) {
            TextView reason = new TextView(this);
            reason.setText("“" + r.reason + "”");
            reason.setTextColor(getResources().getColor(R.color.slate_500));
            reason.setTextSize(13.5f);
            reason.setPadding(0, dp(2), 0, 0);
            card.addView(reason);
        }

        TextView when = new TextView(this);
        when.setText(getString(R.string.requested_on_fmt, r.requestedOn));
        when.setTextColor(getResources().getColor(R.color.slate_500));
        when.setTextSize(13f);
        when.setPadding(0, dp(2), 0, 0);
        card.addView(when);
        return card;
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
        initials.setTextSize(14.5f);
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
        name.setTextSize(16f);
        nameCol.addView(name);
        TextView number = new TextView(this);
        number.setText(s.number);
        number.setTextColor(getResources().getColor(R.color.slate_400));
        number.setTextSize(13.5f);
        nameCol.addView(number);
        header.addView(nameCol, nameColLp);
        card.addView(header);

        // kind badge
        TextView badge = new TextView(this);
        badge.setText(isGroup ? R.string.group_change_label : R.string.number_correction_label);
        badge.setBackgroundResource(isGroup ? R.drawable.bg_badge_navy : R.drawable.bg_badge_melon);
        badge.setTextColor(getResources().getColor(isGroup ? R.color.navy_700 : R.color.melon_600));
        badge.setTextSize(12.5f);
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
        change.setTextSize(14.5f);
        LinearLayout.LayoutParams changeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        changeLp.bottomMargin = dp(10);
        changeLp.bottomMargin = dp(2);
        card.addView(change, changeLp);

        if (!isGroup && s.pendingNumberReason != null) {
            TextView reason = new TextView(this);
            reason.setText("“" + s.pendingNumberReason + "”");
            reason.setTextColor(getResources().getColor(R.color.slate_500));
            reason.setTextSize(13.5f);
            card.addView(reason);
        }

        TextView meta = new TextView(this);
        String on = isGroup ? s.pendingGroupOn : s.pendingNumberOn;
        String metaText = getString(R.string.requested_on_fmt, on == null ? "today" : on);
        if (isGroup) {
            metaText += " " + getString(R.string.group_places_fmt, s.pendingGroup,
                    StudentRepository.getInstance().groupCount(s.pendingGroup), StudentRepository.CAPACITY);
        }
        meta.setText(metaText);
        meta.setTextColor(getResources().getColor(R.color.slate_500));
        meta.setTextSize(13f);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        metaLp.bottomMargin = dp(10);
        card.addView(meta, metaLp);

        TextView error = new TextView(this);
        error.setTextColor(getResources().getColor(R.color.red_600));
        error.setTextSize(13.5f);
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
            Feedback.show(this, R.string.toast_request_declined);
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
                Feedback.show(this, R.string.toast_request_approved);
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
