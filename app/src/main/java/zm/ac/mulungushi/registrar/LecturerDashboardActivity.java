package zm.ac.mulungushi.registrar;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Ported from LecturerDashboard. The lecturer's Home tab: real counts from
 * StudentRepository, a group-occupancy bar per group, and quick actions
 * leading to Requests, Sync, the roster, or straight into Add student.
 *
 * "Conflicts" is always 0 here — conflict detection depends on the offline
 * sync queue the brief describes in Activity E, which isn't built yet.
 */
public class LecturerDashboardActivity extends AppCompatActivity implements EditStudentSheet.Listener {

    private static final String LECTURER_NAME = "Bella Nyirenda";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lecturer_dashboard);

        ((TextView) findViewById(R.id.buttonSignOut)).setText(RosterFormat.initialsOf(LECTURER_NAME));
        findViewById(R.id.buttonSignOut).setOnClickListener(v -> signOut());

        findViewById(R.id.actionRequests).setOnClickListener(v ->
                startActivity(new Intent(this, RequestsActivity.class)));
        findViewById(R.id.actionSync).setOnClickListener(v ->
                startActivity(new Intent(this, SyncActivity.class).putExtra(SyncActivity.EXTRA_ROLE, SyncActivity.ROLE_LECTURER)));
        findViewById(R.id.actionRoster).setOnClickListener(v ->
                startActivity(new Intent(this, LecturerRosterActivity.class)));
        findViewById(R.id.actionAddStudent).setOnClickListener(v ->
                EditStudentSheet.forAdd().show(getSupportFragmentManager(), this));

        findViewById(R.id.navHome).setOnClickListener(v -> { /* already here */ });
        findViewById(R.id.navRoster).setOnClickListener(v ->
                startActivity(new Intent(this, LecturerRosterActivity.class)));
        findViewById(R.id.navSync).setOnClickListener(v ->
                startActivity(new Intent(this, SyncActivity.class).putExtra(SyncActivity.EXTRA_ROLE, SyncActivity.ROLE_LECTURER)));

        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    public void onSaved(String toastMessage) {
        android.widget.Toast.makeText(this, toastMessage, android.widget.Toast.LENGTH_SHORT).show();
        render();
    }

    private void render() {
        ((TextView) findViewById(R.id.textWelcome)).setText(
                getString(R.string.welcome_back_lecturer, LECTURER_NAME.split(" ")[0]));

        StudentRepository repo = StudentRepository.getInstance();
        int activeCount = repo.getActive().size();
        int groupsFull = 0;
        for (String g : StudentRepository.GROUPS) if (repo.groupCount(g) >= StudentRepository.CAPACITY) groupsFull++;
        int requestCount = repo.pendingRequestCount();
        int conflictCount = 0; // stub — see class comment

        ((TextView) findViewById(R.id.tileStudentsNum)).setText(String.valueOf(activeCount));
        ((TextView) findViewById(R.id.tileGroupsFullNum)).setText(String.valueOf(groupsFull));
        ((TextView) findViewById(R.id.tileRequestsNum)).setText(String.valueOf(requestCount));
        ((TextView) findViewById(R.id.tileConflictsNum)).setText(String.valueOf(conflictCount));

        ((TextView) findViewById(R.id.actionRequestsSub)).setText(requestCount > 0
                ? getString(R.string.action_requests_sub_fmt, requestCount)
                : getString(R.string.action_requests_sub_none));
        ((TextView) findViewById(R.id.actionSyncSub)).setText(getString(R.string.action_conflicts_sub_fmt, conflictCount));

        LinearLayout occupancy = findViewById(R.id.occupancyList);
        occupancy.removeAllViews();
        for (String g : StudentRepository.GROUPS) {
            occupancy.addView(occupancyRow(g, repo.groupCount(g)));
        }
    }

    private View occupancyRow(String group, int count) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int vPad = dp(5);
        row.setPadding(0, vPad, 0, vPad);

        TextView label = new TextView(this);
        label.setText(group);
        label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
        label.setTextColor(getResources().getColor(R.color.navy_900));
        label.setTextSize(13);
        label.setLayoutParams(new LinearLayout.LayoutParams(dp(34), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(label);

        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackgroundColor(getResources().getColor(R.color.slate_100));
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(0, dp(6), 1f);
        trackLp.setMarginStart(dp(6));
        trackLp.setMarginEnd(dp(6));
        track.setLayoutParams(trackLp);

        boolean full = count >= StudentRepository.CAPACITY;
        View fill = new View(this);
        fill.setBackgroundColor(getResources().getColor(full ? R.color.red_400 : R.color.navy_600));
        float frac = Math.min(1f, count / (float) StudentRepository.CAPACITY);
        track.addView(fill, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, frac == 0 ? 0.0001f : frac));
        View empty = new View(this);
        track.addView(empty, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f - frac == 0 ? 0.0001f : 1f - frac));
        row.addView(track);

        TextView countView = new TextView(this);
        countView.setText(count + "/" + StudentRepository.CAPACITY);
        countView.setTextColor(getResources().getColor(R.color.slate_500));
        countView.setTextSize(11.5f);
        countView.setLayoutParams(new LinearLayout.LayoutParams(dp(42), LinearLayout.LayoutParams.WRAP_CONTENT));
        countView.setGravity(Gravity.END);
        row.addView(countView);

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
