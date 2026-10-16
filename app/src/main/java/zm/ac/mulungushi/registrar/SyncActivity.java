package zm.ac.mulungushi.registrar;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.ImageViewCompat;

import org.json.JSONObject;

import java.util.List;

/**
 * Sync status: what is saved on this phone and what still has to reach the server.
 *
 *   - "Last synced: 5 minutes ago" and Sync now (UI/UX team's design), then the
 *     phone storage card: where the data on screen came from, how many changes wait.
 *   - Action required: changes the server refused. When someone else changed or
 *     removed the record first, the card shows both versions side by side, with
 *     Keep mine (send my change again on top of theirs) or Use theirs.
 *   - Waiting to upload: changes saved on this phone, oldest first, each one
 *     Saved locally (not tried yet), Pending (tried, no connection) or Syncing.
 *   - Student: the "Name and programme edit" row, Synced once the server has it.
 *   - Student: their open requests, which wait for a lecturer.
 *
 * Same layout as the prototype's SyncScreen; every row is real data. Statuses are
 * said in words, not by colour alone.
 */
public class SyncActivity extends AppCompatActivity {

    public static final String EXTRA_ROLE = "role";
    public static final String ROLE_STUDENT = "student";
    public static final String ROLE_LECTURER = "lecturer";

    private static final int PENDING = 0, SYNCING = 1, SYNCED = 2, ACTION = 3;

