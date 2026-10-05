package zm.ac.mulungushi.registrar;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Ported from GroupChangeScreen. A student picks one of the groups they are
 * not already in; submitting only records the request (StudentRepository
 * .requestGroupChange) — nothing moves until a lecturer approves it in
 * RequestsActivity. If one request is already open, this screen shows that
 * instead of the picker, with a Cancel request button.
 */
public class GroupChangeActivity extends AppCompatActivity {

    private Student student;
    private String choice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_change);
        ConnectivityBanner.attach(this);

        ((TextView) findViewById(R.id.topBarTitle)).setText(R.string.request_group_change_title);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        String number = getIntent().getStringExtra(StudentHomeActivity.EXTRA_STUDENT_NUMBER);
        if (number == null) number = StudentRepository.DEMO_NUMBER_FALLBACK;
        student = StudentRepository.getInstance().findOrCreateDemoStudent(number);
        RosterFormat.bindAvatar(this, student.name, SignOutSheet.studentDetail(this, student.number));
        BottomNav.bindStudent(this, BottomNav.HOME, student.number);

        render();
        RosterFormat.stagger(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        boolean assigned = !StudentRepository.UNASSIGNED.equals(student.group);
        ((TextView) findViewById(R.id.textCurrentGroup)).setText(
                assigned ? student.group : getString(R.string.no_group_yet));

        View pendingBlock = findViewById(R.id.pendingBlock);
        View chooseBlock = findViewById(R.id.chooseBlock);
        boolean hasPending = student.pendingGroup != null;
        pendingBlock.setVisibility(hasPending ? View.VISIBLE : View.GONE);
        chooseBlock.setVisibility(hasPending ? View.GONE : View.VISIBLE);

        if (hasPending) {
            ((TextView) findViewById(R.id.textPendingMove)).setText(getString(R.string.move_to_group, student.pendingGroup));
            findViewById(R.id.buttonCancelRequest).setOnClickListener(v -> {
                StudentRepository.getInstance().cancelGroupChange(student.id);
                Feedback.postForNext(getString(R.string.toast_request_cancelled_dot));
                finish();
            });
        } else {
            buildOptions();
        }
    }

    private void buildOptions() {
        LinearLayout options = findViewById(R.id.groupOptions);
        options.removeAllViews();
        choice = null;
        updateSubmit();

        StudentRepository repo = StudentRepository.getInstance();
        List<String> candidates = new ArrayList<>();
        for (String g : StudentRepository.GROUPS) if (!g.equals(student.group)) candidates.add(g);
        if (!StudentRepository.UNASSIGNED.equals(student.group)) candidates.add(StudentRepository.UNASSIGNED);

        for (String g : candidates) {
            boolean unassignedOption = StudentRepository.UNASSIGNED.equals(g);
            int count = unassignedOption ? 0 : repo.groupCount(g);
            boolean full = !unassignedOption && count >= StudentRepository.CAPACITY;
            options.addView(buildRow(g, count, full));
        }
    }

    private View buildRow(String group, int count, boolean full) {
        int dp8 = dp(8), dp14 = dp(14);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp14, dp14, dp14, dp14);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp8;
        row.setLayoutParams(rowLp);
        row.setBackgroundResource(R.drawable.bg_card_plain);
        row.setEnabled(!full);
        row.setAlpha(full ? 0.55f : 1f);

        android.widget.FrameLayout dot = new android.widget.FrameLayout(this);
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
        dot.setBackgroundResource(R.drawable.bg_radio_off);
        View innerDot = new View(this);
        android.widget.FrameLayout.LayoutParams innerLp =
                new android.widget.FrameLayout.LayoutParams(dp(10), dp(10));
        innerLp.gravity = Gravity.CENTER;
        innerDot.setLayoutParams(innerLp);
        innerDot.setBackgroundResource(R.drawable.dot_navy);
        innerDot.setVisibility(View.GONE);
        dot.addView(innerDot);
        row.addView(dot);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMarginStart(dp(12));
        textCol.setLayoutParams(textLp);

        TextView title = new TextView(this);
        title.setText(group);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(getResources().getColor(R.color.navy_900));
        title.setTextSize(14.5f);
        textCol.addView(title);

        TextView sub = new TextView(this);
        sub.setText(full ? getString(R.string.group_full_note)
                : getString(R.string.group_places_filled, count, StudentRepository.CAPACITY));
        sub.setTextColor(getResources().getColor(R.color.slate_500));
        sub.setTextSize(13.5f);
        textCol.addView(sub);

        row.addView(textCol);

        if (!full) {
            row.setOnClickListener(v -> {
                choice = group;
                LinearLayout options = findViewById(R.id.groupOptions);
                for (int i = 0; i < options.getChildCount(); i++) {
                    LinearLayout r = (LinearLayout) options.getChildAt(i);
                    android.widget.FrameLayout d = (android.widget.FrameLayout) r.getChildAt(0);
                    d.getChildAt(0).setVisibility(View.GONE);
                    r.setBackgroundResource(R.drawable.bg_card_plain);
                }
                innerDot.setVisibility(View.VISIBLE);
                row.setBackgroundResource(R.drawable.bg_card_selected);
                updateSubmit();
            });
        }
        return row;
    }

    private void updateSubmit() {
        android.widget.Button submit = findViewById(R.id.buttonSubmit);
        TextView error = findViewById(R.id.textError);
        error.setVisibility(View.GONE);
        if (choice == null) {
            submit.setEnabled(false);
            submit.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(R.color.slate_100)));
            submit.setTextColor(getResources().getColor(R.color.slate_400));
            submit.setText(R.string.action_choose_group_first);
        } else {
            submit.setEnabled(true);
            submit.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(R.color.navy_600)));
            submit.setTextColor(getResources().getColor(R.color.white));
            submit.setText(getString(R.string.request_group_fmt, choice));
            submit.setOnClickListener(v -> {
                StudentRepository.getInstance().requestGroupChange(student.id, choice);
                Feedback.postForNext(getString(R.string.toast_request_sent_pending));
                Notifier.askSoon = true;
                finish();
            });
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
