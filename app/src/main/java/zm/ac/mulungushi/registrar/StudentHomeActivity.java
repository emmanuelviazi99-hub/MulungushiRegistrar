package zm.ac.mulungushi.registrar;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class StudentHomeActivity extends AppCompatActivity {

    public static final String EXTRA_STUDENT_NUMBER = "student_number";

    private Student student;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_student_home);
        ConnectivityBanner.attach(this);

        String number = getIntent().getStringExtra(EXTRA_STUDENT_NUMBER);
        if (number == null) number = StudentRepository.DEMO_NUMBER_FALLBACK;
        student = StudentRepository.getInstance().findOrCreateDemoStudent(number);

        findViewById(R.id.cardGroupChange).setOnClickListener(v -> openGroupChange());
        findViewById(R.id.cardEditDetails).setOnClickListener(v -> openEditDetails());
        findViewById(R.id.cardGroup).setOnClickListener(v -> openMyGroup());
        BottomNav.bindStudent(this, BottomNav.HOME, student.number);

        render();
        RosterFormat.stagger(this);
        RosterFormat.growBar(findViewById(R.id.occupancyFill));
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        if (student.noticeKind != null) {
            deliverNotice();
        } else {
            Feedback.showPending(this);
        }
        if (Notifier.askSoon) {
            Notifier.askSoon = false;
            if (Notifier.ASK.equals(Notifier.pref)) {
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(() -> Notifier.showSheet(this), 1200);
            }
        }
    }

    /** A lecturer answered a request: say so, and alert if the student turned alerts on. */
    private void deliverNotice() {
        boolean approved = "approved".equals(student.noticeKind);
        boolean group = "group".equals(student.noticeSubject);
        int res = group
                ? (approved ? R.string.notice_group_approved : R.string.notice_group_declined)
                : (approved ? R.string.notice_number_approved : R.string.notice_number_declined);
        String message = getString(res);
        student.noticeKind = null;
        student.noticeSubject = null;
        Notifier.alert(this, message);
        Feedback.show(this, message);
    }

    private void render() {
        RosterFormat.bindHomeAvatar(this, student.name, SignOutSheet.studentDetail(this, student.number));

        TextView welcome = findViewById(R.id.textWelcome);
        welcome.setText(getString(R.string.welcome_back, firstName(student.name)));

        boolean assigned = !StudentRepository.UNASSIGNED.equals(student.group);
        int count = assigned ? StudentRepository.getInstance().groupCount(student.group) : 0;

        TextView groupTitle = findViewById(R.id.textGroupTitle);
        TextView groupSub = findViewById(R.id.textGroupSub);
        groupTitle.setText(assigned ? getString(R.string.lab_group_prefix, student.group) : getString(R.string.no_group_yet));
        groupSub.setText(assigned
                ? getString(R.string.group_places_filled, count, StudentRepository.CAPACITY)
                : getString(R.string.request_group_below));

        View fill = findViewById(R.id.occupancyFill);
        View empty = findViewById(R.id.occupancyEmpty);
        View track = findViewById(R.id.occupancyBar);
        android.widget.LinearLayout.LayoutParams fillLp = (android.widget.LinearLayout.LayoutParams) fill.getLayoutParams();
        android.widget.LinearLayout.LayoutParams emptyLp = (android.widget.LinearLayout.LayoutParams) empty.getLayoutParams();
        int remaining = Math.max(StudentRepository.CAPACITY - count, 0);
        fillLp.weight = assigned ? count : 0f;
        emptyLp.weight = assigned ? Math.max(remaining, 0.0001f) : 1f;
        fill.setLayoutParams(fillLp);
        empty.setLayoutParams(emptyLp);
        track.setVisibility(assigned ? View.VISIBLE : View.GONE);
        if (assigned && count >= StudentRepository.CAPACITY) {
            fill.setBackgroundResource(R.drawable.bg_fill_red);
        } else {
            fill.setBackgroundResource(R.drawable.bg_fill_navy);
        }

        TextView programmeSub = findViewById(R.id.textProgrammeSub);
        programmeSub.setText(getString(R.string.card_edit_details_sub, student.programme));

        renderPending();
    }

    /**
     * Same rule as the prototype: with an open request the banner is the indigo
     * "N change pending sync. View status" one and the request card shows a Pending
     * pill; with none it is the green "All changes are synced." banner.
     */
    private void renderPending() {
        boolean pending = student.pendingGroup != null;
        View banner = findViewById(R.id.bannerSync);
        android.widget.ImageView icon = findViewById(R.id.bannerIcon);
        TextView text = findViewById(R.id.bannerText);

        if (pending) {
            banner.setBackgroundResource(R.drawable.bg_banner_indigo);
            icon.setImageResource(R.drawable.ic_sync);
            icon.setColorFilter(androidx.core.content.ContextCompat.getColor(this, R.color.indigo_700));
            text.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.indigo_700));
            String lead = getString(R.string.pending_sync_one) + " ";
            String link = getString(R.string.view_status);
            android.text.SpannableString span = new android.text.SpannableString(lead + link);
            span.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    lead.length(), span.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new android.text.style.UnderlineSpan(),
                    lead.length(), span.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setText(span);
            banner.setOnClickListener(v -> openSync());
        } else {
            banner.setBackgroundResource(R.drawable.bg_card_green);
            icon.setImageResource(R.drawable.ic_check_circle);
            icon.setColorFilter(androidx.core.content.ContextCompat.getColor(this, R.color.green_700));
            text.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.green_700));
            text.setText(R.string.synced_ok);
            banner.setOnClickListener(null);
            banner.setClickable(false);
        }

        View pill = findViewById(R.id.pillPending);
        TextView requestSub = findViewById(R.id.textRequestSub);
        android.view.ViewGroup.MarginLayoutParams lp =
                (android.view.ViewGroup.MarginLayoutParams) requestSub.getLayoutParams();
        if (pending) {
            pill.setVisibility(View.VISIBLE);
            requestSub.setText(getString(R.string.move_to_group, student.pendingGroup));
            lp.topMargin = (int) (6 * getResources().getDisplayMetrics().density);
        } else {
            pill.setVisibility(View.GONE);
            requestSub.setText(R.string.card_group_change_sub);
            lp.topMargin = (int) (4 * getResources().getDisplayMetrics().density);
        }
        requestSub.setLayoutParams(lp);
    }

    private void openGroupChange() {
        startActivity(new Intent(this, GroupChangeActivity.class)
                .putExtra(EXTRA_STUDENT_NUMBER, student.number));
    }

    private void openEditDetails() {
        startActivity(new Intent(this, EditDetailsActivity.class)
                .putExtra(EXTRA_STUDENT_NUMBER, student.number));
    }

    private void openMyGroup() {
        startActivity(new Intent(this, MyGroupActivity.class)
                .putExtra(EXTRA_STUDENT_NUMBER, student.number));
    }

    private void openSync() {
        startActivity(new Intent(this, SyncActivity.class)
                .putExtra(EXTRA_STUDENT_NUMBER, student.number)
                .putExtra(SyncActivity.EXTRA_ROLE, SyncActivity.ROLE_STUDENT));
    }

    private static String firstName(String fullName) {
        if (fullName == null || fullName.trim().isEmpty()) return "";
        return fullName.trim().split("\\s+")[0];
    }

    private static String initialsOf(String name) {
        String t = name == null ? "" : name.trim();
        if (t.isEmpty()) return "?";
        String[] parts = t.split("\\s+");
        String first = String.valueOf(parts[0].charAt(0));
        if (parts.length > 1) return (first + parts[parts.length - 1].charAt(0)).toUpperCase();
        return first.toUpperCase();
    }

    private void notBuilt() {
        Toast.makeText(this, R.string.toast_not_built, Toast.LENGTH_SHORT).show();
    }

    private void signOut() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
