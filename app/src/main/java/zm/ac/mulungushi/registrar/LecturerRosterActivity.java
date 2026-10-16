package zm.ac.mulungushi.registrar;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * The lecturer's roster (View layer). It shows LecturerViewModel's Screen and
 * hands every action to it: typing, the Filters button and chips (RosterFilterSheet),
 * Clear, Show more. The search and filter rules are in RosterQuery and
 * StudentRepository.search(), not here. Lecturers only: anyone else is sent to
 * their own home (and the server refuses them anyway).
 */
public class LecturerRosterActivity extends AppCompatActivity implements EditStudentSheet.Listener {

    static final String EXTRA_REVIEW_ID = "review_id";
    static final String EXTRA_REVIEW_NUMBER = "review_number";

    // What survives Android closing the app in the background (the ViewModel covers rotation)
    private static final String STATE_QUERY = "roster_query";
    private static final String STATE_PROGRAMME = "roster_programme";
    private static final String STATE_GROUP = "roster_group";
    private static final String STATE_PAGES = "roster_pages";

    private LecturerViewModel vm;
    private boolean reviewing;
    private boolean warned;

    private StudentAdapter adapter;
    private RecyclerView recycler;
    private EditText search;
    private ImageButton buttonClearSearch, buttonFilters;
    private TextView textRecordCount, textResultCount, buttonFilterProgramme, buttonFilterGroup, buttonClearFilters;
    private TextView textEmptyTitle, textCachedNotice, buttonClearAll;
    private View emptyState, loadingState, sessionExpiredState, fabAdd;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!RosterUi.lecturerOrLeave(this)) return; // lecturers only
        setContentView(R.layout.activity_lecturer_roster);
        ConnectivityBanner.attach(this);
        LiveRefresh.lecturer(this);
        PullToRefresh.lecturer(this);

        vm = new ViewModelProvider(this).get(LecturerViewModel.class);
        if (savedInstanceState != null) {
            vm.restore(new RosterQuery(savedInstanceState.getString(STATE_QUERY, ""),
                            savedInstanceState.getString(STATE_PROGRAMME, RosterQuery.ALL),
                            savedInstanceState.getString(STATE_GROUP, RosterQuery.ALL)),
                    savedInstanceState.getInt(STATE_PAGES, 1));
        }

        textRecordCount = findViewById(R.id.textRecordCount);
        textResultCount = findViewById(R.id.textResultCount);
        buttonFilterProgramme = findViewById(R.id.buttonFilterProgramme);
        buttonFilterGroup = findViewById(R.id.buttonFilterGroup);
        buttonClearFilters = findViewById(R.id.buttonClearFilters);
        buttonClearSearch = findViewById(R.id.buttonClearSearch);
        buttonFilters = findViewById(R.id.buttonFilters);
        buttonClearAll = findViewById(R.id.buttonClearAll);
        emptyState = findViewById(R.id.emptyState);
        textEmptyTitle = findViewById(R.id.textEmptyTitle);
        loadingState = findViewById(R.id.loadingState);
        sessionExpiredState = findViewById(R.id.sessionExpiredState);
        fabAdd = findViewById(R.id.fabAdd);
        textCachedNotice = findViewById(R.id.textCachedNotice);

        recycler = findViewById(R.id.recyclerStudents);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new StudentAdapter(this::openEdit);
        adapter.setOnLoadMore(vm::showMore);
        recycler.setAdapter(adapter);

        search = findViewById(R.id.inputSearch);
        search.setText(vm.query().text); // after Android closed the app: the ViewModel was restored above
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                vm.setSearchText(s.toString());
            }
        });

        Accessibility.explain(buttonFilters, R.string.why_filters);
        Accessibility.explain(buttonClearSearch, R.string.why_clear_search);
        Accessibility.explain(buttonFilterProgramme, R.string.why_filter_programme);
        Accessibility.explain(buttonFilterGroup, R.string.why_filter_group);
        Accessibility.explain(buttonClearFilters, R.string.why_clear_filters);

        buttonFilters.setOnClickListener(v -> RosterFilterSheet.show(this, vm, RosterFilterSheet.BOTH));
        buttonFilterProgramme.setOnClickListener(v -> RosterFilterSheet.show(this, vm, RosterFilterSheet.PROGRAMME));
        buttonFilterGroup.setOnClickListener(v -> RosterFilterSheet.show(this, vm, RosterFilterSheet.GROUP));
        buttonClearFilters.setOnClickListener(v -> vm.clearFilters());
        buttonClearSearch.setOnClickListener(v -> {
            search.setText(""); // the watcher tells the ViewModel
            search.requestFocus();
        });
        buttonClearAll.setOnClickListener(v -> {
            search.setText("");
            vm.clearAll();
        });

        fabAdd.setOnClickListener(v -> openAdd());
        Accessibility.explain(fabAdd, R.string.why_add_student);

        // Session expired: "Sign in again" goes to the sign-in screen. Waiting changes stay saved
        // on the phone and upload after the lecturer signs in again.
        findViewById(R.id.buttonSessionSignIn).setOnClickListener(v -> {
            SessionState.reset();
            RosterUi.signedOut(this, new RosterApi.Outcome(false, RosterApi.Outcome.UNAUTHORIZED, null));
        });

        // Offline: show the "limited cached results" label
        NetworkWatcher.watch(this, online -> vm.setNetworkOffline(!online));

        vm.getScreen().observe(this, this::render);
        vm.getLoadFailed().observe(this, event -> {
            RosterApi.Outcome failed = event.take();
            if (failed == null) return;
            RosterUi.refreshFailed(this, failed, warned);
            warned = true;
        });

        String reviewId = getIntent().getStringExtra(EXTRA_REVIEW_ID);
        if (reviewId != null && savedInstanceState == null) {
            reviewing = true;
            String reviewNumber = getIntent().getStringExtra(EXTRA_REVIEW_NUMBER);
            getWindow().getDecorView().post(() ->
                    EditStudentSheet.forEdit(reviewId, reviewNumber).show(getSupportFragmentManager(), this));
        }
        RosterFormat.bindHomeAvatar(this, RosterUi.lecturerName(), SignOutSheet.lecturerDetail(this));
        BottomNav.bindLecturer(this, BottomNav.MIDDLE);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (vm == null) return;
        RosterQuery q = vm.query();
        outState.putString(STATE_QUERY, q.text);
        outState.putString(STATE_PROGRAMME, q.programme);
        outState.putString(STATE_GROUP, q.group);
        outState.putInt(STATE_PAGES, vm.pages());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (vm != null) vm.refresh(false); // skipped if a load is running or just finished (rotation)
    }

    // ---------------------------------------------------------------- drawing the ViewModel's state

    private void render(LecturerViewModel.Screen s) {
        if (s == null) return;
        boolean list = s.mode == LecturerViewModel.Mode.LIST;
        recycler.setVisibility(list ? View.VISIBLE : View.GONE);
        fabAdd.setVisibility(list ? View.VISIBLE : View.GONE);
        loadingState.setVisibility(s.mode == LecturerViewModel.Mode.LOADING ? View.VISIBLE : View.GONE);
        sessionExpiredState.setVisibility(s.mode == LecturerViewModel.Mode.EXPIRED ? View.VISIBLE : View.GONE);

        // Search and filters are switched off when the session has expired
        boolean usable = s.mode != LecturerViewModel.Mode.EXPIRED;
        float alpha = usable ? 1f : 0.5f;
        search.setEnabled(usable);
        search.setAlpha(alpha);
        for (View v : new View[]{buttonFilterProgramme, buttonFilterGroup, buttonFilters}) {
            v.setEnabled(usable);
            v.setAlpha(alpha);
        }
        buttonClearSearch.setVisibility(s.query.text.isEmpty() || !usable ? View.GONE : View.VISIBLE);

        RosterQuery q = s.query;
        String all = getString(R.string.filter_all);
        String programme = RosterQuery.ALL.equals(q.programme) ? all : q.programme;
        String group = RosterQuery.ALL.equals(q.group) ? all : q.group;
        buttonFilterProgramme.setText(getString(R.string.filter_programme) + ": " + programme);
        buttonFilterGroup.setText(getString(R.string.filter_group) + ": " + group);
        buttonFilterProgramme.setBackgroundResource(RosterQuery.ALL.equals(q.programme) ? R.drawable.bg_filter : R.drawable.bg_filter_active);
        buttonFilterGroup.setBackgroundResource(RosterQuery.ALL.equals(q.group) ? R.drawable.bg_filter : R.drawable.bg_filter_active);
        buttonFilterProgramme.setContentDescription(getString(R.string.a11y_programme_filter, programme));
        buttonFilterGroup.setContentDescription(getString(R.string.a11y_group_filter, group));
        buttonFilters.setBackgroundResource(q.hasFilters() ? R.drawable.bg_filter_active : R.drawable.bg_filter);
        buttonFilters.setContentDescription(q.hasFilters()
                ? getResources().getQuantityString(R.plurals.a11y_filters_on, q.filterCount(), q.filterCount())
                : getString(R.string.a11y_filters_none));

        if (!list) {
            emptyState.setVisibility(View.GONE);
            setIfChanged(textRecordCount, "");
            setIfChanged(textResultCount, "");
            textCachedNotice.setVisibility(View.GONE);
            buttonClearFilters.setVisibility(View.GONE);
            return;
        }

        // Only what is saved on this phone: no network, or the server can't be reached
        textCachedNotice.setVisibility(s.savedCopyOnly ? View.VISIBLE : View.GONE);
        setIfChanged(textRecordCount, getString(R.string.roster_records, s.total));
        // a live region: TalkBack reads the new count, once per change
        setIfChanged(textResultCount, s.matching == 1
                ? getString(R.string.roster_count_single, s.matching)
                : getString(R.string.roster_count_plural, s.matching));
        buttonClearFilters.setVisibility(q.hasFilters() ? View.VISIBLE : View.GONE);

        adapter.submit(s.shown, s.matching);

        if (s.empty == LecturerViewModel.Empty.NONE) {
            emptyState.setVisibility(View.GONE);
            return;
        }
        emptyState.setVisibility(View.VISIBLE);
        String title;
        switch (s.empty) {
            case NOT_LOADED:  title = getString(R.string.roster_loading); break;
            case NO_STUDENTS: title = getString(R.string.roster_empty_title); break;
            default:
                title = q.hasText() && !q.hasFilters()
                        ? getString(R.string.roster_no_match_text, q.text.trim())
                        : getString(R.string.roster_empty_filtered);
        }
        setIfChanged(textEmptyTitle, title);
        buttonClearAll.setVisibility(s.empty == LecturerViewModel.Empty.NO_MATCH ? View.VISIBLE : View.GONE);
    }

    /** Setting the same text again would make TalkBack read a live region again. */
    private static void setIfChanged(TextView v, String text) {
        if (!TextUtils.equals(v.getText(), text)) v.setText(text);
    }

    // ---------------------------------------------------------------- add / edit

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
        // the sheet saved on the server and reloaded the roster; StudentRepository tells the ViewModel
    }
}
