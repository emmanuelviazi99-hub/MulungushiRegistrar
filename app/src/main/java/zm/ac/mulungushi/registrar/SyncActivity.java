package zm.ac.mulungushi.registrar;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.ImageViewCompat;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;

/**
 * Ported from SyncScreen, including the prototype's lecturer view: a retrying
 * "Syncing" row, the Mutinta Banda conflict card (offline proposal vs current
 * on server, with Keep / Accept), and the two notices. Those lecturer rows are
 * the prototype's showcase of every status, so they are fixed demo content;
 * the student view is built from the student's real requests.
 */
public class SyncActivity extends AppCompatActivity {

    public static final String EXTRA_ROLE = "role";
    public static final String ROLE_STUDENT = "student";
    public static final String ROLE_LECTURER = "lecturer";

    private static final int PENDING = 0, SYNCING = 1, SYNCED = 2, ACTION = 3;

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
        RosterFormat.hideBack(this);
        findViewById(R.id.dashLine).setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        buildLegend();

        String role = getIntent().getStringExtra(EXTRA_ROLE);
        if (ROLE_LECTURER.equals(role)) {
            RosterFormat.bindAvatar(this, "B. Nyirenda", SignOutSheet.lecturerDetail(this));
            BottomNav.bindLecturer(this, BottomNav.SYNC);
            renderLecturer();
        } else {
            renderStudent();
        }
        RosterFormat.stagger(this);
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

    // ---------- student ----------

    private void renderStudent() {
        String number = getIntent().getStringExtra(StudentHomeActivity.EXTRA_STUDENT_NUMBER);
        if (number == null) number = StudentRepository.DEMO_NUMBER_FALLBACK;
        Student student = StudentRepository.getInstance().findOrCreateDemoStudent(number);
        RosterFormat.bindAvatar(this, student.name, SignOutSheet.studentDetail(this, student.number));
        BottomNav.bindStudent(this, BottomNav.SYNC, student.number);

        LinearLayout content = findViewById(R.id.syncContent);
        int waiting = 0;

        if (student.pendingGroup != null) {
            String on = student.pendingGroupOn == null ? "today" : student.pendingGroupOn;
            content.addView(statusCard(getString(R.string.sync_group_transfer, student.pendingGroup),
                    getString(R.string.sync_group_transfer_note, on), R.string.pill_pending_short, PENDING, 12));
            waiting++;
        }
        if (student.pendingNumber != null) {
            String on = student.pendingNumberOn == null ? "today" : student.pendingNumberOn;
            content.addView(statusCard(getString(R.string.sync_number_title, student.pendingNumber),
                    getString(R.string.sync_number_note, on, student.number), R.string.pill_pending_short, PENDING, 12));
            waiting++;
        }
        // Name/programme edits apply straight away in this build, so this row is always Synced.
        content.addView(statusCard(getString(R.string.name_programme_edit_title),
                getString(R.string.sync_edit_synced_note), R.string.pill_synced, SYNCED, 16));

        String text;
        if (waiting == 0) text = getString(R.string.sync_up_to_date_banner);
        else if (waiting == 1) text = getString(R.string.sync_waiting_one);
        else text = getString(R.string.sync_waiting_many, waiting);
        content.addView(banner(waiting > 0 ? "offline" : "ok", text));

        ((TextView) findViewById(R.id.textFootnote)).setText(R.string.sync_footnote_student);
    }

    // ---------- lecturer ----------

    private void renderLecturer() {
        LinearLayout content = findViewById(R.id.syncContent);
        content.addView(statusCard(getString(R.string.sync_syncing_title),
                getString(R.string.sync_syncing_note), R.string.pill_syncing, SYNCING, 12));
        content.addView(conflictCard());
        content.addView(banner("warn", getString(R.string.sync_warn_banner)));
        content.addView(banner("ok", getString(R.string.sync_ok_banner)));
        ((TextView) findViewById(R.id.textFootnote)).setText(R.string.sync_footnote_lecturer);
    }

    private View conflictCard() {
        final StudentRepository repo = StudentRepository.getInstance();
        final boolean resolved = repo.isConflictResolved();

        LinearLayout card = newCard(16);
        card.setBackgroundResource(resolved ? R.drawable.bg_card_resolved : R.drawable.bg_card_conflict);

        card.addView(headerRow(getString(R.string.sync_conflict_title),
                resolved ? R.string.pill_resolved : R.string.pill_action, resolved ? SYNCED : ACTION));

        if (resolved) {
            card.addView(body(getString(R.string.sync_conflict_resolved_note), 0));
            return card;
        }

        card.addView(body(getString(R.string.sync_conflict_note), 12));

        LinearLayout boxes = new LinearLayout(this);
        boxes.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams boxesLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        boxesLp.bottomMargin = dp(12);
        boxes.setLayoutParams(boxesLp);
        boxes.addView(compareBox(R.string.sync_offline_proposal, R.string.sync_proposal_body,
                R.drawable.bg_dashed_box_sky, true));
        boxes.addView(compareBox(R.string.sync_current_server, R.string.sync_server_body,
                R.drawable.bg_dashed_box_grey, false));
        card.addView(boxes);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button keep = actionButton(R.string.action_keep_proposal, R.color.slate_100, R.color.navy_700);
        LinearLayout.LayoutParams keepLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        keepLp.setMarginEnd(dp(5));
        keep.setOnClickListener(v -> resolve(R.string.toast_kept_proposal));
        buttons.addView(keep, keepLp);

        Button accept = actionButton(R.string.action_accept_server, R.color.navy_600, R.color.white);
        LinearLayout.LayoutParams acceptLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        acceptLp.setMarginStart(dp(5));
        accept.setOnClickListener(v -> resolve(R.string.toast_accepted_server));
        buttons.addView(accept, acceptLp);

        card.addView(buttons);
        return card;
    }

    private void resolve(int toastRes) {
        StudentRepository.getInstance().resolveConflict();
        Feedback.show(this, toastRes);
        LinearLayout content = findViewById(R.id.syncContent);
        content.removeAllViews();
        renderLecturer();
    }

    private View compareBox(int titleRes, int bodyRes, int bgRes, boolean first) {
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

        TextView text = new TextView(this);
        text.setText(bodyRes);
        text.setTextSize(12f);
        text.setLineSpacing(dp(2), 1f);
        text.setTextColor(getResources().getColor(R.color.navy_900));
        box.addView(text);
        return box;
    }

    // ---------- shared pieces ----------

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

    /** Prototype Banner: tinted rounded box, 18dp icon, 13sp text. */
    private View banner(String tone, String text) {
        int bg, fg, icon;
        switch (tone) {
            case "warn": bg = R.color.amber_50; fg = R.color.amber_700; icon = R.drawable.ic_alert_circle; break;
            case "ok": bg = R.color.green_50; fg = R.color.green_700; icon = R.drawable.ic_check_circle; break;
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
