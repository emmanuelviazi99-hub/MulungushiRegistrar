package zm.ac.mulungushi.registrar;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * The roster's filter sheet (View layer only). The Filters button opens it with
 * both sections; the "Programme" and "Group" chips open it with just theirs.
 * Each pick goes straight to LecturerViewModel, the list behind updates at once,
 * and the sheet redraws its ticks and counts. "Reset" clears both filters;
 * "Show N students" closes it.
 *
 * TalkBack: the title and section names are headings; each option reads
 * "CS, 4 students, selected" (or "not selected"), and the dialog's title is
 * announced when it opens.
 */
final class RosterFilterSheet {

    static final int PROGRAMME = 1, GROUP = 2, BOTH = PROGRAMME | GROUP;

    private final Activity a;
    private final LecturerViewModel vm;
    private final int sections;
    private final BottomSheetDialog dialog;
    private final LinearLayout body;

    private RosterFilterSheet(Activity a, LecturerViewModel vm, int sections) {
        this.a = a;
        this.vm = vm;
        this.sections = sections;
        this.dialog = new BottomSheetDialog(a);
        this.body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
    }

    static void show(Activity a, LecturerViewModel vm, int sections) {
        if (a.isFinishing() || a.isDestroyed()) return;
        new RosterFilterSheet(a, vm, sections).open();
    }

    private void open() {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_sheet_top);
        root.setPadding(0, 0, 0, dp(12));

        View handle = new View(a);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(ContextCompat.getColor(a, R.color.slate_200));
        hb.setCornerRadius(dp(3));
        handle.setBackground(hb);
        handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(36), dp(5));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = dp(10);
        hlp.bottomMargin = dp(14);
        root.addView(handle, hlp);

        String titleText = a.getString(sections == PROGRAMME ? R.string.filter_programme
                : sections == GROUP ? R.string.filter_group : R.string.filter_sheet_title);
        TextView title = new TextView(a);
        title.setText(titleText);
        title.setTextColor(ContextCompat.getColor(a, R.color.navy_900));
        title.setTextSize(20f);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(20), 0, dp(20), dp(6));
        ViewCompat.setAccessibilityHeading(title, true);
        root.addView(title);

        ScrollView scroll = new ScrollView(a);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        render();

        dialog.setContentView(root);
        dialog.setTitle(titleText); // announced by TalkBack when the sheet opens
        View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) sheet.setBackgroundColor(Color.TRANSPARENT);
        dialog.show();
    }

    /** Draws the options for the current query; called again after each pick. */
    private void render() {
        body.removeAllViews();
        RosterQuery q = vm.query();

        if ((sections & PROGRAMME) != 0) {
            if (sections == BOTH) section(R.string.filter_programme);
            option(a.getString(R.string.filter_all_programmes), vm.countWithProgramme(RosterQuery.ALL),
                    RosterQuery.ALL.equals(q.programme), () -> vm.setProgramme(RosterQuery.ALL));
            for (String p : StudentRepository.PROGRAMMES) {
                option(p, vm.countWithProgramme(p), p.equals(q.programme), () -> vm.setProgramme(p));
            }
        }
        if ((sections & GROUP) != 0) {
            if (sections == BOTH) section(R.string.filter_group);
            option(a.getString(R.string.filter_all_groups), vm.countWithGroup(RosterQuery.ALL),
                    RosterQuery.ALL.equals(q.group), () -> vm.setGroup(RosterQuery.ALL));
            for (String g : StudentRepository.GROUPS) {
                option(g, vm.countWithGroup(g), g.equals(q.group), () -> vm.setGroup(g));
            }
            option(a.getString(R.string.unassigned), vm.countWithGroup(StudentRepository.UNASSIGNED),
                    StudentRepository.UNASSIGNED.equals(q.group), () -> vm.setGroup(StudentRepository.UNASSIGNED));
        }
        actions(q);
    }

    private void section(int labelRes) {
        TextView t = new TextView(a);
        t.setText(labelRes);
        t.setTextColor(ContextCompat.getColor(a, R.color.slate_500));
        t.setTextSize(13.5f);
        t.setAllCaps(true);
        t.setLetterSpacing(0.05f);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(dp(20), dp(14), dp(20), dp(6));
        ViewCompat.setAccessibilityHeading(t, true);
        body.addView(t);
    }

    private void option(String label, int count, boolean on, Runnable pick) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), 0, dp(20), 0);
        row.setMinimumHeight(dp(52));
        if (on) row.setBackgroundColor(ContextCompat.getColor(a, R.color.navy_50));
        row.setClickable(true);
        row.setFocusable(true);
        row.setSelected(on);
        String students = a.getResources().getQuantityString(R.plurals.filter_option_students, count, count);
        row.setContentDescription(label + ", " + students + ", "
                + a.getString(on ? R.string.a11y_selected : R.string.a11y_not_selected));
        row.setOnClickListener(v -> {
            pick.run();
            render();
        });

        TextView name = new TextView(a);
        name.setText(label);
        name.setTextSize(17f);
        name.setTextColor(ContextCompat.getColor(a, on ? R.color.navy_700 : R.color.navy_900));
        if (on) name.setTypeface(null, Typeface.BOLD);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView n = new TextView(a);
        n.setText(String.valueOf(count));
        n.setTextSize(15f);
        n.setTextColor(ContextCompat.getColor(a, R.color.slate_500));
        row.addView(n);

        TextView tick = new TextView(a);
        tick.setText(on ? "✓" : "");
        tick.setTextSize(20f);
        tick.setTypeface(null, Typeface.BOLD);
        tick.setGravity(Gravity.CENTER);
        tick.setTextColor(ContextCompat.getColor(a, R.color.navy_600));
        row.addView(tick, new LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.WRAP_CONTENT));

        body.addView(row);
    }

    /** "Reset" and "Show N students". */
    private void actions(RosterQuery q) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(20), dp(14), dp(20), 0);

        Button reset = new Button(a);
        reset.setText(R.string.filter_reset);
        reset.setAllCaps(false);
        reset.setMinHeight(dp(52));
        reset.setMinimumHeight(dp(52));
        reset.setTextSize(15f);
        reset.setTypeface(null, Typeface.BOLD);
        reset.setTextColor(ContextCompat.getColor(a, R.color.navy_900));
        GradientDrawable rb = new GradientDrawable();
        rb.setColor(ContextCompat.getColor(a, R.color.slate_100));
        rb.setCornerRadius(dp(14));
        reset.setBackground(rb);
        reset.setStateListAnimator(null);
        reset.setEnabled(q.hasFilters());
        reset.setAlpha(q.hasFilters() ? 1f : 0.5f);
        reset.setOnClickListener(v -> {
            vm.clearFilters();
            render();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rlp.setMarginEnd(dp(5));
        row.addView(reset, rlp);

        int matching = vm.matchingCount();
        Button done = new Button(a);
        done.setText(a.getResources().getQuantityString(R.plurals.filter_show_results, matching, matching));
        done.setAllCaps(false);
        done.setMinHeight(dp(52));
        done.setMinimumHeight(dp(52));
        done.setTextSize(15f);
        done.setTypeface(null, Typeface.BOLD);
        done.setTextColor(Color.WHITE);
        GradientDrawable db = new GradientDrawable();
        db.setColor(ContextCompat.getColor(a, R.color.navy_600));
        db.setCornerRadius(dp(14));
        done.setBackground(db);
        done.setStateListAnimator(null);
        done.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        dlp.setMarginStart(dp(5));
        row.addView(done, dlp);

        body.addView(row);
    }

    private int dp(int v) {
        return RosterFormat.dp(a, v);
    }
}
