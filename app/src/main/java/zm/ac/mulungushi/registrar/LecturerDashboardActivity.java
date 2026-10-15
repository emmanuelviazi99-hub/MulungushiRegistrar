package zm.ac.mulungushi.registrar;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Ported from LecturerDashboard. The lecturer's Home tab: counts from the
 * server (reloaded each time the screen opens), a group-occupancy bar per
 * group, and quick actions leading to Requests, Sync, the roster, or straight
 * into Add student.
 *
 * "Conflicts" still comes from the Sync screen's fixed demo conflict: conflict
 * detection needs the offline sync queue (brief, Activity E), not built yet.
 */
public class LecturerDashboardActivity extends AppCompatActivity implements EditStudentSheet.Listener {

    private boolean warned;
    private final Runnable redraw = this::render;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lecturer_dashboard);
        ConnectivityBanner.attach(this);
        LiveRefresh.lecturer(this);
        PullToRefresh.lecturer(this);

        RosterFormat.bindHomeAvatar(this, RosterUi.lecturerName(), SignOutSheet.lecturerDetail(this));

        findViewById(R.id.actionRequests).setOnClickListener(v ->
                startActivity(new Intent(this, RequestsActivity.class)));
        findViewById(R.id.actionSync).setOnClickListener(v ->
                startActivity(new Intent(this, SyncActivity.class).putExtra(SyncActivity.EXTRA_ROLE, SyncActivity.ROLE_LECTURER)));
        findViewById(R.id.actionAddStudent).setOnClickListener(v ->
                EditStudentSheet.forAdd().show(getSupportFragmentManager(), this));

        BottomNav.bindLecturer(this, BottomNav.HOME);

        render();
        RosterFormat.stagger(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        StudentRepository.getInstance().addListener(redraw);
        render();
        // counts, groups and open requests as the server has them now
        RosterApi.get().loadRoster(outcome -> {
            if (isFinishing() || isDestroyed()) return;
            if (!outcome.ok) {
                RosterUi.refreshFailed(this, outcome, warned);
                warned = true;
            }
            render();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        StudentRepository.getInstance().removeListener(redraw);
    }

    @Override
    public void onSaved(String toastMessage) {
        Feedback.show(this, toastMessage);
        render();
    }

    private void render() {
        ((TextView) findViewById(R.id.textWelcome)).setText(
                getString(R.string.welcome_lecturer_fmt, RosterUi.lecturerName()));

        StudentRepository repo = StudentRepository.getInstance();
        int activeCount = repo.getActive().size();
        int groupsFull = 0;
        for (String g : StudentRepository.GROUPS) if (repo.groupCount(g) >= StudentRepository.CAPACITY) groupsFull++;
        int requestCount = repo.pendingRequestCount();
        int conflictCount = repo.conflictCount();

        ((TextView) findViewById(R.id.tileStudentsNum)).setText(String.valueOf(activeCount));
        ((TextView) findViewById(R.id.tileGroupsFullNum)).setText(String.valueOf(groupsFull));
        ((TextView) findViewById(R.id.tileRequestsNum)).setText(String.valueOf(requestCount));
        ((TextView) findViewById(R.id.tileConflictsNum)).setText(String.valueOf(conflictCount));

        ((TextView) findViewById(R.id.actionRequestsSub)).setText(requestCount > 0
                ? getString(R.string.action_requests_waiting_fmt, requestCount)
                : getString(R.string.action_requests_sub_none));
        int waiting = repo.getWaiting().size();
        ((TextView) findViewById(R.id.actionSyncSub)).setText(conflictCount > 0
                ? getResources().getQuantityString(R.plurals.conflicts_to_review, conflictCount, conflictCount)
                : waiting > 0 ? getResources().getQuantityString(R.plurals.sync_waiting_short, waiting, waiting)
                : getString(R.string.sync_nothing_waiting));

        LinearLayout occupancy = findViewById(R.id.occupancyList);
        occupancy.removeAllViews();
        for (String g : StudentRepository.GROUPS) {
            occupancy.addView(occupancyRow(g, repo.groupCount(g)));
        }
        View shareRow = getLayoutInflater().inflate(R.layout.row_share_summary, occupancy, false);
        shareRow.findViewById(R.id.buttonShareSummary).setOnClickListener(v -> shareSummary());
        occupancy.addView(shareRow);
    }

    /** Opens the Android Sharesheet with the group labels and counts only. No names, no student numbers. */
    private void shareSummary() {
        StudentRepository repo = StudentRepository.getInstance();
        String title = getString(R.string.share_summary_title);
        StringBuilder text = new StringBuilder(title);
        for (String g : StudentRepository.GROUPS) {
            text.append('\n').append(getString(R.string.group_summary_line,
                    g, repo.groupCount(g), StudentRepository.CAPACITY));
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, title);
        send.putExtra(Intent.EXTRA_TITLE, title);
        send.putExtra(Intent.EXTRA_TEXT, text.toString());
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_chooser_title)));
        } catch (android.content.ActivityNotFoundException e) {
            Feedback.show(this, R.string.share_failed);
        }
        // Closing the Sharesheet without picking an app just returns here. Nothing else happens.
    }

    private View occupancyRow(String group, int count) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int vPad = dp(6);
        row.setPadding(0, vPad, 0, vPad);

        TextView label = new TextView(this);
        label.setText(group);
        label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
        label.setTextColor(getResources().getColor(R.color.navy_900));
        label.setTextSize(13f);
        label.setLayoutParams(new LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(label);

        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackgroundResource(R.drawable.bg_track);
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(0, dp(6), 1f);
        trackLp.setMarginStart(dp(12));
        trackLp.setMarginEnd(dp(12));
        track.setLayoutParams(trackLp);

        boolean full = count >= StudentRepository.CAPACITY;
        View fill = new View(this);
        fill.setBackgroundResource(full ? R.drawable.bg_fill_red : R.drawable.bg_fill_navy);
        float frac = Math.min(1f, count / (float) StudentRepository.CAPACITY);
        track.addView(fill, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, frac == 0 ? 0.0001f : frac));
        RosterFormat.growBar(fill);
        View empty = new View(this);
        track.addView(empty, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f - frac == 0 ? 0.0001f : 1f - frac));
        row.addView(track);

        TextView countView = new TextView(this);
        countView.setText(count + "/" + StudentRepository.CAPACITY);
        countView.setTextColor(getResources().getColor(R.color.slate_500));
        countView.setTextSize(11.5f);
        countView.setLayoutParams(new LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT));
        countView.setGravity(Gravity.END);
        row.addView(countView);

        // TalkBack reads the whole row as one sentence, not "G01", then "14 slash 15"
        row.setContentDescription(getString(R.string.group_summary_line, group, count, StudentRepository.CAPACITY));
        androidx.core.view.ViewCompat.setScreenReaderFocusable(row, true);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        track.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        countView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        return row;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void signOut() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
