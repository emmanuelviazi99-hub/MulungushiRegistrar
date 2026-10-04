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

    private static final String LECTURER_NAME = "Bella Nyirenda";

    private StudentAdapter adapter;
    private TextView textRecordCount, textResultCount, buttonFilterProgramme, buttonFilterGroup, buttonClearFilters;
    private View emptyState;
    private TextView textEmptyTitle;

    private String query = "";
    private String programmeFilter = "all";
    private String groupFilter = "all";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lecturer_roster);

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
        findViewById(R.id.buttonSignOut).setOnClickListener(v -> signOut());
        ((TextView) findViewById(R.id.buttonSignOut)).setText(initialsOf(LECTURER_NAME));
        findViewById(R.id.navRoster).setOnClickListener(v -> { /* already here */ });
        findViewById(R.id.navHome).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, LecturerDashboardActivity.class)));
        findViewById(R.id.navSync).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, SyncActivity.class)
                        .putExtra(SyncActivity.EXTRA_ROLE, SyncActivity.ROLE_LECTURER)));

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
        List<String> values = new ArrayList<>();
        labels.add(getString(R.string.filter_all) + " (" + repo.getActive().size() + ")");
        values.add("all");
        for (String p : StudentRepository.PROGRAMMES) {
            int n = 0;
            for (Student s : repo.getActive()) if (s.programme.equals(p)) n++;
            labels.add(p + " (" + n + ")");
            values.add(p);
        }
        showChoiceDialog(getString(R.string.filter_programme), labels, values, programmeFilter, v -> {
            programmeFilter = v;
            refresh();
        });
    }

    private void showGroupFilter() {
        StudentRepository repo = StudentRepository.getInstance();
        List<String> labels = new ArrayList<>();
        List<String> values = new ArrayList<>();
        labels.add(getString(R.string.filter_all));
        values.add("all");
        for (String g : StudentRepository.GROUPS) {
            int c = repo.groupCount(g);
            labels.add(g + " — " + c + "/" + StudentRepository.CAPACITY + (c >= StudentRepository.CAPACITY ? " (Full)" : ""));
            values.add(g);
        }
        int unassigned = 0;
        for (Student s : repo.getActive()) if (StudentRepository.UNASSIGNED.equals(s.group)) unassigned++;
        labels.add(getString(R.string.unassigned) + " (" + unassigned + ")");
        values.add(StudentRepository.UNASSIGNED);
        showChoiceDialog(getString(R.string.filter_group), labels, values, groupFilter, v -> {
            groupFilter = v;
            refresh();
        });
    }

    private interface OnPick { void pick(String value); }

    private void showChoiceDialog(String title, List<String> labels, List<String> values, String current, OnPick onPick) {
        int checked = values.indexOf(current);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (dialog, which) -> {
                    onPick.pick(values.get(which));
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void openAdd() {
        EditStudentSheet.forAdd().show(getSupportFragmentManager(), this);
    }

    private void openEdit(Student s) {
        EditStudentSheet.forEdit(s.id).show(getSupportFragmentManager(), this);
    }

    @Override
    public void onSaved(String toastMessage) {
        Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show();
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
