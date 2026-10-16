package zm.ac.mulungushi.registrar;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.List;

/**
 * The lecturer's roster screen: search, filters, pages, and loading.
 *
 *   View        LecturerRosterActivity + activity_lecturer_roster.xml + RosterFilterSheet:
 *               shows getScreen(), forwards typing, filter picks, Clear and Show more here
 *   ViewModel   this class: holds the query (RosterQuery), the pages open and the
 *               loading state; survives rotation, so nothing is reloaded or lost
 *   Repository  StudentRepository.search() applies the query to the roster that
 *               RosterApi loaded (GET /students, lecturer-only on the server)
 *
 * Typing filters the roster already on the phone: no request per keystroke. The
 * roster itself is fetched when the screen opens, at most once per few seconds
 * (a rotation or a quick tab switch does not fetch again), and never twice at once.
 */
public class LecturerViewModel extends ViewModel {

    /** Students per page; "Show more" opens the next page (UI/UX team). */
    static final int PAGE_SIZE = 10;
    /** A load newer than this is fresh enough to skip on the next screen resume. */
    private static final long FRESH_MS = 5_000;

    enum Mode { LOADING, LIST, EXPIRED }

    enum Empty { NONE, NOT_LOADED, NO_STUDENTS, NO_MATCH }

    /** Everything the screen draws, in one value. */
    static final class Screen {
        final Mode mode;
        final List<Student> shown;   // the open pages
        final int matching;          // how many match in total
        final int total;             // students on the roster
        final RosterQuery query;
        final boolean savedCopyOnly; // offline: the list is what was saved on the phone
        final Empty empty;

        Screen(Mode mode, List<Student> shown, int matching, int total, RosterQuery query,
               boolean savedCopyOnly, Empty empty) {
            this.mode = mode;
            this.shown = shown;
            this.matching = matching;
            this.total = total;
            this.query = query;
            this.savedCopyOnly = savedCopyOnly;
            this.empty = empty;
        }
    }

    private final MutableLiveData<Screen> screen = new MutableLiveData<>();
    private final MutableLiveData<Event<RosterApi.Outcome>> loadFailed = new MutableLiveData<>();

    private RosterQuery query = RosterQuery.NONE;
    private int pages = 1;
    private boolean networkOffline;
    private boolean inFlight;
    private boolean answered;   // the first answer (server, or the saved copy) has arrived
    private long lastLoadAt;
    private boolean cleared;
    private final Runnable onDataChanged = this::publish;

    public LecturerViewModel() {
        // background uploads, pull-down and the 30 s refresh all land in StudentRepository
        StudentRepository.getInstance().addListener(onDataChanged);
        answered = StudentRepository.getInstance().isRosterLoaded();
    }

    LiveData<Screen> getScreen() { return screen; }

    /** "Couldn't refresh" once per failure; the screen shows it with RosterUi.refreshFailed. */
    LiveData<Event<RosterApi.Outcome>> getLoadFailed() { return loadFailed; }

    RosterQuery query() { return query; }

    int pages() { return pages; }

    /** After the app was closed by Android: put back what the screen had (only into a fresh ViewModel). */
    void restore(RosterQuery saved, int savedPages) {
        if (saved == null || !query.equals(RosterQuery.NONE) || pages != 1) return;
        query = saved;
        pages = Math.max(1, savedPages);
        publish();
    }

    // ---------------------------------------------------------------- what the lecturer does

    void setSearchText(String text) {
        if (text == null) text = "";
        if (text.equals(query.text)) return; // also fires when Android puts the text back after a rotation
        apply(query.withText(text));
    }

    void setProgramme(String programme) { apply(query.withProgramme(programme)); }

    void setGroup(String group) { apply(query.withGroup(group)); }

    /** "Clear filters": both filters back to All; the search text stays. */
    void clearFilters() { apply(query.withoutFilters()); }

    /** The x in the search box, and "Clear search and filters" when nothing matches. */
    void clearAll() { apply(RosterQuery.NONE); }

    void showMore() {
        pages++;
        publish();
    }

    void setNetworkOffline(boolean offline) {
        if (offline == networkOffline) return;
        networkOffline = offline;
        publish();
    }

    /** How many students a choice in the filter sheet would show, with the rest of the query kept. */
    int countWithProgramme(String programme) {
        return StudentRepository.getInstance().countMatching(query.withProgramme(programme));
    }

    /** How many students the current query shows (all pages). */
    int matchingCount() {
        return StudentRepository.getInstance().countMatching(query);
    }

    int countWithGroup(String group) {
        return StudentRepository.getInstance().countMatching(query.withGroup(group));
    }

    // ---------------------------------------------------------------- loading

    /**
     * Fetches the roster. Skipped while a fetch is running, and (unless force) when the
     * last one finished moments ago. Lecturer accounts only; the server refuses others too.
     */
    void refresh(boolean force) {
        if (!Session.isLecturer()) return;
        if (inFlight) return;
        long now = System.currentTimeMillis();
        if (!force && answered && now - lastLoadAt < FRESH_MS) {
            publish();
            return;
        }
        inFlight = true;
        publish();
        RosterApi.get().loadRoster(outcome -> {
            inFlight = false;
            if (cleared) return;
            lastLoadAt = System.currentTimeMillis();
            answered = true;
            if (outcome.is(RosterApi.Outcome.UNAUTHORIZED)) {
                SessionState.expire(); // the screen shows "Sign in again" in place of the list
            } else if (!outcome.ok) {
                loadFailed.setValue(new Event<>(outcome));
            }
            publish();
        });
    }

    // ---------------------------------------------------------------- state

    private void apply(RosterQuery next) {
        if (next.equals(query)) return;
        query = next;
        pages = 1; // a new search starts at the top
        publish();
    }

    private void publish() {
        if (cleared) return;
        StudentRepository repo = StudentRepository.getInstance();
        Mode mode = SessionState.isExpired() ? Mode.EXPIRED
                : (!answered && !repo.isRosterLoaded()) ? Mode.LOADING
                : Mode.LIST;
        int total = repo.getActive().size();
        List<Student> matching = repo.search(query);
        int limit = Math.min(matching.size(), pages * PAGE_SIZE);
        Empty empty = !matching.isEmpty() ? Empty.NONE
                : !repo.isRosterLoaded() ? Empty.NOT_LOADED
                : total == 0 ? Empty.NO_STUDENTS
                : Empty.NO_MATCH;
        screen.setValue(new Screen(mode, new ArrayList<>(matching.subList(0, limit)), matching.size(), total,
                query, networkOffline || repo.isOffline(), empty));
    }

    @Override
    protected void onCleared() {
        cleared = true;
        StudentRepository.getInstance().removeListener(onDataChanged);
    }
}
