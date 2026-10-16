package zm.ac.mulungushi.registrar;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

public class StudentHomeActivity extends AppCompatActivity {

    public static final String EXTRA_STUDENT_NUMBER = "student_number";

    private Student student;
    private boolean warned;
    private AuthViewModel auth;
    private GroupViewModel groups;
    /** Redraws when a background upload (WorkManager) brings new data while this screen is open. */
    private final Runnable redraw = () -> {
        Student latest = StudentRepository.getInstance().me();
        if (latest == null || isFinishing() || isDestroyed()) return;
        student = latest;
        render();
        if (student.noticeKind != null) deliverNotice(); // a lecturer answered while this screen was open
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_student_home);
        ConnectivityBanner.attach(this);
        LiveRefresh.student(this);
        PullToRefresh.student(this);

        student = RosterUi.meOrSignIn(this);
        if (student == null) return;

        // "Welcome, Riley!" on the first sign-in, "Welcome back, Riley!" after: decided by AuthViewModel
        auth = new ViewModelProvider(this).get(AuthViewModel.class);
        TextView welcome = findViewById(R.id.textWelcome);
        auth.getWelcome().observe(this, g -> welcome.setText(
                getString(g.firstTime ? R.string.welcome_first : R.string.welcome_back, g.name)));

        // "Request for Group" (no group yet) or "Request for Group Change": from the student's
        // group as the server has it, updated by itself when it changes (GroupViewModel)
        groups = new ViewModelProvider(this).get(GroupViewModel.class);
        TextView requestTitle = findViewById(R.id.textGroupRequestTitle);
        groups.getScreen().observe(this, s -> {
            boolean first = s.mode == GroupRequestRules.Mode.FIRST_GROUP;
            requestTitle.setText(first ? R.string.card_group_first_title : R.string.card_group_change_title);
            Accessibility.explain(findViewById(R.id.cardGroupChange), first ? R.string.why_group_first : R.string.why_group_change);
        });

        findViewById(R.id.cardGroupChange).setOnClickListener(v -> openGroupChange());
        findViewById(R.id.cardEditDetails).setOnClickListener(v -> openEditDetails());
        findViewById(R.id.cardGroup).setOnClickListener(v -> openMyGroup());
        Accessibility.explain(findViewById(R.id.cardEditDetails), R.string.why_edit_details);
        Accessibility.explain(findViewById(R.id.cardGroup), R.string.why_my_group);
        BottomNav.bindStudent(this, BottomNav.HOME, student.number);

        render();
        RosterFormat.stagger(this);
        RosterFormat.growBar(findViewById(R.id.occupancyFill));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (student == null) return;
        StudentRepository.getInstance().addListener(redraw);
        render();
        Feedback.showPending(this);
        // what the server says now: group, open requests, answers from the lecturer
        RosterApi.get().loadMine(outcome -> {
            if (isFinishing() || isDestroyed()) return;
            if (!outcome.ok) {
                RosterUi.refreshFailed(this, outcome, warned);
                warned = true;
            }
            Student latest = StudentRepository.getInstance().me();
            if (latest == null) return;
            student = latest;
            render();
            if (outcome.ok && student.noticeKind != null) deliverNotice();
        });
        if (Notifier.askSoon) {
            Notifier.askSoon = false;
            if (Notifier.ASK.equals(Notifier.pref)) {
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(() -> Notifier.showSheet(this), 1200);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        StudentRepository.getInstance().removeListener(redraw);
    }

    /** A lecturer answered a request: say so, and alert if the student turned alerts on. */
    private void deliverNotice() {
        String message = Notifier.noticeText(this, student.noticeKind, student.noticeSubject);
        Notices.markSeen(student.id, student.noticeSubject, student.noticeRequestId);
        student.noticeKind = null;
        student.noticeSubject = null;
        Notifier.alert(this, message);
        Feedback.show(this, message);
    }

    private void render() {
        RosterFormat.bindHomeAvatar(this, student.name, SignOutSheet.studentDetail(this, student.number));

        auth.showWelcome(student.name);

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
        int waiting = StudentRepository.getInstance().getWaiting().size();
        boolean pending = student.pendingGroup != null || waiting > 0;
        View banner = findViewById(R.id.bannerSync);
        android.widget.ImageView icon = findViewById(R.id.bannerIcon);
        TextView text = findViewById(R.id.bannerText);

        if (pending) {
            banner.setBackgroundResource(R.drawable.bg_banner_indigo);
            icon.setImageResource(R.drawable.ic_sync);
            icon.setColorFilter(androidx.core.content.ContextCompat.getColor(this, R.color.indigo_700));
            text.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.indigo_700));
            // Waiting to upload (saved on this phone) comes first; otherwise the open request.
            String lead = (waiting > 0
                    ? getResources().getQuantityString(R.plurals.sync_waiting_short, waiting, waiting) + ". "
                    : getString(R.string.pending_sync_one)) + " ";
            String link = getString(R.string.view_status);
            android.text.SpannableString span = new android.text.SpannableString(lead + link);
            span.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    lead.length(), span.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new android.text.style.UnderlineSpan(),
                    lead.length(), span.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setText(span);
            banner.setOnClickListener(v -> openSync());
            Accessibility.explain(banner, R.string.why_sync_banner);
        } else {
            banner.setBackgroundResource(R.drawable.bg_card_green);
            icon.setImageResource(R.drawable.ic_check_circle);
            icon.setColorFilter(androidx.core.content.ContextCompat.getColor(this, R.color.green_700));
            text.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.green_700));
            text.setText(R.string.synced_ok);
            banner.setOnClickListener(null);
            banner.setClickable(false);
            Accessibility.plain(banner);
        }

        View pill = findViewById(R.id.pillPending);
        TextView requestSub = findViewById(R.id.textRequestSub);
        android.view.ViewGroup.MarginLayoutParams lp =
                (android.view.ViewGroup.MarginLayoutParams) requestSub.getLayoutParams();
        if (student.pendingGroup != null) {
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