    private boolean lecturer;
    private boolean busy;
    private final Runnable redraw = this::render;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sync);
        ConnectivityBanner.attach(this);

        ((TextView) findViewById(R.id.topBarTitle)).setText(R.string.sync_status_title);
        TextView sub = findViewById(R.id.topBarSub);
        sub.setText(R.string.sync_status_sub);
        sub.setVisibility(View.VISIBLE);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        Accessibility.explain(findViewById(R.id.btnBack), R.string.why_back);
        RosterFormat.hideBack(this);
        findViewById(R.id.dashLine).setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        buildLegend();
        findViewById(R.id.btnSyncNow).setOnClickListener(v -> syncNow(true));
        Accessibility.explain(findViewById(R.id.btnSyncNow), R.string.why_sync_now);

        lecturer = ROLE_LECTURER.equals(getIntent().getStringExtra(EXTRA_ROLE));
        LiveRefresh.attach(this, () -> RosterApi.get().syncNow(null));
        PullToRefresh.attach(this, done -> RosterApi.get().syncNow(done));
        if (lecturer) {
            RosterFormat.bindAvatar(this, RosterUi.lecturerName(), SignOutSheet.lecturerDetail(this));
            BottomNav.bindLecturer(this, BottomNav.SYNC);
        } else {
            Student student = RosterUi.meOrSignIn(this);
            if (student == null) return;
            RosterFormat.bindAvatar(this, student.name, SignOutSheet.studentDetail(this, student.number));
            BottomNav.bindStudent(this, BottomNav.SYNC, student.number);
        }
        render();
        RosterFormat.stagger(this);
        syncNow(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        StudentRepository.getInstance().addListener(redraw);
    }

    @Override
    protected void onPause() {
        super.onPause();
        StudentRepository.getInstance().removeListener(redraw);
    }

    /** Uploads what is waiting and reloads. Says how it went only when the person asked. */
    private void syncNow(boolean asked) {
        if (busy) return;
        busy = true;
        render();
        RosterApi.get().syncNow(outcome -> {
            busy = false;
            if (isFinishing() || isDestroyed()) return;
            render();
            if (!asked) return;
            if (RosterUi.signedOut(this, outcome)) return;
            StudentRepository repo = StudentRepository.getInstance();
            if (!outcome.ok) Feedback.show(this, RosterUi.message(this, outcome));
            else if (!repo.getWaiting().isEmpty()) Feedback.show(this, R.string.sync_still_waiting);
            else Feedback.show(this, R.string.sync_all_uploaded);
        });
    }

    // ---------- the screen ----------

    private void render() {
        if (isFinishing() || isDestroyed()) return;
        LinearLayout content = findViewById(R.id.syncContent);
        content.removeAllViews();
        StudentRepository repo = StudentRepository.getInstance();
        List<PendingOp> waiting = repo.getWaiting();
        List<PendingOp> refused = repo.getRefused();

        // Last synced + Sync now
        ((TextView) findViewById(R.id.textLastSynced)).setText(
                getString(R.string.sync_last_synced_label, lastSyncedText(repo.dataSavedAt())));
        Button now = findViewById(R.id.btnSyncNow);
        now.setEnabled(!busy);
        now.setText(busy ? R.string.action_syncing_short : R.string.action_sync_now);

        content.addView(storageCard(repo, waiting.size()));

        for (PendingOp op : refused) content.addView(refusedCard(op));

        boolean nameEditWaiting = false;
        for (PendingOp op : waiting) {
            boolean nameEdit = PendingOp.UPDATE_ME.equals(op.type);
            nameEditWaiting |= nameEdit;
            String title = nameEdit ? getString(R.string.name_programme_edit_title) : op.label;
            String when = RosterUi.savedAtText(op.createdAt);
            // Activity E: Saved locally (not tried yet) -> Pending (tried, no connection) -> Syncing (now)
            if (busy) {
                content.addView(statusCard(title, getString(R.string.sync_syncing_now_note), R.string.pill_syncing, SYNCING, 12));
            } else if (op.tries == 0) {
                content.addView(statusCard(title, getString(R.string.sync_saved_locally_note, when), R.string.pill_local, PENDING, 12));
            } else {
                content.addView(statusCard(title, getString(R.string.sync_pending_note, when), R.string.pill_pending, PENDING, 12));
            }
        }

        if (!lecturer) {
            if (!nameEditWaiting && repo.dataSavedAt() > 0) {
                content.addView(statusCard(getString(R.string.name_programme_edit_title),
                        getString(R.string.sync_edit_synced_note), R.string.pill_synced, SYNCED, 16));
            }
            studentRequests(content, repo);
        }

        String text;
        int tone;
        if (!refused.isEmpty()) {
            text = getResources().getQuantityString(R.plurals.sync_refused_banner, refused.size(), refused.size());
            tone = 1;
        } else if (!waiting.isEmpty()) {
            text = getResources().getQuantityString(R.plurals.sync_waiting_upload, waiting.size(), waiting.size());
            tone = 0;
        } else {
            text = getString(lecturer ? R.string.sync_all_uploaded_lecturer : R.string.sync_up_to_date_banner);
            tone = 2;
        }
        content.addView(banner(tone, text));

        ((TextView) findViewById(R.id.textFootnote)).setText(
                lecturer ? R.string.sync_footnote_lecturer : R.string.sync_footnote_student);
    }

    /** Where the data on screen came from, how much is waiting, and Sync now. */
    private View storageCard(StudentRepository repo, int waiting) {
        LinearLayout card = newCard(16);
        int pillText;
        int tone;
        if (busy) { pillText = R.string.pill_syncing; tone = SYNCING; }
        else if (waiting > 0 || repo.isOffline()) { pillText = R.string.pill_pending; tone = PENDING; }
        else { pillText = R.string.pill_synced; tone = SYNCED; }
        card.addView(headerRow(getString(R.string.sync_storage_title), pillText, tone));

        long saved = repo.dataSavedAt();
        String from = saved <= 0 ? getString(R.string.sync_storage_never)
                : getString(repo.isOffline() ? R.string.sync_storage_saved_offline : R.string.sync_storage_saved,
                RosterUi.savedAtText(saved));
        card.addView(body(from, 4));
        card.addView(body(getResources().getQuantityString(R.plurals.sync_storage_waiting, waiting, waiting), 0));
        return card;
    }

    /** A change the server refused: the reason, and what the person can do about it. */
    private View refusedCard(PendingOp op) {
        LinearLayout card = newCard(16);
        card.setBackgroundResource(R.drawable.bg_card_conflict);
        boolean conflict = op.isConflict();
        card.addView(headerRow(conflict ? getString(R.string.sync_conflict_heading, op.label)
                : getString(R.string.sync_refused_heading, op.label), R.string.pill_action, ACTION));

        Student theirs = lecturer ? StudentRepository.getInstance().findById(op.targetId) : null;
        boolean canKeep = conflict && PendingOp.SAVE_STUDENT.equals(op.type) && theirs != null;

        if (canKeep) {
            card.addView(body(getString(R.string.sync_conflict_explain), 12));
            LinearLayout boxes = new LinearLayout(this);
            boxes.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams boxesLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            boxesLp.bottomMargin = dp(12);
            boxes.setLayoutParams(boxesLp);
            boxes.addView(compareBox(R.string.sync_your_change, describeChange(op, theirs),
                    R.drawable.bg_dashed_box_sky, true));
            boxes.addView(compareBox(R.string.sync_current_server, describeRecord(theirs),
                    R.drawable.bg_dashed_box_grey, false));
            card.addView(boxes);

            LinearLayout buttons = new LinearLayout(this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            Button keep = actionButton(R.string.action_keep_mine, R.color.slate_100, R.color.navy_700);
            keep.setContentDescription(getString(R.string.action_keep_mine_desc, op.label));
            LinearLayout.LayoutParams keepLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            keepLp.setMarginEnd(dp(5));
            keep.setOnClickListener(v -> {
                v.setEnabled(false);
                RosterApi.get().keepMine(op, theirs.version, outcome -> afterChoice(outcome, R.string.toast_kept_mine));
            });
            buttons.addView(keep, keepLp);
            Button use = actionButton(R.string.action_use_theirs, R.color.navy_600, R.color.white);
            use.setContentDescription(getString(R.string.action_use_theirs_desc, op.label));
            LinearLayout.LayoutParams useLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            useLp.setMarginStart(dp(5));
            use.setOnClickListener(v -> {
                v.setEnabled(false);
                RosterApi.get().dismiss(op.opId, outcome -> afterChoice(outcome, R.string.toast_used_theirs));
            });
            buttons.addView(use, useLp);
            card.addView(buttons);
            return card;
        }

        String reason = refusalText(op);
        card.addView(body(reason, 12));
        Button ok = actionButton(R.string.action_ok_remove, R.color.navy_600, R.color.white);
        ok.setOnClickListener(v -> {
            v.setEnabled(false);
            RosterApi.get().dismiss(op.opId, outcome -> afterChoice(outcome, 0));
        });
        card.addView(ok, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private void afterChoice(RosterApi.Outcome outcome, int toastRes) {
        if (isFinishing() || isDestroyed()) return;
        render();
        if (RosterUi.signedOut(this, outcome)) return;
        if (!outcome.ok) Feedback.show(this, RosterUi.message(this, outcome));
        else if (toastRes != 0) Feedback.show(this, RosterUi.okText(this, outcome, toastRes));
    }

    /** "Just now", "5 minutes ago", "3 days ago", a date after a week, or "Not synced yet". */
    private String lastSyncedText(long whenMillis) {
        if (whenMillis <= 0) return getString(R.string.sync_last_synced_never);
        long minutes = Math.max(0, (System.currentTimeMillis() - whenMillis) / 60_000L);
        if (minutes < 1) return getString(R.string.sync_last_synced_just_now);
        if (minutes < 60) return getResources().getQuantityString(R.plurals.sync_minutes_ago, (int) minutes, (int) minutes);
        long hours = minutes / 60;
        if (hours < 24) return getResources().getQuantityString(R.plurals.sync_hours_ago, (int) hours, (int) hours);
        long days = hours / 24;
        if (days < 7) return getResources().getQuantityString(R.plurals.sync_days_ago, (int) days, (int) days);
        return new java.text.SimpleDateFormat("d MMM", java.util.Locale.ENGLISH).format(new java.util.Date(whenMillis));
    }

    /** The server's reason, in plain words. */
    private String refusalText(PendingOp op) {
        if ("STUDENT_NOT_FOUND".equals(op.error)) return getString(R.string.error_student_gone);
        if ("REQUEST_NOT_FOUND".equals(op.error)) return getString(R.string.error_request_gone);
        if ("VERSION_CONFLICT".equals(op.error)) return getString(R.string.sync_conflict_gone);
        if (op.message != null && !op.message.isEmpty()) return op.message;
        return getString(R.string.error_server);
    }

    /** "Name: …\nGroup: …" for the fields this change sets. */
    private String describeChange(PendingOp op, Student current) {
        JSONObject b = op.bodyJson();
        StringBuilder t = new StringBuilder();
        line(t, R.string.field_name, b.optString("fullName", current.name));
        line(t, R.string.field_number, b.optString("studentNumber", current.number));
        line(t, R.string.field_programme, b.optString("programmeCode", current.programme));
        line(t, R.string.field_group, Overlay.groupOf(b.has("groupCode") ? b.optString("groupCode") : current.group));
        return t.toString().trim();
    }

    private String describeRecord(Student s) {
        StringBuilder t = new StringBuilder();
        line(t, R.string.field_name, s.name);
        line(t, R.string.field_number, s.number);
        line(t, R.string.field_programme, s.programme);
        line(t, R.string.field_group, s.group);
        return t.toString().trim();
    }

    private void line(StringBuilder t, int labelRes, String value) {
        t.append(getString(labelRes)).append(": ").append(value == null ? "—" : value).append('\n');
    }

    /** A student's open requests: they wait for a lecturer, not for the network. */
    private void studentRequests(LinearLayout content, StudentRepository repo) {
        Student student = repo.me();
        if (student == null) return;
        if (student.pendingGroup != null && student.pendingGroupRequestId > 0) {
            String on = student.pendingGroupOn == null ? "today" : student.pendingGroupOn;
            content.addView(statusCard(getString(R.string.sync_group_transfer, student.pendingGroup),
                    getString(R.string.sync_group_transfer_note, on), R.string.pill_pending_short, PENDING, 12));
        }
        if (student.pendingNumber != null && student.pendingNumberRequestId > 0) {
            String on = student.pendingNumberOn == null ? "today" : student.pendingNumberOn;
            content.addView(statusCard(getString(R.string.sync_number_title, student.pendingNumber),
                    getString(R.string.sync_number_note, on, student.number), R.string.pill_pending_short, PENDING, 12));
        }
    }

    // ---------- legend ----------

    private void buildLegend() {
        LinearLayout box = findViewById(R.id.legendRow);
        LinearLayout row1 = newLegendRow();
        LinearLayout row2 = newLegendRow();
        box.addView(row1);
        box.addView(row2);
        addLegendPill(row1, R.string.pill_local, R.drawable.bg_pill_slate, R.color.slate_600);
        addLegendPill(row1, R.string.pill_pending, R.drawable.bg_pill_indigo, R.color.indigo_600);
        addLegendPill(row1, R.string.pill_syncing, R.drawable.bg_pill_sky, R.color.sky_700);
        addLegendPill(row2, R.string.pill_synced, R.drawable.bg_pill_green, R.color.green_700);
        addLegendPill(row2, R.string.pill_action, R.drawable.bg_pill_red, R.color.red_600);
    }

    private LinearLayout newLegendRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        r.setLayoutParams(lp);
        return r;
    }

    private void addLegendPill(LinearLayout row, int textRes, int bgRes, int colorRes) {
        TextView pill = new TextView(this);
        pill.setText(textRes);
        pill.setBackgroundResource(bgRes);
        pill.setTextColor(getResources().getColor(colorRes));
        pill.setTextSize(11f);
        pill.setTypeface(null, Typeface.BOLD);
        pill.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        RosterFormat.addDot(pill, getResources().getColor(colorRes));
        row.addView(pill, lp);
    }

    // ---------- shared pieces ----------

    private View compareBox(int titleRes, String text, int bgRes, boolean first) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(bgRes);
        box.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        if (first) lp.setMarginEnd(dp(4)); else lp.setMarginStart(dp(4));
        box.setLayoutParams(lp);

        TextView title = new TextView(this);
        title.setText(getString(titleRes).toUpperCase());
        title.setTextSize(11f);
        title.setTypeface(null, Typeface.BOLD);
        title.setLetterSpacing(0.04f);
        title.setTextColor(getResources().getColor(R.color.slate_500));
        title.setPadding(0, 0, 0, dp(4));
        box.addView(title);

        TextView body = new TextView(this);
        body.setText(text);
        body.setTextSize(12f);
        body.setLineSpacing(dp(2), 1f);
        body.setTextColor(getResources().getColor(R.color.navy_900));
        box.addView(body);
        return box;
    }

    private LinearLayout newCard(int bottomMarginDp) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card_plain);
        card.setElevation(dp(2));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(bottomMarginDp);
        card.setLayoutParams(lp);
        return card;
    }

    private View statusCard(String title, String sub, int pillTextRes, int tone, int bottomMarginDp) {
        LinearLayout card = newCard(bottomMarginDp);
        card.addView(headerRow(title, pillTextRes, tone));
        card.addView(body(sub, 0));
        return card;
    }

    private View headerRow(String title, int pillTextRes, int tone) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = dp(4);
        header.setLayoutParams(hlp);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setTextColor(getResources().getColor(R.color.navy_900));
        titleView.setTextSize(14f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMarginEnd(dp(8));
        header.addView(titleView, titleLp);

        int bg, color;
        switch (tone) {
            case SYNCING: bg = R.drawable.bg_pill_sky; color = R.color.sky_700; break;
            case SYNCED: bg = R.drawable.bg_pill_green; color = R.color.green_700; break;
            case ACTION: bg = R.drawable.bg_pill_red; color = R.color.red_600; break;
            default: bg = R.drawable.bg_pill_indigo; color = R.color.indigo_600; break;
        }
        TextView pill = new TextView(this);
        pill.setText(pillTextRes);
        pill.setBackgroundResource(bg);
        pill.setTextColor(getResources().getColor(color));
        pill.setTextSize(11f);
        pill.setTypeface(null, Typeface.BOLD);
        pill.setPadding(dp(10), dp(4), dp(10), dp(4));
        RosterFormat.addDot(pill, getResources().getColor(color));
        header.addView(pill);
        return header;
    }

    private TextView body(String text, int bottomMarginDp) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(getResources().getColor(R.color.slate_500));
        tv.setTextSize(12.5f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(bottomMarginDp);
        tv.setLayoutParams(lp);
        return tv;
    }

    private Button actionButton(int label, int bgColor, int textColor) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15f);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(getResources().getColor(textColor));
        b.setStateListAnimator(null);
        b.setElevation(0f);
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getResources().getColor(bgColor));
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        return b;
    }

    /** Prototype Banner: tinted rounded box, 18dp icon, 13sp text. tone 0 = waiting, 1 = warning, 2 = all good. */
    private View banner(int tone, String text) {
        int bg, fg, icon;
        switch (tone) {
            case 1: bg = R.color.amber_50; fg = R.color.amber_700; icon = R.drawable.ic_alert_circle; break;
            case 2: bg = R.color.green_50; fg = R.color.green_700; icon = R.drawable.ic_check_circle; break;
            default: bg = R.color.indigo_50; fg = R.color.indigo_700; icon = R.drawable.ic_sync; break;
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(getResources().getColor(bg));
        shape.setCornerRadius(dp(12));
        row.setBackground(shape);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(16);
        row.setLayoutParams(lp);

        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        ImageViewCompat.setImageTintList(iv, ColorStateList.valueOf(getResources().getColor(fg)));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(18), dp(18));
        ilp.topMargin = dp(1);
        ilp.setMarginEnd(dp(10));
        row.addView(iv, ilp);

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setLineSpacing(0f, 1.1f);
        tv.setTextColor(getResources().getColor(fg));
        row.addView(tv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
