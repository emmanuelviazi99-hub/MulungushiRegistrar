package zm.ac.mulungushi.registrar;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ported from LecturerDashboard. The lecturer's Home tab: counts from the
 * server (reloaded each time the screen opens), a group-occupancy bar per
 * group, and quick actions leading to Requests, Sync, the roster, or straight
 * into Add student.
 *
 * "Conflicts" still comes from the Sync screen's fixed demo conflict: conflict
 * detection needs the offline sync queue (brief, Activity E), not built yet.
 *
 * The "Class list" card (ClassListViewModel): import the lecturer's spreadsheet
 * (system file picker, preview, then Import), download the group lists as an Excel
 * file (saved where the lecturer chooses), and add a group when all are full.
 */
public class LecturerDashboardActivity extends AppCompatActivity implements EditStudentSheet.Listener {

    private static final int PICK_CLASS_LIST = 41;
    private static final int SAVE_GROUP_LISTS = 42;
    private static final String[] SPREADSHEET_TYPES = {
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", // .xlsx
            "application/vnd.ms-excel",                                          // some phones label .xlsx/.csv so
            "text/csv", "text/comma-separated-values", "application/octet-stream",
    };
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private boolean warned;
    private final Runnable redraw = this::render;
    private AuthViewModel auth;
    private ClassListViewModel classList;
    /** Reading and writing the chosen file, off the main thread. */
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private boolean reading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!RosterUi.lecturerOrLeave(this)) return; // lecturers only
        setContentView(R.layout.activity_lecturer_dashboard);
        auth = new ViewModelProvider(this).get(AuthViewModel.class);
        TextView welcome = findViewById(R.id.textWelcome);
        auth.getWelcome().observe(this, g -> welcome.setText(
                getString(g.firstTime ? R.string.welcome_first : R.string.welcome_back, g.name)));
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
        Accessibility.explain(findViewById(R.id.actionRequests), R.string.why_requests);
        Accessibility.explain(findViewById(R.id.actionSync), R.string.why_sync_lecturer);
        Accessibility.explain(findViewById(R.id.actionAddStudent), R.string.why_add_student);

        classList = new ViewModelProvider(this).get(ClassListViewModel.class);
        findViewById(R.id.actionImportClassList).setOnClickListener(v -> pickClassList());
        findViewById(R.id.actionDownloadGroups).setOnClickListener(v -> classList.download());
        findViewById(R.id.actionAddGroup).setOnClickListener(v -> confirmAddGroup());
        Accessibility.explain(findViewById(R.id.actionImportClassList), R.string.why_import_class_list);
        Accessibility.explain(findViewById(R.id.actionDownloadGroups), R.string.why_download_groups);
        Accessibility.explain(findViewById(R.id.actionAddGroup), R.string.why_add_group);
        classList.isBusy().observe(this, busy -> renderClassList());
        classList.getResults().observe(this, e -> {
            ClassListViewModel.Result r = e.take();
            if (r != null) onClassListResult(r);
        });
        // turned the phone while the preview was open: show it again
        if (savedInstanceState != null && classList.pendingPreview() != null) showPreview(classList.pendingPreview());

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
        auth.showWelcome(RosterUi.lecturerName());

        StudentRepository repo = StudentRepository.getInstance();
        int activeCount = repo.getActive().size();
        int groupsFull = 0;
        for (String g : StudentRepository.GROUPS) if (repo.isFull(g)) groupsFull++;
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
        shareRow.findViewById(R.id.buttonViewGroups).setOnClickListener(v -> startActivity(LecturerGroupsActivity.open(this, null)));
        Accessibility.explain(shareRow.findViewById(R.id.buttonViewGroups), R.string.why_view_groups);
        shareRow.findViewById(R.id.buttonShareSummary).setOnClickListener(v -> shareSummary());
        Accessibility.explain(shareRow.findViewById(R.id.buttonShareSummary), R.string.why_share_summary);
        occupancy.addView(shareRow);
        renderClassList();
    }

    // ---------------------------------------------------------------- class list

    private void renderClassList() {
        if (classList == null) return;
        boolean busy = reading || Boolean.TRUE.equals(classList.isBusy().getValue());
        StudentRepository repo = StudentRepository.getInstance();
        ((TextView) findViewById(R.id.actionImportSub)).setText(
                reading ? getString(R.string.class_list_reading)
                        : busy ? getString(R.string.class_list_working) : getString(R.string.action_import_sub));
        ((TextView) findViewById(R.id.actionDownloadSub)).setText(R.string.action_download_sub);
        ((TextView) findViewById(R.id.actionAddGroupSub)).setText(repo.allGroupsFull()
                ? getString(R.string.action_add_group_sub_full)
                : getString(R.string.action_add_group_sub_fmt, repo.nextGroupCode(), StudentRepository.CAPACITY));
        for (int id : new int[]{R.id.actionImportClassList, R.id.actionDownloadGroups, R.id.actionAddGroup}) {
            View row = findViewById(id);
            row.setEnabled(!busy);
            row.setAlpha(busy ? 0.55f : 1f);
        }
    }

    private void pickClassList() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, SPREADSHEET_TYPES);
        try {
            startActivityForResult(pick, PICK_CLASS_LIST);
        } catch (android.content.ActivityNotFoundException e) {
            Feedback.problem(this, R.string.import_no_picker);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = data == null ? null : data.getData();
        if (requestCode == PICK_CLASS_LIST) {
            if (resultCode == Activity.RESULT_OK && uri != null) readAndPreview(uri);
        } else if (requestCode == SAVE_GROUP_LISTS) {
            ClassListApi.File file = classList.takeDownloaded();
            if (resultCode == Activity.RESULT_OK && uri != null && file != null) save(file, uri);
        }
    }

    /** Reads the chosen file (up to 3 MB) off the main thread, then asks the server for a preview. */
    private void readAndPreview(Uri uri) {
        reading = true;
        renderClassList();
        files.execute(() -> {
            String name = displayName(uri);
            byte[] bytes = null;
            int problem = 0;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("no stream");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    if (out.size() > ClassListApi.MAX_FILE_BYTES) { problem = R.string.import_file_too_big; break; }
                }
                if (problem == 0) bytes = out.toByteArray();
            } catch (IOException | SecurityException e) {
                problem = R.string.import_file_unreadable;
            }
            final byte[] read = bytes;
            final int why = problem;
            runOnUiThread(() -> {
                reading = false;
                if (isFinishing() || isDestroyed()) return;
                if (read == null || read.length == 0) {
                    Feedback.problem(this, why != 0 ? why : R.string.import_file_unreadable);
                    renderClassList();
                    return;
                }
                classList.preview(read, name);
                renderClassList();
            });
        });
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.trim().isEmpty()) return n.trim();
            }
        } catch (RuntimeException ignored) {
            // some providers don't answer: the server reads the file either way
        }
        return "class-list.xlsx";
    }

    private void onClassListResult(ClassListViewModel.Result r) {
        switch (r.kind) {
            case PREVIEW:
                showPreview(r.summary);
                break;
            case IMPORTED:
                new AlertDialog.Builder(this)
                        .setTitle(R.string.import_done_title)
                        .setMessage(r.summary.resultText())
                        .setPositiveButton(R.string.action_ok, null)
                        .show();
                render();
                break;
            case DOWNLOADED:
                Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(XLSX)
                        .putExtra(Intent.EXTRA_TITLE, r.file.fileName);
                try {
                    startActivityForResult(create, SAVE_GROUP_LISTS);
                } catch (android.content.ActivityNotFoundException e) {
                    classList.takeDownloaded();
                    Feedback.problem(this, R.string.import_no_picker);
                }
                break;
            case GROUP_ADDED:
                Feedback.show(this, getString(R.string.add_group_done_fmt, r.groupCode == null ? "" : r.groupCode));
                render();
                break;
            case FAILED:
                RosterUi.report(this, r.outcome);
                break;
        }
        renderClassList();
    }

    /** What the import would do; "Import N students" saves it, anything else changes nothing. */
    private void showPreview(ClassListImport preview) {
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setMessage(preview.previewText())
                .setOnCancelListener(d -> classList.cancelImport());
        if (preview.toAdd > 0) {
            b.setTitle(R.string.import_preview_title)
                    .setPositiveButton(preview.confirmLabel(), (d, w) -> classList.confirmImport())
                    .setNegativeButton(R.string.action_cancel, (d, w) -> classList.cancelImport());
        } else {
            b.setTitle(R.string.import_nothing_title)
                    .setPositiveButton(R.string.action_ok, (d, w) -> classList.cancelImport());
        }
        b.show();
    }

    private void save(ClassListApi.File file, Uri uri) {
        files.execute(() -> {
            boolean ok;
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("no stream");
                out.write(file.bytes);
                ok = true;
            } catch (IOException | SecurityException | IllegalArgumentException e) {
                ok = false;
            }
            final boolean saved = ok;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (saved) Feedback.show(this, getString(R.string.download_saved_fmt, file.fileName));
                else Feedback.problem(this, R.string.download_failed);
            });
        });
    }

    private void confirmAddGroup() {
        String next = StudentRepository.getInstance().nextGroupCode();
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.add_group_title_fmt, next))
                .setMessage(getString(R.string.add_group_message_fmt, next, StudentRepository.CAPACITY))
                .setPositiveButton(R.string.add_group_confirm, (d, w) -> classList.addGroup())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        files.shutdown();
    }

    /** Opens the Android Sharesheet with the group labels and counts only. No names, no student numbers. */
    private void shareSummary() {
        StudentRepository repo = StudentRepository.getInstance();
        String title = getString(R.string.share_summary_title);
        StringBuilder text = new StringBuilder(title);
        for (String g : StudentRepository.GROUPS) {
            text.append('\n').append(getString(R.string.group_summary_line,
                    g, repo.groupCount(g), repo.capacityOf(g)));
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

        int capacity = StudentRepository.getInstance().capacityOf(group);
        boolean full = count >= capacity;
        View fill = new View(this);
        fill.setBackgroundResource(full ? R.drawable.bg_fill_red : R.drawable.bg_fill_navy);
        float frac = Math.min(1f, count / (float) capacity);
        track.addView(fill, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, frac == 0 ? 0.0001f : frac));
        RosterFormat.growBar(fill);
        View empty = new View(this);
        track.addView(empty, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f - frac == 0 ? 0.0001f : 1f - frac));
        row.addView(track);

        TextView countView = new TextView(this);
        countView.setText(count + "/" + capacity);
        countView.setTextColor(getResources().getColor(R.color.slate_500));
        countView.setTextSize(11.5f);
        countView.setLayoutParams(new LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT));
        countView.setGravity(Gravity.END);
        row.addView(countView);

        // a tap opens that group's members (Groups screen)
        row.setMinimumHeight(dp(44));
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> startActivity(LecturerGroupsActivity.open(this, group)));
        Accessibility.explainRow(row, getString(R.string.why_open_group_row));

        // TalkBack reads the whole row as one sentence, not "G01", then "14 slash 15"
        row.setContentDescription(getString(R.string.group_summary_line, group, count, capacity));
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
