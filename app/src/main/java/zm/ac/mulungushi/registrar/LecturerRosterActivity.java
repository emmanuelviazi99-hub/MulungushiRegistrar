package zm.ac.mulungushi.registrar;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LecturerRosterActivity extends AppCompatActivity implements EditStudentSheet.Listener {

    private boolean warned;
    private final Runnable redraw = this::refresh;

    private StudentAdapter adapter;
    private TextView textRecordCount, textResultCount, buttonFilterProgramme, buttonFilterGroup, buttonClearFilters;
    private View emptyState;
    private TextView textEmptyTitle;

    static final String EXTRA_REVIEW_ID = "review_id";
    static final String EXTRA_REVIEW_NUMBER = "review_number";
    private boolean reviewing;

    private String query = "";
    private String programmeFilter = "all";
    private String groupFilter = "all";

    // Pagination (UI/UX team): how many students per page, and how many pages are open now.
    private static final int PAGE_SIZE = 10;
    private int pagesShown = 1;

    // Loading: true until the first answer (server, or the copy saved on the phone) arrives.
    private boolean loading;

    private static final int MODE_LIST = 0, MODE_LOADING = 1, MODE_EXPIRED = 2;
    private RecyclerView recycler;
    private android.widget.EditText search;
    private View loadingState, sessionExpiredState, fabAdd;
    private TextView textCachedNotice;
    private boolean networkOffline;

    // Keys for what survives a rotation
    private static final String STATE_QUERY = "roster_query";
    private static final String STATE_PROGRAMME = "roster_programme";
    private static final String STATE_GROUP = "roster_group";
    private static final String STATE_PAGES = "roster_pages";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lecturer_roster);
        ConnectivityBanner.attach(this);
        LiveRefresh.lecturer(this);
        PullToRefresh.lecturer(this);

        // Get the search text, filters and open pages back after a rotation
        if (savedInstanceState != null) {
            query = savedInstanceState.getString(STATE_QUERY, "");
            programmeFilter = savedInstanceState.getString(STATE_PROGRAMME, "all");
            groupFilter = savedInstanceState.getString(STATE_GROUP, "all");
            pagesShown = Math.max(1, savedInstanceState.getInt(STATE_PAGES, 1));
        }

        textRecordCount = findViewById(R.id.textRecordCount);
        textResultCount = findViewById(R.id.textResultCount);
        buttonFilterProgramme = findViewById(R.id.buttonFilterProgramme);
        buttonFilterGroup = findViewById(R.id.buttonFilterGroup);
        buttonClearFilters = findViewById(R.id.buttonClearFilters);
        emptyState = findViewById(R.id.emptyState);
        textEmptyTitle = findViewById(R.id.textEmptyTitle);
        loadingState = findViewById(R.id.loadingState);
        sessionExpiredState = findViewById(R.id.sessionExpiredState);
        fabAdd = findViewById(R.id.fabAdd);
        textCachedNotice = findViewById(R.id.textCachedNotice);

        recycler = findViewById(R.id.recyclerStudents);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new StudentAdapter(this::openEdit);
        adapter.setOnLoadMore(() -> {
            pagesShown++;
            refresh();
        });
        recycler.setAdapter(adapter);

        search = findViewById(R.id.inputSearch);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                String now = s.toString();
                if (now.equals(query)) return; // also fires when Android restores the text after a rotation
                query = now;
                pagesShown = 1;
                refresh();
            }
        });

        Accessibility.asButton(buttonFilterProgramme);
        Accessibility.asButton(buttonFilterGroup);
        Accessibility.asButton(buttonClearFilters);

        buttonFilterProgramme.setOnClickListener(v -> showProgrammeFilter());
        buttonFilterGroup.setOnClickListener(v -> showGroupFilter());
        buttonClearFilters.setOnClickListener(v -> {
            programmeFilter = "all";
            groupFilter = "all";
            pagesShown = 1;
            refresh();
        });

        fabAdd.setOnClickListener(v -> openAdd());

        // Session expired: "Sign in again" goes to the sign-in screen. Waiting changes stay saved
        // on the phone and upload after the lecturer signs in again.
        findViewById(R.id.buttonSessionSignIn).setOnClickListener(v -> {
            SessionState.reset();
            RosterUi.signedOut(this, new RosterApi.Outcome(false, RosterApi.Outcome.UNAUTHORIZED, null));
        });

        // Offline: show the "limited cached results" label
        NetworkWatcher.watch(this, online -> {
            networkOffline = !online;
            refresh();
        });
        loading = !StudentRepository.getInstance().isRosterLoaded();

        String reviewId = getIntent().getStringExtra(EXTRA_REVIEW_ID);
        if (reviewId != null && savedInstanceState == null) {
            reviewing = true;
            String reviewNumber = getIntent().getStringExtra(EXTRA_REVIEW_NUMBER);
            getWindow().getDecorView().post(() ->
                    EditStudentSheet.forEdit(reviewId, reviewNumber).show(getSupportFragmentManager(), this));
        }
        RosterFormat.bindHomeAvatar(this, RosterUi.lecturerName(), SignOutSheet.lecturerDetail(this));
        BottomNav.bindLecturer(this, BottomNav.MIDDLE);

        refresh();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_QUERY, query);
        outState.putString(STATE_PROGRAMME, programmeFilter);
        outState.putString(STATE_GROUP, groupFilter);
        outState.putInt(STATE_PAGES, pagesShown);
    }

    @Override
    protected void onResume() {
        super.onResume();
        StudentRepository.getInstance().addListener(redraw);
        refresh();
        reload();
    }

    @Override
    protected void onPause() {
        super.onPause();
        StudentRepository.getInstance().removeListener(redraw);
    }


    /** Fetches the roster from the server, then shows it. */
    private void reload() {
        RosterApi.get().loadRoster(outcome -> {
            if (isFinishing() || isDestroyed()) return;
            loading = false;
            if (outcome.is(RosterApi.Outcome.UNAUTHORIZED)) {
                SessionState.expire(); // shown in place of the list, with "Sign in again"
            } else if (!outcome.ok) {
                RosterUi.refreshFailed(this, outcome, warned);
                warned = true;
            }
            refresh();
        });
    }

    /** Shows the list, the loading spinner or the expired-session message. */
    private void applyMode(int mode) {
        boolean list = mode == MODE_LIST;
        recycler.setVisibility(list ? View.VISIBLE : View.GONE);
        fabAdd.setVisibility(list ? View.VISIBLE : View.GONE);
        loadingState.setVisibility(mode == MODE_LOADING ? View.VISIBLE : View.GONE);
        sessionExpiredState.setVisibility(mode == MODE_EXPIRED ? View.VISIBLE : View.GONE);
        if (!list) {
            emptyState.setVisibility(View.GONE);
            textRecordCount.setText("");
            textResultCount.setText("");
            textCachedNotice.setVisibility(View.GONE);
        }
        // Search and filters are switched off when the session has expired
        boolean usable = mode != MODE_EXPIRED;
        float alpha = usable ? 1f : 0.5f;
        search.setEnabled(usable);
        search.setAlpha(alpha);
        buttonFilterProgramme.setClickable(usable);
        buttonFilterProgramme.setAlpha(alpha);
        buttonFilterGroup.setClickable(usable);
        buttonFilterGroup.setAlpha(alpha);
    }

    private void refresh() {
        StudentRepository repo = StudentRepository.getInstance();
        if (SessionState.isExpired()) {
            applyMode(MODE_EXPIRED);
            buttonClearFilters.setVisibility(View.GONE);
            return;
        }
        if (loading && !repo.isRosterLoaded()) {
            applyMode(MODE_LOADING);
            buttonClearFilters.setVisibility(View.GONE);
            return;
        }
        applyMode(MODE_LIST);
        // Only what is saved on this phone: no network, or the server can't be reached
        textCachedNotice.setVisibility(networkOffline || repo.isOffline() ? View.VISIBLE : View.GONE);

        List<Student> active = repo.getActive();
        textRecordCount.setText(getString(R.string.roster_records, active.size()));

        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Student> filtered = new ArrayList<>();
        for (Student s : active) {
            boolean matchesQ = q.isEmpty() || s.name.toLowerCase(Locale.ROOT).contains(q) || s.number.contains(q);
            boolean matchesP = programmeFilter.equals("all") || s.programme.equals(programmeFilter);
            boolean matchesG = groupFilter.equals("all") || s.group.equals(groupFilter);
            if (matchesQ && matchesP && matchesG) filtered.add(s);
        }
        int limit = Math.min(filtered.size(), pagesShown * PAGE_SIZE);
        adapter.submit(new ArrayList<>(filtered.subList(0, limit)), filtered.size());

        textResultCount.setText(filtered.size() == 1
                ? getString(R.string.roster_count_single, filtered.size())
                : getString(R.string.roster_count_plural, filtered.size()));

        boolean filtersActive = !programmeFilter.equals("all") || !groupFilter.equals("all");
        buttonClearFilters.setVisibility(filtersActive ? View.VISIBLE : View.GONE);
        buttonFilterProgramme.setBackgroundResource(programmeFilter.equals("all") ? R.drawable.bg_filter : R.drawable.bg_filter_active);
        buttonFilterGroup.setBackgroundResource(groupFilter.equals("all") ? R.drawable.bg_filter : R.drawable.bg_filter_active);
        buttonFilterProgramme.setText(getString(R.string.filter_programme) + ": " + (programmeFilter.equals("all") ? getString(R.string.filter_all) : programmeFilter));
        buttonFilterGroup.setText(getString(R.string.filter_group) + ": " + (groupFilter.equals("all") ? getString(R.string.filter_all) : groupFilter));
        buttonFilterProgramme.setContentDescription(getString(R.string.a11y_programme_filter,
                programmeFilter.equals("all") ? getString(R.string.filter_all) : programmeFilter));
        buttonFilterGroup.setContentDescription(getString(R.string.a11y_group_filter,
                groupFilter.equals("all") ? getString(R.string.filter_all) : groupFilter));

        if (filtered.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            textEmptyTitle.setText(!repo.isRosterLoaded() ? R.string.roster_loading
                    : active.isEmpty() ? R.string.roster_empty_title : R.string.roster_empty_filtered);
        } else {
            emptyState.setVisibility(View.GONE);
        }
    }

    private void showProgrammeFilter() {
        StudentRepository repo = StudentRepository.getInstance();
        List<String> labels = new ArrayList<>();
        List<String> counts = new ArrayList<>();
        List<String> values = new ArrayList<>();
        labels.add("All programmes");
        counts.add(String.valueOf(repo.getActive().size()));
        values.add("all");
        for (String p : StudentRepository.PROGRAMMES) {
            int n = 0;
            for (Student s : repo.getActive()) if (s.programme.equals(p)) n++;
            labels.add(p);
            counts.add(String.valueOf(n));
            values.add(p);
        }
        showChoiceSheet(getString(R.string.filter_programme), labels, counts, values, programmeFilter, v -> {
            programmeFilter = v;
            pagesShown = 1;
            refresh();
        });
    }

    private void showGroupFilter() {
        StudentRepository repo = StudentRepository.getInstance();
        List<String> labels = new ArrayList<>();
        List<String> counts = new ArrayList<>();
        List<String> values = new ArrayList<>();
        labels.add("All groups");
        counts.add("");
        values.add("all");
        for (String g : StudentRepository.GROUPS) {
            int c = repo.groupCount(g);
            labels.add(g);
            counts.add(c + " of " + StudentRepository.CAPACITY);
            values.add(g);
        }
        int unassigned = 0;
        for (Student s : repo.getActive()) if (StudentRepository.UNASSIGNED.equals(s.group)) unassigned++;
        labels.add(getString(R.string.unassigned));
        counts.add(String.valueOf(unassigned));
        values.add(StudentRepository.UNASSIGNED);
        showChoiceSheet(getString(R.string.filter_group), labels, counts, values, groupFilter, v -> {
            groupFilter = v;
            pagesShown = 1;
            refresh();
        });
    }

    private interface OnPick { void pick(String value); }

    /** Bottom sheet picker (drag handle, tick on the current choice, counts on the right). */
    private void showChoiceSheet(String title, List<String> labels, List<String> counts,
                                 List<String> values, String current, OnPick onPick) {
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_sheet_top);
        root.setPadding(0, 0, 0, RosterFormat.dp(this, 12));

        View handle = new View(this);
        android.graphics.drawable.GradientDrawable hb = new android.graphics.drawable.GradientDrawable();
        hb.setColor(androidx.core.content.ContextCompat.getColor(this, R.color.slate_200));
        hb.setCornerRadius(RosterFormat.dp(this, 3));
        handle.setBackground(hb);
        android.widget.LinearLayout.LayoutParams hlp = new android.widget.LinearLayout.LayoutParams(
                RosterFormat.dp(this, 36), RosterFormat.dp(this, 5));
        hlp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = RosterFormat.dp(this, 10);
        hlp.bottomMargin = RosterFormat.dp(this, 14);
        root.addView(handle, hlp);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.navy_900));
        titleView.setTextSize(20f);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setPadding(RosterFormat.dp(this, 20), 0, RosterFormat.dp(this, 20), RosterFormat.dp(this, 10));
        root.addView(titleView);

        for (int i = 0; i < labels.size(); i++) {
            final String value = values.get(i);
            boolean on = value.equals(current);

            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(RosterFormat.dp(this, 20), 0, RosterFormat.dp(this, 20), 0);
            row.setMinimumHeight(RosterFormat.dp(this, 56));
            if (on) row.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, R.color.navy_50));
            row.setClickable(true);
            row.setOnClickListener(v -> {
                onPick.pick(value);
                dialog.dismiss();
            });

            TextView label = new TextView(this);
            label.setText(labels.get(i));
            label.setTextSize(17f);
            label.setTextColor(androidx.core.content.ContextCompat.getColor(this, on ? R.color.navy_700 : R.color.navy_900));
            if (on) label.setTypeface(null, android.graphics.Typeface.BOLD);
            row.addView(label, new android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView count = new TextView(this);
            count.setText(counts.get(i));
            count.setTextSize(15f);
            count.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.slate_400));
            row.addView(count);

            TextView tick = new TextView(this);
            tick.setText(on ? "\u2713" : "");
            tick.setTextSize(20f);
            tick.setTypeface(null, android.graphics.Typeface.BOLD);
            tick.setGravity(android.view.Gravity.CENTER);
            tick.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.navy_600));
            row.addView(tick, new android.widget.LinearLayout.LayoutParams(RosterFormat.dp(this, 32),
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

            root.addView(row);
        }

        dialog.setContentView(root);
        View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) sheet.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        dialog.show();
    }

    private void openAdd() {
        EditStudentSheet.forAdd().show(getSupportFragmentManager(), this);
    }

    private void openEdit(Student s) {
        EditStudentSheet.forEdit(s.id).show(getSupportFragmentManager(), this);
    }

    @Override
    public void onSaved(String toastMessage) {
        if (reviewing) {
            // came from Requests: hand the confirmation to that screen and go back to it
            Feedback.postForNext(toastMessage);
            finish();
            return;
        }
        Feedback.show(this, toastMessage);
        refresh(); // the sheet saved on the server and reloaded the roster already
    }

    private void notBuilt() {
        Toast.makeText(this, R.string.toast_not_built, Toast.LENGTH_SHORT).show();
    }

    private static String initialsOf(String name) {
        String t = name == null ? "" : name.trim();
        if (t.isEmpty()) return "?";
        String[] parts = t.split("\\s+");
        String first = String.valueOf(parts[0].charAt(0));
        if (parts.length > 1) return (first + parts[parts.length - 1].charAt(0)).toUpperCase();
        return first.toUpperCase();
    }

    private void signOut() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
