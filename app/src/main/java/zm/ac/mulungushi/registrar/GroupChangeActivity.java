package zm.ac.mulungushi.registrar;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.lifecycle.ViewModelProvider;

/**
 * "Request for Group" (no group yet) or "Request for Group Change" (already in a
 * group): View layer only. It draws GroupViewModel's Screen and hands it the
 * student's choice, Send and Withdraw. The rules (which wording, which groups,
 * one open request, not your own group) are in GroupRequestRules; the server
 * checks them again. Nothing moves until a lecturer approves in RequestsActivity.
 */
public class GroupChangeActivity extends AppCompatActivity {

    private GroupViewModel vm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_change);
        ConnectivityBanner.attach(this);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        Accessibility.explain(findViewById(R.id.btnBack), R.string.why_back);
        Accessibility.explain(findViewById(R.id.buttonCancelRequest), R.string.why_cancel_group);

        Student me = RosterUi.meOrSignIn(this);
        if (me == null) return;
        RosterFormat.bindAvatar(this, me.name, SignOutSheet.studentDetail(this, me.number));
        BottomNav.bindStudent(this, BottomNav.HOME, me.number);

        // errors are read out by TalkBack as soon as they appear
        ViewCompat.setAccessibilityLiveRegion(findViewById(R.id.textError), ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE);

        findViewById(R.id.buttonCancelRequest).setOnClickListener(v -> vm.cancel());
        findViewById(R.id.buttonSubmit).setOnClickListener(v -> vm.submit());

        vm = new ViewModelProvider(this).get(GroupViewModel.class);
        vm.getScreen().observe(this, this::render);
        vm.getResults().observe(this, e -> {
            GroupViewModel.Result r = e.take();
            if (r != null) onResult(r);
        });
        vm.getLoadFailed().observe(this, e -> {
            RosterApi.Outcome o = e.take();
            if (o != null) RosterUi.refreshFailed(this, o, false);
        });
        RosterFormat.stagger(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (vm != null) vm.load(); // group totals and any open request as the server has them now
    }

    // ---------------------------------------------------------------- drawing

    private void render(GroupViewModel.Screen s) {
        if (s == null) return;
        boolean first = s.mode == GroupRequestRules.Mode.FIRST_GROUP;
        String title = getString(first ? R.string.request_group_first_title : R.string.request_group_change_title);
        ((TextView) findViewById(R.id.topBarTitle)).setText(title);
        setTitle(title); // the window title TalkBack announces when the screen opens
        ((TextView) findViewById(R.id.textCurrentGroup)).setText(s.assigned ? s.me.group : getString(R.string.no_group_yet));
        Accessibility.explain(findViewById(R.id.buttonSubmit), first ? R.string.why_submit_first_group : R.string.why_submit_group);

        findViewById(R.id.pendingBlock).setVisibility(s.hasOpenRequest ? View.VISIBLE : View.GONE);
        findViewById(R.id.chooseBlock).setVisibility(s.hasOpenRequest ? View.GONE : View.VISIBLE);

        if (s.hasOpenRequest) {
            ((TextView) findViewById(R.id.textPendingMove)).setText(getString(
                    first ? R.string.join_group : R.string.move_to_group, s.me.pendingGroup));
            TextView pill = findViewById(R.id.pillPending);
            RosterFormat.addDot(pill, getResources().getColor(R.color.indigo_600));
            String on = s.me.pendingGroupOn == null ? "today" : s.me.pendingGroupOn;
            String note = getString(R.string.requested_on_fmt, on) + " " + getString(R.string.group_pending_confirm);
            if (s.assigned) note += " " + getString(R.string.group_stay_fmt, s.me.group);
            ((TextView) findViewById(R.id.textRequestedNote)).setText(note);
            Button cancel = findViewById(R.id.buttonCancelRequest);
            cancel.setEnabled(!s.busy);
        } else {
            buildOptions(s);
        }
        updateSubmit(s);
    }

    private void buildOptions(GroupViewModel.Screen s) {
        LinearLayout options = findViewById(R.id.groupOptions);
        options.removeAllViews();
        for (GroupViewModel.Option o : s.options) options.addView(buildRow(o, o.group.equals(s.choice), s.busy));
    }

    private View buildRow(GroupViewModel.Option o, boolean selected, boolean busy) {
        int dp8 = dp(8), dp14 = dp(14);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp14, dp14, dp14, dp14);
        row.setMinimumHeight(dp(48));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp8;
        row.setLayoutParams(rowLp);
        row.setBackgroundResource(selected ? R.drawable.bg_card_selected : R.drawable.bg_card_plain);
        row.setElevation(dp(1));
        row.setEnabled(!o.full && !busy);
        row.setAlpha(o.full ? 0.55f : 1f);

        FrameLayout dot = new FrameLayout(this);
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
        dot.setBackgroundResource(R.drawable.bg_radio_off);
        View innerDot = new View(this);
        FrameLayout.LayoutParams innerLp = new FrameLayout.LayoutParams(dp(10), dp(10));
        innerLp.gravity = Gravity.CENTER;
        innerDot.setLayoutParams(innerLp);
        innerDot.setBackgroundResource(R.drawable.dot_navy);
        innerDot.setVisibility(selected ? View.VISIBLE : View.GONE);
        dot.addView(innerDot);
        row.addView(dot);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMarginStart(dp(12));
        textCol.setLayoutParams(textLp);

        TextView title = new TextView(this);
        title.setText(o.group);
        title.setTextColor(getResources().getColor(R.color.navy_900));
        title.setTextSize(14.5f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        textCol.addView(title);

        String detail = o.full ? getString(R.string.group_full_note)
                : getString(R.string.group_places_filled, o.count, StudentRepository.CAPACITY);
        TextView sub = new TextView(this);
        sub.setText(detail);
        sub.setTextColor(getResources().getColor(R.color.slate_600));
        sub.setTextSize(12f);
        textCol.addView(sub);
        row.addView(textCol);

        // TalkBack: "G02, 6 of 15 places filled, selected"; a full group says so and can't be picked
        row.setSelected(selected);
        row.setContentDescription(o.group + ", " + detail + ", "
                + getString(selected ? R.string.a11y_selected : R.string.a11y_not_selected));
        if (!o.full) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> vm.choose(o.group));
        }
        return row;
    }

    private void updateSubmit(GroupViewModel.Screen s) {
        Button submit = findViewById(R.id.buttonSubmit);
        boolean ready = s.choice != null && !s.busy;
        submit.setEnabled(ready);
        submit.setBackgroundTintList(ColorStateList.valueOf(getResources().getColor(ready ? R.color.navy_600 : R.color.slate_100)));
        submit.setTextColor(getResources().getColor(ready ? R.color.white : R.color.slate_500));
        if (s.busy && !s.hasOpenRequest) submit.setText(R.string.action_saving);
        else if (s.choice == null) submit.setText(R.string.action_choose_group_first);
        else submit.setText(getString(R.string.request_group_fmt, s.choice));
        if (s.choice != null || s.busy) hideError();
    }

    // ---------------------------------------------------------------- outcomes

    private void onResult(GroupViewModel.Result r) {
        switch (r.action) {
            case SENT:
                if (r.outcome.ok) {
                    Feedback.postForNext(RosterUi.okText(this, r.outcome, R.string.toast_request_sent_pending));
                    Notifier.askSoon = true;
                    finish();
                } else if (!RosterUi.signedOut(this, r.outcome)) {
                    showError(RosterUi.message(this, r.outcome)); // e.g. a request was already open: it is shown
                }
                break;
            case CANCELLED:
                if (r.outcome.ok || r.outcome.is("REQUEST_NOT_FOUND")) {
                    // REQUEST_NOT_FOUND: the lecturer answered it first; home shows the answer
                    Feedback.postForNext(r.outcome.ok
                            ? RosterUi.okText(this, r.outcome, R.string.toast_request_cancelled_dot)
                            : getString(R.string.error_request_gone));
                    finish();
                } else {
                    RosterUi.report(this, r.outcome);
                }
                break;
            case NOT_SENT:
                if (r.outcome != null && RosterUi.signedOut(this, r.outcome)) return;
                switch (r.refusal) {
                    case OPEN_REQUEST: showError(getString(R.string.error_request_already_open)); break;
                    case SAME_GROUP:   showError(getString(R.string.error_already_in_group)); break;
                    default:           showError(getString(R.string.action_choose_group_first));
                }
                break;
        }
    }

    /** Under the picker; when the picker is hidden (an open request is shown instead), as a message. */
    private void showError(String message) {
        if (findViewById(R.id.chooseBlock).getVisibility() != View.VISIBLE) {
            Feedback.show(this, message);
            return;
        }
        TextView error = findViewById(R.id.textError);
        if (!TextUtils.equals(error.getText(), message) || error.getVisibility() != View.VISIBLE) {
            error.setText(message);
            error.setVisibility(View.VISIBLE);
        }
    }

    private void hideError() {
        findViewById(R.id.textError).setVisibility(View.GONE);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
