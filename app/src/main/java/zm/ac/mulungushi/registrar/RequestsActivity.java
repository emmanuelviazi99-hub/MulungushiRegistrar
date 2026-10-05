package zm.ac.mulungushi.registrar;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ported from RequestsScreen: Pending / Answered tabs, oldest request first
 * under Pending, newest first under Answered. Group moves are approved right
 * here (capacity is re-checked at that moment). A number correction opens the
 * roster record with the number filled in, and nothing changes until the
 * lecturer saves it, like the prototype's Review button.
 */
public class RequestsActivity extends AppCompatActivity {

    private boolean showAnswered = false;

    /** One pending request, group move or number correction. */
    private static final class Item {
        final Student student;
        final boolean group;
        final int seq;
        Item(Student s, boolean group) {
            this.student = s;
            this.group = group;
            this.seq = group ? s.pendingGroupSeq : s.pendingNumberSeq;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_requests);
        ConnectivityBanner.attach(this);

        ((TextView) findViewById(R.id.topBarTitle)).setText(R.string.requests_title);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        RosterFormat.bindAvatar(this, "B. Nyirenda", SignOutSheet.lecturerDetail(this));
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
        Feedback.showPending(this);
    }

    private void styleTab(TextView tab, boolean active) {
        tab.setBackgroundResource(active ? R.drawable.bg_segment_active : 0);
        tab.setTextColor(getResources().getColor(active ? R.color.navy_900 : R.color.slate_500));
    }

    private List<Item> pendingItems() {
        List<Item> items = new ArrayList<>();
        for (Student s : StudentRepository.getInstance().getPendingRequests()) {
            if (s.pendingGroup != null) items.add(new Item(s, true));
            if (s.pendingNumber != null) items.add(new Item(s, false));
        }
        Collections.sort(items, (a, b) -> Integer.compare(a.seq, b.seq));
        return items;
    }

    private void render() {
        StudentRepository repo = StudentRepository.getInstance();
        List<Item> pending = pendingItems();
        int pendingTotal = pending.size();

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
        for (Item it : pending) list.addView(buildCard(it));
    }

