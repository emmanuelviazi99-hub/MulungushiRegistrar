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

    private static final String LECTURER_NAME = "B. Nyirenda";

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lecturer_roster);
        ConnectivityBanner.attach(this);

        textRecordCount = findViewById(R.id.textRecordCount);
        textResultCount = findViewById(R.id.textResultCount);
        buttonFilterProgramme = findViewById(R.id.buttonFilterProgramme);
        buttonFilterGroup = findViewById(R.id.buttonFilterGroup);
        buttonClearFilters = findViewById(R.id.buttonClearFilters);
        emptyState = findViewById(R.id.emptyState);
        textEmptyTitle = findViewById(R.id.textEmptyTitle);

        RecyclerView recycler = findViewById(R.id.recyclerStudents);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new StudentAdapter(this::openEdit);
        recycler.setAdapter(adapter);

        android.widget.EditText search = findViewById(R.id.inputSearch);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString();
                refresh();
            }
        });

        buttonFilterProgramme.setOnClickListener(v -> showProgrammeFilter());
        buttonFilterGroup.setOnClickListener(v -> showGroupFilter());
        buttonClearFilters.setOnClickListener(v -> {
            programmeFilter = "all";
            groupFilter = "all";
            refresh();
        });

        findViewById(R.id.fabAdd).setOnClickListener(v -> openAdd());

        String reviewId = getIntent().getStringExtra(EXTRA_REVIEW_ID);
        if (reviewId != null && savedInstanceState == null) {
            reviewing = true;
            String reviewNumber = getIntent().getStringExtra(EXTRA_REVIEW_NUMBER);
            getWindow().getDecorView().post(() ->
                    EditStudentSheet.forEdit(reviewId, reviewNumber).show(getSupportFragmentManager(), this));
        }
        RosterFormat.bindHomeAvatar(this, LECTURER_NAME, SignOutSheet.lecturerDetail(this));
        BottomNav.bindLecturer(this, BottomNav.MIDDLE);

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh(); // picks up anything changed by the sheet
    }

    private void refresh() {
        StudentRepository repo = StudentRepository.getInstance();
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
        adapter.submit(filtered);

        textResultCount.setText(filtered.size() == 1
                ? getString(R.string.roster_count_single, filtered.size())
                : getString(R.string.roster_count_plural, filtered.size()));

        boolean filtersActive = !programmeFilter.equals("all") || !groupFilter.equals("all");
        buttonClearFilters.setVisibility(filtersActive ? View.VISIBLE : View.GONE);
        buttonFilterProgramme.setBackgroundResource(programmeFilter.equals("all") ? R.drawable.bg_filter : R.drawable.bg_filter_active);
        buttonFilterGroup.setBackgroundResource(groupFilter.equals("all") ? R.drawable.bg_filter : R.drawable.bg_filter_active);
        buttonFilterProgramme.setText(getString(R.string.filter_programme) + ": " + (programmeFilter.equals("all") ? getString(R.string.filter_all) : programmeFilter));
        buttonFilterGroup.setText(getString(R.string.filter_group) + ": " + (groupFilter.equals("all") ? getString(R.string.filter_all) : groupFilter));

        if (filtered.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            textEmptyTitle.setText(active.isEmpty() ? R.string.roster_empty_title : R.string.roster_empty_filtered);
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
        refresh();
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
