package zm.ac.mulungushi.registrar;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A student's group and group request, for the home card and the request screen.
 *
 *   View        StudentHomeActivity (the card's "Request for Group" / "Request for
 *               Group Change" label) and GroupChangeActivity (the picker): they draw
 *               getScreen() and pass on choose / submit / cancel
 *   ViewModel   this class: the assignment state, the choice, one action at a time
 *   Repository  StudentRepository.me() (the record from GET /students/me) and
 *               RosterApi (POST /group-requests, DELETE /my-requests/group, outbox)
 *
 * The wording follows the student's group as stored on the server, and updates by
 * itself when it changes (a lecturer approved, the 30 s refresh, a background upload):
 * StudentRepository tells this ViewModel, which publishes a new Screen.
 */
public class GroupViewModel extends ViewModel {

    static final class Option {
        final String group;
        final int count;
        final boolean full;

        Option(String group, int count, boolean full) {
            this.group = group;
            this.count = count;
            this.full = full;
        }
    }

    static final class Screen {
        final Student me;
        final GroupRequestRules.Mode mode;
        final boolean assigned;
        final boolean hasOpenRequest;
        final List<Option> options;
        final String choice;
        final boolean busy;

        Screen(Student me, GroupRequestRules.Mode mode, boolean assigned, boolean hasOpenRequest,
               List<Option> options, String choice, boolean busy) {
            this.me = me;
            this.mode = mode;
            this.assigned = assigned;
            this.hasOpenRequest = hasOpenRequest;
            this.options = options;
            this.choice = choice;
            this.busy = busy;
        }
    }

    /** What happened after submit or cancel; shown once (Event). */
    static final class Result {
        enum Action { SENT, CANCELLED, NOT_SENT }

        final Action action;
        final RosterApi.Outcome outcome;           // SENT / CANCELLED: the server's answer
        final GroupRequestRules.Refusal refusal;   // NOT_SENT: why nothing was sent

        Result(Action action, RosterApi.Outcome outcome, GroupRequestRules.Refusal refusal) {
            this.action = action;
            this.outcome = outcome;
            this.refusal = refusal;
        }
    }

    private final MutableLiveData<Screen> screen = new MutableLiveData<>();
    private final MutableLiveData<Event<Result>> results = new MutableLiveData<>();
    private final MutableLiveData<Event<RosterApi.Outcome>> loadFailed = new MutableLiveData<>();

    private String choice;
    private boolean busy;
    private boolean loading;
    private boolean cleared;
    private final Runnable onDataChanged = this::publish;

    public GroupViewModel() {
        StudentRepository.getInstance().addListener(onDataChanged);
        publish();
    }

    LiveData<Screen> getScreen() { return screen; }

    LiveData<Event<Result>> getResults() { return results; }

    LiveData<Event<RosterApi.Outcome>> getLoadFailed() { return loadFailed; }

    /** The server's latest record, open request and group totals (GET /students/me, /groups). */
    void load() {
        if (loading || busy) return;
        loading = true;
        RosterApi.get().loadMine(outcome -> {
            loading = false;
            if (cleared) return;
            if (!outcome.ok) loadFailed.setValue(new Event<>(outcome));
            publish();
        });
    }

    void choose(String group) {
        if (busy) return;
        for (Option o : options(StudentRepository.getInstance().me())) {
            if (o.group.equals(group) && !o.full) {
                choice = group;
                publish();
                return;
            }
        }
    }

    /**
     * Sends the request. First reloads the student's record so the check uses the
     * latest group and open request; then the right request goes through the outbox
     * (sent now, or saved on the phone until online). One at a time.
     */
    void submit() {
        if (busy || choice == null) return;
        busy = true;
        publish();
        final String asked = choice;
        RosterApi.get().loadMine(fresh -> {
            if (cleared) return;
            // Offline, the check uses the copy on the phone; the server checks again on arrival.
            Student latest = StudentRepository.getInstance().me();
            GroupRequestRules.Refusal refusal = GroupRequestRules.check(latest, asked, StudentRepository.GROUPS);
            if (refusal != GroupRequestRules.Refusal.NONE) {
                busy = false;
                choice = null;
                results.setValue(new Event<>(new Result(Result.Action.NOT_SENT, fresh, refusal)));
                publish();
                return;
            }
            RosterApi.get().requestGroupChange(asked, outcome -> {
                busy = false;
                if (cleared) return;
                if (outcome.ok) choice = null;
                results.setValue(new Event<>(new Result(Result.Action.SENT, outcome, null)));
                publish();
            });
        });
    }

    /** Withdraws the open request. */
    void cancel() {
        if (busy) return;
        busy = true;
        publish();
        RosterApi.get().cancelGroupChange(outcome -> {
            busy = false;
            if (cleared) return;
            results.setValue(new Event<>(new Result(Result.Action.CANCELLED, outcome, null)));
            publish();
        });
    }

    // ---------------------------------------------------------------- state

    private List<Option> options(Student me) {
        StudentRepository repo = StudentRepository.getInstance();
        List<Option> out = new ArrayList<>();
        for (String g : GroupRequestRules.choices(me, StudentRepository.GROUPS)) {
            int count = repo.groupCount(g);
            out.add(new Option(g, count, count >= StudentRepository.CAPACITY));
        }
        return out;
    }

    private void publish() {
        if (cleared) return;
        Student me = StudentRepository.getInstance().me();
        if (me == null) return; // signed out: the screen sends the person to sign in
        List<Option> options = options(me);
        // a choice that is no longer possible (the group filled up, or became the student's own) is dropped
        boolean stillThere = false;
        for (Option o : options) if (o.group.equals(choice) && !o.full) stillThere = true;
        if (!stillThere && !busy) choice = null;
        screen.setValue(new Screen(me, GroupRequestRules.modeFor(me), GroupRequestRules.isAssigned(me),
                GroupRequestRules.hasOpenRequest(me), Collections.unmodifiableList(options), choice, busy));
    }

    @Override
    protected void onCleared() {
        cleared = true;
        StudentRepository.getInstance().removeListener(onDataChanged);
    }
}
