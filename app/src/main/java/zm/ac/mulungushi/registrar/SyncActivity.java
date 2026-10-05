package zm.ac.mulungushi.registrar;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/**
 * Ported from SyncScreen. The prototype's lecturer view includes a few
 * hardcoded dramatized rows (a specific "syncing" retry, a specific named
 * conflict) to showcase every status at once. Those aren't derived from any
 * real state in this build, so rather than invent fake data, this screen
 * shows the live pending requests from StudentRepository and says plainly
 * that offline queueing/conflict handling aren't built yet (see the brief's
 * Activity E). The status-pill legend itself is kept as reference.
 */
public class SyncActivity extends AppCompatActivity {

    public static final String EXTRA_ROLE = "role";
    public static final String ROLE_STUDENT = "student";
    public static final String ROLE_LECTURER = "lecturer";

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
        pill.setTextSize(13f);
        pill.setTypeface(pill.getTypeface(), android.graphics.Typeface.BOLD);
        int padH = dp(10), padV = dp(5);
        pill.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        RosterFormat.addDot(pill, getResources().getColor(colorRes));
        row.addView(pill, lp);
    }

    private void renderStudent() {
        String number = getIntent().getStringExtra(StudentHomeActivity.EXTRA_STUDENT_NUMBER);
        if (number == null) number = StudentRepository.DEMO_NUMBER_FALLBACK;
        Student student = StudentRepository.getInstance().findOrCreateDemoStudent(number);
        RosterFormat.bindAvatar(this, student.name, SignOutSheet.studentDetail(this, student.number));
        BottomNav.bindStudent(this, BottomNav.SYNC, student.number);

        LinearLayout content = findViewById(R.id.syncContent);
        int waiting = 0;

        if (student.pendingGroup != null) {
            content.addView(statusCard(
                    getString(R.string.to_arrow_fmt, getString(R.string.group_change_label), student.pendingGroup),
                    getString(R.string.requested_note), R.string.pill_pending_short, R.drawable.bg_pill_indigo, R.color.indigo_600));
            waiting++;
        }
        if (student.pendingNumber != null) {
            content.addView(statusCard(
                    getString(R.string.to_arrow_fmt, getString(R.string.number_correction_label), student.pendingNumber),
                    getString(R.string.number_correction_pending_note), R.string.pill_pending_short, R.drawable.bg_pill_indigo, R.color.indigo_600));
            waiting++;
        }
        // Name/programme edits apply immediately in this build (no offline queue yet), so this is always Synced.
        content.addView(statusCard(getString(R.string.name_programme_edit_title),
                getString(R.string.name_programme_synced_note), R.string.pill_synced, R.drawable.bg_pill_green, R.color.green_700));

        TextView banner = bannerView(waiting > 0
                ? getResources().getQuantityString(R.plurals.sync_waiting_banner_plural, waiting, waiting)
                : getString(R.string.sync_up_to_date_banner), waiting > 0);
        content.addView(banner, 0);

        ((TextView) findViewById(R.id.textFootnote)).setText(R.string.sync_footnote_student);
    }

    private void renderLecturer() {
        LinearLayout content = findViewById(R.id.syncContent);
        List<Student> pending = StudentRepository.getInstance().getPendingRequests();

        if (pending.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.no_pending_requests);
            empty.setTextColor(getResources().getColor(R.color.slate_400));
            empty.setTextSize(14.5f);
            empty.setPadding(0, dp(20), 0, dp(20));
            empty.setGravity(Gravity.CENTER);
            content.addView(empty);
        } else {
            for (Student s : pending) {
                if (s.pendingGroup != null) {
                    content.addView(statusCard(
                            s.name + " — " + getString(R.string.group_change_label),
                            getString(R.string.to_arrow_fmt, s.group, s.pendingGroup),
                            R.string.pill_pending_short, R.drawable.bg_pill_indigo, R.color.indigo_600));
                }
                if (s.pendingNumber != null) {
                    content.addView(statusCard(
                            s.name + " — " + getString(R.string.number_correction_label),
                            getString(R.string.to_arrow_fmt, s.number, s.pendingNumber),
                            R.string.pill_pending_short, R.drawable.bg_pill_indigo, R.color.indigo_600));
                }
            }
        }
        ((TextView) findViewById(R.id.textFootnote)).setText(R.string.sync_footnote_lecturer);
    }

    private View statusCard(String title, String sub, int pillTextRes, int pillBg, int pillColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card_plain);
        int pad = dp(14);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(10);
        card.setLayoutParams(cardLp);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);
        titleView.setTextColor(getResources().getColor(R.color.navy_900));
        titleView.setTextSize(16f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(titleView, titleLp);

        TextView pill = new TextView(this);
        pill.setText(pillTextRes);
        pill.setBackgroundResource(pillBg);
        pill.setTextColor(getResources().getColor(pillColor));
        pill.setTextSize(12.5f);
        pill.setTypeface(pill.getTypeface(), android.graphics.Typeface.BOLD);
        int ph = dp(9), pv = dp(4);
        pill.setPadding(ph, pv, ph, pv);
        RosterFormat.addDot(pill, getResources().getColor(pillColor));
        header.addView(pill);

        card.addView(header);

        TextView subView = new TextView(this);
        subView.setText(sub);
        subView.setTextColor(getResources().getColor(R.color.slate_500));
        subView.setTextSize(14f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        card.addView(subView, subLp);

        return card;
    }

    private TextView bannerView(String text, boolean waiting) {
        TextView banner = new TextView(this);
        banner.setText(text);
        banner.setBackgroundResource(waiting ? R.drawable.bg_banner_indigo : R.drawable.bg_card_green);
        banner.setTextColor(getResources().getColor(waiting ? R.color.indigo_700 : R.color.green_700));
        banner.setTextSize(14.5f);
        int pad = dp(14);
        banner.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        banner.setLayoutParams(lp);
        return banner;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