    private TextView emptyText(int res) {
        TextView empty = new TextView(this);
        empty.setText(res);
        empty.setTextColor(getResources().getColor(R.color.slate_400));
        empty.setTextSize(14f);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(56), 0, dp(56));
        return empty;
    }

    // ---------- shared pieces, sized like the prototype (14 / 12 / 11 / 13 / 12.5) ----------

    private LinearLayout newCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card_plain);
        card.setElevation(dp(2));
        int pad = dp(16);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);
        return card;
    }

    private LinearLayout newHeader(String name, String number, View trailing) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(8));

        FrameLayout avatar = new FrameLayout(this);
        avatar.setBackgroundResource(R.drawable.bg_avatar);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView initials = new TextView(this);
        initials.setText(RosterFormat.initialsOf(name));
        initials.setTextColor(getResources().getColor(R.color.navy_700));
        initials.setTextSize(14f);
        initials.setTypeface(null, Typeface.BOLD);
        FrameLayout.LayoutParams initialsLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        initialsLp.gravity = Gravity.CENTER;
        avatar.addView(initials, initialsLp);
        header.addView(avatar);

        LinearLayout nameCol = new LinearLayout(this);
        nameCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams nameColLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nameColLp.setMarginStart(dp(12));
        TextView nameView = new TextView(this);
        nameView.setText(name);
        nameView.setSingleLine(true);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nameView.setTypeface(null, Typeface.BOLD);
        nameView.setTextColor(getResources().getColor(R.color.navy_900));
        nameView.setTextSize(14f);
        nameCol.addView(nameView);
        TextView numberView = new TextView(this);
        numberView.setText(number);
        numberView.setTextColor(getResources().getColor(R.color.slate_400));
        numberView.setTextSize(12f);
        nameCol.addView(numberView);
        header.addView(nameCol, nameColLp);

        if (trailing != null) header.addView(trailing);
        return header;
    }

    private TextView kindBadge(boolean isGroup) {
        TextView badge = new TextView(this);
        badge.setText(isGroup ? R.string.group_change_label : R.string.number_correction_label);
        badge.setBackgroundResource(isGroup ? R.drawable.bg_badge_navy : R.drawable.bg_badge_melon);
        badge.setTextColor(getResources().getColor(isGroup ? R.color.navy_700 : R.color.melon_600));
        badge.setTextSize(11f);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setPadding(dp(8), dp(2), dp(8), dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        badge.setLayoutParams(lp);
        return badge;
    }

    private TextView line(String text, float sp, int colorRes, boolean medium, int bottomDp) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(getResources().getColor(colorRes));
        if (medium) tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(bottomDp);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** 48dp tall, 12dp corners, like the prototype's Button. */
    private Button actionButton(int label, int bgColor, int textColor) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15f);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(getResources().getColor(textColor));
        b.setStateListAnimator(null);
        b.setElevation(0f);
        b.setPadding(0, 0, 0, 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getResources().getColor(bgColor));
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        return b;
    }

    // ---------- pending ----------

    private View buildCard(Item it) {
        final Student s = it.student;
        final boolean isGroup = it.group;
        final StudentRepository repo = StudentRepository.getInstance();

        LinearLayout card = newCard();
        card.addView(newHeader(s.name, s.number, null));
        card.addView(kindBadge(isGroup));

        String from = isGroup ? s.group : s.number;
        String to = isGroup ? s.pendingGroup : s.pendingNumber;
        card.addView(line(getString(R.string.to_arrow_fmt, from, to), 13f, R.color.navy_900, true, 2));

        if (!isGroup && s.pendingNumberReason != null) {
            card.addView(line("“" + s.pendingNumberReason + "”", 12.5f, R.color.slate_600, false, 2));
        }

        String on = isGroup ? s.pendingGroupOn : s.pendingNumberOn;
        String meta = getString(R.string.requested_on_fmt, on == null ? "today" : on);
        if (isGroup) {
            meta += " " + getString(R.string.group_places_fmt, s.pendingGroup,
                    repo.groupCount(s.pendingGroup), StudentRepository.CAPACITY);
        } else {
            meta += " " + getString(R.string.review_hint);
        }
        card.addView(line(meta, 12f, R.color.slate_500, false, 12));

        TextView error = line("", 12.5f, R.color.red_600, false, 8);
        error.setVisibility(View.GONE);
        card.addView(error);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button decline = actionButton(R.string.action_decline, R.color.slate_100, R.color.navy_700);
        LinearLayout.LayoutParams declineLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        declineLp.setMarginEnd(dp(5));
        decline.setOnClickListener(v -> {
            if (isGroup) repo.declineGroupChange(s.id);
            else repo.declineNumberCorrection(s.id);
            Feedback.show(this, R.string.toast_request_declined);
            render();
        });
        buttons.addView(decline, declineLp);

        Button approve = actionButton(isGroup ? R.string.action_approve : R.string.action_review,
                R.color.navy_600, R.color.white);
        LinearLayout.LayoutParams approveLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        approveLp.setMarginStart(dp(5));
        approve.setOnClickListener(v -> {
            if (!isGroup) {
                startActivity(new Intent(this, LecturerRosterActivity.class)
                        .putExtra(LecturerRosterActivity.EXTRA_REVIEW_ID, s.id)
                        .putExtra(LecturerRosterActivity.EXTRA_REVIEW_NUMBER, s.pendingNumber));
                return;
            }
            if (repo.approveGroupChange(s.id)) {
                Feedback.show(this, R.string.toast_request_approved);
                render();
            } else {
                error.setText(R.string.toast_group_full_decline);
                error.setVisibility(View.VISIBLE);
            }
        });
        buttons.addView(approve, approveLp);

        card.addView(buttons);
        return card;
    }

    // ---------- answered ----------

    private View buildAnsweredCard(StudentRepository.RequestRecord r) {
        boolean isGroup = StudentRepository.RequestRecord.GROUP.equals(r.kind);
        boolean approved = StudentRepository.RequestRecord.APPROVED.equals(r.status);

        TextView result = new TextView(this);
        result.setText(approved ? R.string.badge_approved : R.string.badge_declined);
        result.setBackgroundResource(approved ? R.drawable.bg_badge_green : R.drawable.bg_badge_declined);
        result.setTextColor(getResources().getColor(approved ? R.color.green_700 : R.color.red_600));
        result.setTextSize(11f);
        result.setTypeface(null, Typeface.BOLD);
        result.setPadding(dp(8), dp(2), dp(8), dp(2));

        LinearLayout card = newCard();
        card.addView(newHeader(r.studentName, r.studentNumber, result));
        card.addView(kindBadge(isGroup));
        card.addView(line(getString(R.string.to_arrow_fmt, r.from, r.to), 13f, R.color.navy_900, true, 2));
        if (r.reason != null) {
            card.addView(line("“" + r.reason + "”", 12.5f, R.color.slate_600, false, 2));
        }
        card.addView(line(getString(R.string.requested_on_fmt, r.requestedOn), 12f, R.color.slate_500, false, 0));
        return card;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
