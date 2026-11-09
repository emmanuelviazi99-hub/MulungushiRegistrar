package zm.ac.mulungushi.registrar;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The lecturer's Groups screen: pick a group along the top, see its members.
 *
 *   View        LecturerGroupsActivity: the group tabs, the header ("15 of 15 · Full"),
 *               the member list; a tap on a member opens the usual edit sheet
 *   ViewModel   this class: which group is chosen, and that group's members
 *   Repository  StudentRepository (the roster already loaded from GET /students and
 *               the totals from GET /groups), kept fresh by RosterApi.loadRoster()
 *
 * Nothing is fetched just for this screen: the roster is already on the phone, so
 * switching groups is instant and works offline with the saved copy.
 */
public class GroupsViewModel extends ViewModel {

    static final class Tab {
        final String group;   // G01... or StudentRepository.UNASSIGNED
        final int count;
        final int capacity;   // 0 for Unassigned
        final boolean full;

        Tab(String group, int count, int capacity, boolean full) {
            this.group = group;
            this.count = count;
            this.capacity = capacity;
            this.full = full;
        }
    }

    static final class Screen {
        final List<Tab> tabs;
        final String group;            // the chosen one
        final List<Student> members;   // sorted by name
        final int capacity;            // 0 for Unassigned
        final boolean full;

        Screen(List<Tab> tabs, String group, List<Student> members, int capacity, boolean full) {
            this.tabs = tabs;
            this.group = group;
            this.members = members;
            this.capacity = capacity;
            this.full = full;
        }
    }

    private final MutableLiveData<Screen> screen = new MutableLiveData<>();
    private final Runnable onDataChanged = this::publish;
    private String chosen;
    private boolean cleared;

    public GroupsViewModel() {
        StudentRepository.getInstance().addListener(onDataChanged);
    }

    LiveData<Screen> getScreen() { return screen; }

    /** The group to show first (from the dashboard row that was tapped), once. */
    void start(String group) {
        if (chosen == null) chosen = group;
        publish();
    }

    void choose(String group) {
        chosen = group;
        publish();
    }

    String chosen() { return chosen; }

    /** Members of one group, A-Z by name; Unassigned works too. */
    static List<Student> membersOf(List<Student> roster, String group) {
        List<Student> out = new ArrayList<>();
        for (Student s : roster) {
            String g = s.group == null ? StudentRepository.UNASSIGNED : s.group;
            if (g.equals(group)) out.add(s);
        }
        Collections.sort(out, Comparator.comparing((Student s) -> s.name == null ? "" : s.name.toLowerCase(Locale.ROOT))
                .thenComparing(s -> s.number == null ? "" : s.number));
        return out;
    }

    /** The chosen group if it still exists, else the first group (a group may have been closed). */
    static String pick(String wanted, String[] groups, boolean anyUnassigned) {
        if (wanted != null) {
            if (StudentRepository.UNASSIGNED.equals(wanted) && anyUnassigned) return wanted;
            for (String g : groups) if (g.equals(wanted)) return wanted;
        }
        if (groups.length > 0) return groups[0];
        return StudentRepository.UNASSIGNED;
    }

    private void publish() {
        if (cleared) return;
        StudentRepository repo = StudentRepository.getInstance();
        List<Student> roster = repo.getActive();
        List<Tab> tabs = new ArrayList<>();
        for (String g : StudentRepository.GROUPS) {
            int count = membersOf(roster, g).size();
            int capacity = Math.max(repo.limitOf(g), count);
            tabs.add(new Tab(g, count, capacity, count >= repo.limitOf(g)));
        }
        int unassigned = membersOf(roster, StudentRepository.UNASSIGNED).size();
        if (unassigned > 0) tabs.add(new Tab(StudentRepository.UNASSIGNED, unassigned, 0, false));

        chosen = pick(chosen, StudentRepository.GROUPS, unassigned > 0);
        List<Student> members = membersOf(roster, chosen);
        boolean isGroup = !StudentRepository.UNASSIGNED.equals(chosen);
        int limit = isGroup ? repo.limitOf(chosen) : 0;
        screen.setValue(new Screen(Collections.unmodifiableList(tabs), chosen, Collections.unmodifiableList(members),
                isGroup ? Math.max(limit, members.size()) : 0, isGroup && members.size() >= limit));
    }

    @Override
    protected void onCleared() {
        cleared = true;
        StudentRepository.getInstance().removeListener(onDataChanged);
    }
}
