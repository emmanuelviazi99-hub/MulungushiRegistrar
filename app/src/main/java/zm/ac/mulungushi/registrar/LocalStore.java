package zm.ac.mulungushi.registrar;

import java.util.List;

/**
 * What the app keeps on the phone, per signed-in account:
 *
 *   - the last answers from the server (the roster, the student's own record,
 *     requests, group totals), so screens show something straight away and
 *     when there is no network;
 *   - the outbox: changes made on this phone that are waiting to be uploaded
 *     (see PendingOp), or that the server refused.
 *
 * On the phone this is RoomLocalStore (a Room database). Tests and the
 * live check use MemoryLocalStore. Every method may do disk work, so callers
 * use it off the main thread.
 */
public interface LocalStore {

    // ---- saved server answers ----

    /** The roster as the server last sent it: one JSON object per student, in the server's order. */
    void replaceStudents(String accountId, List<String> studentJson, long savedAt);

    List<String> students(String accountId);

    /** Any other saved answer, by name (e.g. "groups", "pending", "me"). */
    void putEntry(String accountId, String name, String json, long savedAt);

    /** The saved answer, or null if there is none. */
    String entry(String accountId, String name);

    /** When that answer was saved (milliseconds), or 0 if there is none. */
    long entrySavedAt(String accountId, String name);

    // ---- the outbox ----

    /** Adds a change; gives it a seq after every other change of this account. */
    void addOp(PendingOp op);

    void updateOp(PendingOp op);

    void removeOp(String opId);

    /** This account's changes, oldest first. */
    List<PendingOp> ops(String accountId);

    // ---- sign-out ----

    /** Forgets everything saved for this account: answers and changes. */
    void clearAccount(String accountId);
}
