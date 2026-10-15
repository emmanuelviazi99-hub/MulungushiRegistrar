package zm.ac.mulungushi.registrar;

import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/** Ported from StudentProfile. Shows the signed-in student and their groupmates. */
public class MyGroupActivity extends AppCompatActivity {

    private final Runnable redraw = () -> {
        Student me = StudentRepository.getInstance().me();
        if (me != null && !isFinishing() && !isDestroyed()) render(me);
    };

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my_group);
        ConnectivityBanner.attach(this);
        LiveRefresh.student(this);
        PullToRefresh.student(this);

        ((TextView) findViewById(R.id.topBarTitle)).setText(R.string.my_group_title);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        Student self = RosterUi.meOrSignIn(this);
        if (self == null) return;
        RosterFormat.bindAvatar(this, self.name, SignOutSheet.studentDetail(this, self.number));
        RosterFormat.hideBack(this);
        BottomNav.bindStudent(this, BottomNav.MIDDLE, self.number);
        render(self);
        RosterFormat.stagger(this);

        // the group and its members as the server has them now
        RosterApi.get().loadMine(outcome -> {
            if (isFinishing() || isDestroyed()) return;
            if (!outcome.ok) RosterUi.refreshFailed(this, outcome, false);
            Student me = StudentRepository.getInstance().me();
            if (me != null) render(me);
        });
    }

    private void render(Student self) {
        ((TextView) findViewById(R.id.selfInitials)).setText(RosterFormat.initialsOf(self.name));
        ((TextView) findViewById(R.id.selfName)).setText(self.name);
        ((TextView) findViewById(R.id.selfIdLine)).setText(getString(R.string.my_group_id_line, self.number, self.programme));
        ((TextView) findViewById(R.id.badgeGroupSelf)).setText("Group: " + self.group);

        boolean assigned = !StudentRepository.UNASSIGNED.equals(self.group);
        StudentRepository repo = StudentRepository.getInstance();
        int count = assigned ? repo.groupCount(self.group) : 0;

        ((TextView) findViewById(R.id.textClassmatesOf)).setText(getString(R.string.my_group_classmates, self.group));
        ((TextView) findViewById(R.id.badgeCount)).setText(count + " of " + StudentRepository.CAPACITY);

        LinearLayout list = findViewById(R.id.membersList);
        list.removeAllViews();

        findViewById(R.id.textUnassignedNote).setVisibility(assigned ? View.GONE : View.VISIBLE);

        // names and numbers of the student's own group, from GET /groups/mine
        List<Student> members = assigned ? repo.myGroupMembers() : new java.util.ArrayList<>();
        int shown = members.size();
        for (int i = 0; i < members.size(); i++) {
            list.addView(buildRow(members.get(i), i < members.size() - 1));
        }
        findViewById(R.id.textEmptyMembers).setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
    }

    private View buildRow(Student s, boolean withDivider) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = dp(14);
        row.setPadding(pad, pad, pad, pad);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout avatar = new FrameLayout(this);
        avatar.setBackgroundResource(R.drawable.bg_avatar);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        avatar.setLayoutParams(avatarLp);
        TextView initials = new TextView(this);
        initials.setText(RosterFormat.initialsOf(s.name));
        initials.setTextColor(getResources().getColor(R.color.navy_700));
        initials.setTextSize(14.5f);
        FrameLayout.LayoutParams initialsLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        initialsLp.gravity = android.view.Gravity.CENTER;
        initials.setLayoutParams(initialsLp);
        avatar.addView(initials);
        row.addView(avatar);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMarginStart(dp(12));
        textCol.setLayoutParams(textLp);

        TextView name = new TextView(this);
        name.setText(s.name);
        name.setTextColor(getResources().getColor(R.color.navy_900));
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        name.setTextSize(15f);
        textCol.addView(name);

        TextView number = new TextView(this);
        number.setText(s.number);
        number.setTextColor(getResources().getColor(R.color.slate_400));
        number.setTextSize(13.5f);
        textCol.addView(number);

        row.addView(textCol);

        TextView statusBadge = new TextView(this);
        statusBadge.setText(R.string.status_active);
        statusBadge.setBackgroundResource(R.drawable.bg_card_green);
        statusBadge.setTextColor(getResources().getColor(R.color.green_700));
        statusBadge.setTextSize(12.5f);
        statusBadge.setPadding(dp(8), dp(3), dp(8), dp(3));
        row.addView(statusBadge);

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(row);
        if (withDivider) {
            View bottomLine = new View(this);
            bottomLine.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
            bottomLine.setBackgroundColor(getResources().getColor(R.color.slate_100));
            wrapper.addView(bottomLine);
        }
        return wrapper;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
