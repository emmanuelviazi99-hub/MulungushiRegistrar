package zm.ac.mulungushi.registrar;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Groups (lecturer): each group and its members, without searching. Opened from a
 * row in the dashboard's Group occupancy card (that group first) or its "View groups"
 * button. A tap on a member opens the usual edit sheet, to move or remove them.
 *
 * View layer only: GroupsViewModel says which group is chosen and who is in it.
 */
public class LecturerGroupsActivity extends AppCompatActivity implements EditStudentSheet.Listener {

    static final String EXTRA_GROUP = "group";
    private static final String STATE_GROUP = "group";

    /** Opens the Groups screen on one group (or the first, when group is null). */
    static Intent open(Context c, String group) {
        return new Intent(c, LecturerGroupsActivity.class).putExtra(EXTRA_GROUP, group);
    }

    private GroupsViewModel vm;
    private StudentAdapter adapter;
    private String lastScrolledTo;
    private boolean warned;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!RosterUi.lecturerOrLeave(this)) return; // lecturers only
        setContentView(R.layout.activity_lecturer_groups);
        ConnectivityBanner.attach(this);
        LiveRefresh.lecturer(this);
        PullToRefresh.lecturer(this);

        ((TextView) findViewById(R.id.topBarTitle)).setText(R.string.groups_title);
        setTitle(R.string.groups_title);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        Accessibility.explain(findViewById(R.id.btnBack), R.string.why_back);
        RosterFormat.bindAvatar(this, RosterUi.lecturerName(), SignOutSheet.lecturerDetail(this));
        BottomNav.bindLecturer(this, BottomNav.HOME);

        RecyclerView list = findViewById(R.id.recyclerMembers);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new StudentAdapter(s -> EditStudentSheet.forEdit(s.id).show(getSupportFragmentManager(), this));
        list.setAdapter(adapter);
        // "G05, 15 of 15 places filled, full" is read out when the group changes
        ViewCompat.setAccessibilityLiveRegion(findViewById(R.id.textGroupCount), ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE);

        vm = new ViewModelProvider(this).get(GroupsViewModel.class);
        vm.getScreen().observe(this, this::render);
        String first = savedInstanceState != null ? savedInstanceState.getString(STATE_GROUP)
                : getIntent().getStringExtra(EXTRA_GROUP);
        vm.start(first);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // the latest roster from the server; the screen redraws by itself when it arrives
        RosterApi.get().loadRoster(outcome -> {
            if (isFinishing() || isDestroyed() || outcome.ok) return;
            RosterUi.refreshFailed(this, outcome, warned); // once per visit; the saved copy stays on screen
            warned = true;
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (vm != null) out.putString(STATE_GROUP, vm.chosen());
    }

    @Override
    public void onSaved(String toastMessage) {
        Feedback.show(this, toastMessage); // the roster was reloaded: the list updates by itself
    }

    // ---------------------------------------------------------------- drawing

    private void render(GroupsViewModel.Screen s) {
        if (s == null) return;
        buildTabs(s);

        boolean unassigned = StudentRepository.UNASSIGNED.equals(s.group);
        ((TextView) findViewById(R.id.textGroupName)).setText(unassigned ? getString(R.string.groups_unassigned) : s.group);
        String countText = unassigned
                ? getResources().getQuantityString(R.plurals.groups_unassigned_count, s.members.size(), s.members.size())
                : getString(R.string.group_places_filled, s.members.size(), s.capacity);
        TextView count = findViewById(R.id.textGroupCount);
        count.setText(countText);

        TextView pill = findViewById(R.id.pillGroupState);
        View bar = findViewById(R.id.groupBar);
        if (unassigned) {
            pill.setVisibility(View.GONE);
            bar.setVisibility(View.GONE);
        } else {
            pill.setVisibility(View.VISIBLE);
            pill.setText(s.full ? R.string.groups_full : R.string.groups_open);
            pill.setBackgroundResource(s.full ? R.drawable.bg_pill_red : R.drawable.bg_pill_green);
            pill.setTextColor(ContextCompat.getColor(this, s.full ? R.color.red_600 : R.color.green_700));
            bar.setVisibility(View.VISIBLE);
            drawBar((LinearLayout) bar, s.members.size(), s.capacity, s.full);
        }
        findViewById(R.id.groupHeader).setContentDescription((unassigned ? getString(R.string.groups_unassigned) : s.group)
                + ", " + countText + (unassigned ? "" : ", " + getString(s.full ? R.string.groups_full : R.string.groups_open)));

        adapter.submit(s.members);
        findViewById(R.id.textNoMembers).setVisibility(s.members.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void buildTabs(GroupsViewModel.Screen s) {
        LinearLayout tabs = findViewById(R.id.groupTabs);
        tabs.removeAllViews();
        View selectedTab = null;
        for (GroupsViewModel.Tab t : s.tabs) {
            boolean selected = t.group.equals(s.group);
            boolean unassigned = StudentRepository.UNASSIGNED.equals(t.group);
            TextView tab = new TextView(this);
            String label = unassigned ? getString(R.string.groups_unassigned_tab, t.count)
                    : getString(R.string.groups_tab_fmt, t.group, t.count, t.capacity);
            tab.setText(label);
            tab.setTextSize(13.5f);
            tab.setTypeface(null, android.graphics.Typeface.BOLD);
            tab.setGravity(Gravity.CENTER);
            tab.setMinHeight(dp(44));
            tab.setPadding(dp(14), dp(8), dp(14), dp(8));
            tab.setBackgroundResource(selected ? R.drawable.bg_filter_active : R.drawable.bg_filter);
            tab.setTextColor(ContextCompat.getColor(this,
                    selected ? R.color.navy_900 : (t.full ? R.color.red_600 : R.color.slate_600)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            tab.setLayoutParams(lp);
            tab.setClickable(true);
            tab.setFocusable(true);
            tab.setSelected(selected);
            String spoken = unassigned ? label
                    : getString(R.string.group_summary_line, t.group, t.count, t.capacity)
                      + (t.full ? ", " + getString(R.string.groups_full) : "");
            tab.setContentDescription(spoken + ", " + getString(selected ? R.string.a11y_selected : R.string.a11y_not_selected));
            tab.setOnClickListener(v -> vm.choose(t.group));
            tabs.addView(tab);
            if (selected) selectedTab = tab;
        }
        // bring the chosen tab into view (G12 is off-screen on a small phone), once per choice
        if (selectedTab != null && !s.group.equals(lastScrolledTo)) {
            lastScrolledTo = s.group;
            final View target = selectedTab;
            HorizontalScrollView scroll = findViewById(R.id.groupTabsScroll);
            scroll.post(() -> scroll.smoothScrollTo(Math.max(0, target.getLeft() - dp(16)), 0));
        }
    }

    private void drawBar(LinearLayout bar, int count, int capacity, boolean full) {
        bar.removeAllViews();
        float frac = capacity <= 0 ? 0f : Math.min(1f, count / (float) capacity);
        View fill = new View(this);
        fill.setBackgroundResource(full ? R.drawable.bg_fill_red : R.drawable.bg_fill_navy);
        bar.addView(fill, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, frac == 0 ? 0.0001f : frac));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT,
                1f - frac == 0 ? 0.0001f : 1f - frac));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
