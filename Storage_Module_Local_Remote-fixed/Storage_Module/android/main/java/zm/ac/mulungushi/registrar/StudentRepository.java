package zm.ac.mulungushi.registrar;

import android.content.Context;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The app's single source of student data. Everything is kept in memory for the screens AND
 * saved to the phone's local storage (LocalStorage) on every change, so it survives closing
 * and reopening the app and works offline. Every change also asks SyncManager to upload it to
 * the remote server in the background.
 *
 * Screens must change data only through the methods here, or it will not be saved or synced.
 * Everything runs on the main thread (the HTTP part of sync runs on a background thread and
 * hands its result back to the main thread).
 */
public class StudentRepository {

    public static final int CAPACITY = 15;
    public static final String[] PROGRAMMES = {"CS", "IT", "DS"};
    public static final String[] GROUPS = {"G01", "G02", "G03", "G04"};
    public static final String UNASSIGNED = "Unassigned";
    public static final String DEMO_NUMBER_FALLBACK = "202301045";

    private static StudentRepository instance;
    private static Context appContext;
    private final LocalStorage localStorage;
    /** id -> content signature as of the last save (in memory): tells which records changed so updatedAt can be bumped. */
    private final Map<String, String> persistedSig = new HashMap<>();
    /** id -> content signature the server is known to hold (saved on the phone): a record that differs is waiting to upload. */
    private final Map<String, String> syncedSig = new HashMap<>();

    public static synchronized void initialize(Context context) {
        appContext = context.getApplicationContext();
        if (instance == null) instance = new StudentRepository();
    }

    private final List<Student> students = new ArrayList<>();

    /** One answered request, kept so the lecturer's Requests screen can show an Answered history. */
    public static class RequestRecord {
        public static final String GROUP = "group";
        public static final String NUMBER = "number";
        public static final String APPROVED = "approved";
        public static final String DECLINED = "declined";

        public int seq;
        public String studentId;
        public String studentName;
        public String studentNumber;
        public String kind;        // group | number
        public String from;
        public String to;
        public String reason;      // number corrections only, may be null
        public String requestedOn;
        public String status;      // approved | declined
    }

    /** The one demo sync conflict the prototype shows (Mutinta Banda). Resolving it clears the lecturer's Conflicts count. */
    private boolean conflictResolved = false;
    public boolean isConflictResolved() { return conflictResolved; }
    public void resolveConflict() {
        conflictResolved = true;
        if (appContext != null) {
            appContext.getSharedPreferences(LocalStorage.PREFS, Context.MODE_PRIVATE).edit().putBoolean("conflict_resolved", true).apply();
        }
    }
    public int conflictCount() { return conflictResolved ? 0 : 1; }

    /** Newest last; the screen reverses it. Saved on the phone with the students. */
    private final List<RequestRecord> answered = new ArrayList<>();
    private int nextSeq = 10;

    private StudentRepository() {
        if (appContext == null) throw new IllegalStateException("RegistrarApplication did not initialize local storage");
        localStorage = new LocalStorage(appContext);
        conflictResolved = appContext.getSharedPreferences(LocalStorage.PREFS, Context.MODE_PRIVATE).getBoolean("conflict_resolved", false);
        if (localStorage.hasSavedStudents()) {
            students.addAll(localStorage.loadStudents());
            answered.addAll(localStorage.loadAnswered());
            syncedSig.putAll(localStorage.loadSynced());
            for (Student st : students) persistedSig.put(st.id, LocalStorage.signature(st));
            for (RequestRecord r : answered) nextSeq = Math.max(nextSeq, r.seq + 1);
            for (Student st : students) {
                nextSeq = Math.max(nextSeq, st.pendingGroupSeq + 1);
                nextSeq = Math.max(nextSeq, st.pendingNumberSeq + 1);
            }
        } else {
            seed();
            // Demo seed data counts as already synced with updatedAt 0, so it never overwrites real server data.
            for (Student st : students) {
                String g = LocalStorage.signature(st);
                persistedSig.put(st.id, g);
                syncedSig.put(st.id, g);
            }
            persistQuiet();
        }
    }

    public static synchronized StudentRepository getInstance() {
        if (instance == null) {
            if (appContext == null) throw new IllegalStateException("RegistrarApplication not initialized");
            instance = new StudentRepository();
        }
        return instance;
    }

    /** Saves locally (on disk before it returns), stamps changed records, then asks the sync manager to upload. */
    private void persist() {
        persistQuiet();
        SyncManager sm = SyncManager.peek();
        if (sm != null && hasDirty()) sm.requestSync();
    }

    /** Saves locally without triggering a sync (used by the sync itself). */
    private void persistQuiet() {
        long now = System.currentTimeMillis();
        for (Student s : students) {
            String sig = LocalStorage.signature(s);
            if (!sig.equals(persistedSig.get(s.id))) {
                s.updatedAt = Math.max(now, s.updatedAt + 1); // always newer than the copy it replaces
                persistedSig.put(s.id, sig);
            }
        }
        localStorage.saveAll(students, answered, syncedSig);
    }

    // ---- Remote sync support (called by SyncManager on the main thread) ----

    private boolean isDirty(Student s) {
        if (s.id == null || s.id.startsWith("demo-")) return false; // demo-only sign-in records are never uploaded
        return !LocalStorage.signature(s).equals(syncedSig.get(s.id));
    }

    /** Records changed on this phone since the server last matched. */
    private List<Student> dirtyStudents() {
        List<Student> out = new ArrayList<>();
        for (Student s : students) if (isDirty(s)) out.add(s);
        return out;
    }

    public boolean hasDirty() { return !dirtyStudents().isEmpty(); }
    public int pendingSyncCount() { return dirtyStudents().size(); }

    /** True while this student's latest change has not reached the server yet. */
    public boolean isPendingSync(String studentId) {
        Student s = findById(studentId);
        return s != null && isDirty(s);
    }

    public JSONArray dirtyAsJson() {
        JSONArray a = new JSONArray();
        try {
            for (Student s : dirtyStudents()) a.put(LocalStorage.toJson(s));
        } catch (Exception e) {
            throw new IllegalStateException("Could not prepare changes for upload", e);
        }
        return a;
    }

    /**
     * Merges the server's list into local data (newer updatedAt wins, one record per student
     * number), marks the records the server now holds as synced, and saves. Records the server
     * refused are not in its list, so they stay marked as waiting; nothing local is deleted.
     */
    public int applyRemote(JSONArray remote) throws Exception {
        List<Student> incoming = new ArrayList<>();
        for (int i = 0; i < remote.length(); i++) incoming.add(LocalStorage.fromJson(remote.getJSONObject(i)));
        int changed = SyncMerge.apply(students, incoming, this::isDirty);
        for (Student r : incoming) {
            Student cur = findById(r.id);
            if (cur == null) continue;
            String remoteSig = LocalStorage.signature(r);
            if (r.updatedAt == cur.updatedAt && !LocalStorage.signature(cur).equals(remoteSig)) {
                // The server holds our own upload, tidied (e.g. email in lower case): keep its copy.
                cur.copyFrom(r);
                changed++;
            }
            String cs = LocalStorage.signature(cur);
            persistedSig.put(cur.id, cs);
            if (cs.equals(remoteSig)) syncedSig.put(cur.id, cs);
        }
        forgetMissingIds();
        persistQuiet();
        return changed;
    }

    /** Drops sync bookkeeping for ids no longer on the phone (a record replaced by the server's copy). */
    private void forgetMissingIds() {
        Set<String> ids = new HashSet<>();
        for (Student s : students) ids.add(s.id);
        persistedSig.keySet().retainAll(ids);
        syncedSig.keySet().retainAll(ids);
    }

    private void seed() {
        Student chanda = new Student("s1", "Chanda", "Mwansa", "202301045", "CS", "G01", true);
        chanda.email = DemoAccounts.STUDENT_EMAIL; // so Forgot password works for the demo student
        chanda.pendingGroup = "G02"; // same starting state as the prototype: one open group-change request
        chanda.pendingGroupOn = "12 Sep";
        chanda.pendingGroupSeq = 4;
        students.add(chanda);
        students.add(new Student("s2", "Bwalya", "Phiri", "202301112", "IT", "G01", true));
        Student mutinta = new Student("s3", "Mutinta", "Banda", "202301198", "DS", "G02", true);
        mutinta.pendingNumber = "202301189";
        mutinta.pendingNumberOn = "11 Sep";
        mutinta.pendingNumberReason = "Two digits were swapped when I registered.";
        mutinta.pendingNumberSeq = 3;
        students.add(mutinta);
        students.add(new Student("s4", "Natasha", "Zulu", "202301203", "CS", "G04", true));
        Student kelvin = new Student("s5", "Kelvin", "Tembo", "202301256", "IT", "G02", true);
        kelvin.pendingGroup = "G04";
        kelvin.pendingGroupOn = "10 Sep";
        kelvin.pendingGroupSeq = 2;
        students.add(kelvin);
        students.add(new Student("s6", "Bornface", "Sikaonga", "202301310", "CS", "G03", true));

        // one request already answered, like the prototype's history
        RequestRecord done = new RequestRecord();
        done.seq = 1;
        done.studentId = "s4";
        done.studentName = "Natasha Zulu";
        done.studentNumber = "202301203";
        done.kind = RequestRecord.GROUP;
        done.from = UNASSIGNED;
        done.to = "G04";
        done.requestedOn = "2 Sep";
        done.status = RequestRecord.APPROVED;
        answered.add(done);
    }

    /** Active records only, the way the roster and group counts read them. */
    public List<Student> getActive() {
        List<Student> out = new ArrayList<>();
        for (Student s : students) if (s.active) out.add(s);
        return out;
    }

    public int groupCount(String group) {
        int n = 0;
        for (Student s : students) if (s.active && group.equals(s.group)) n++;
        return n;
    }

    /**
     * Numbers stay unique across removed records too (a soft-deleted number
     * is still reserved), matching the prototype's owner lookup in EditSheet.
     */
    public Student findOwnerOfNumber(String number, String excludeId) {
        for (Student s : students) {
            if (s.number != null && s.number.equals(number) && (excludeId == null || !s.id.equals(excludeId))) return s;
        }
        return null;
    }

    public Student findById(String id) {
        if (id == null) return null;
        for (Student s : students) if (id.equals(s.id)) return s;
        return null;
    }

    /** The active student with this number, or null. */
    public Student findActiveByNumber(String number) {
        for (Student s : students) {
            if (s.active && s.number != null && s.number.equals(number)) return s;
        }
        return null;
    }

    /** Looks a signed-in student up by number, or fabricates a demo record for a number not on the roster. */
    public Student findOrCreateDemoStudent(String number) {
        Student found = findActiveByNumber(number);
        if (found != null) return found;
        Student demo = new Student("demo-" + number, "Demo", "Student", number, "CS", UNASSIGNED, true);
        students.add(demo);
        persist();
        return demo;
    }

    /** The active student registered with this email, or null. Case does not matter. */
    public Student findByEmail(String email) {
        if (email == null) return null;
        String e = email.trim();
        for (Student s : students) {
            if (s.active && s.email != null && s.email.equalsIgnoreCase(e)) return s;
        }
        return null;
    }

    /** A lecturer adds a student on this phone. Saved at once and uploaded in the background. */
    public void add(Student s) {
        s.id = "s" + System.currentTimeMillis();
        normalise(s);
        students.add(s);
        persist();
    }

    /**
     * A student finished registration (the server accepted the emailed claim code). The record,
     * with the five registration details and registeredAt, is saved on the phone straight away
     * (on disk before this returns, whatever happens to the network afterwards), then the upload
     * to remote storage starts in the background. If the upload fails the record stays saved and
     * waiting, and SyncManager retries it.
     *
     * The id is the server's student id, so the phone and the server name the record the same
     * way. Any older local record with the same student number (demo data, an earlier copy) is
     * replaced, so the phone never holds two records for one number.
     */
    public Student addRegistered(Student registered) {
        normalise(registered);
        if (registered.registeredAt <= 0) registered.registeredAt = System.currentTimeMillis();
        Student same = findById(registered.id);
        Student oldOwner = findOwnerOfNumber(registered.number, registered.id);
        if (oldOwner != null) students.remove(oldOwner);
        if (same != null) {
            same.copyFrom(registered);
        } else {
            students.add(registered);
            same = registered;
        }
        persist();
        SyncManager sm = SyncManager.peek();
        if (sm != null) sm.syncNow();
        return same;
    }

    /**
     * Keeps the phone in step with the student record the server returned at sign-in. Only the
     * identity fields come from the server; local requests and notices are kept.
     */
    public Student rememberSignedIn(Student fromServer) {
        normalise(fromServer);
        Student mine = findById(fromServer.id);
        if (mine == null) {
            Student sameNumber = findOwnerOfNumber(fromServer.number, fromServer.id);
            if (sameNumber != null && !isDirty(sameNumber)) {
                students.remove(sameNumber);
            } else if (sameNumber != null) {
                return sameNumber; // unsynced local changes win; never two records for one number
            }
            students.add(fromServer);
            mine = fromServer;
        } else {
            mine.firstName = fromServer.firstName;
            mine.lastName = fromServer.lastName;
            mine.number = fromServer.number;
            mine.programme = fromServer.programme;
            mine.email = fromServer.email;
            mine.group = fromServer.group;
            if (fromServer.registeredAt > 0) mine.registeredAt = fromServer.registeredAt;
        }
        persist();
        return mine;
    }

    public void update(Student updated) {
        normalise(updated);
        for (int i = 0; i < students.size(); i++) {
            if (students.get(i).id.equals(updated.id)) {
                students.set(i, updated);
                persist();
                return;
            }
        }
    }

    /** Trims names and stores the email in lower case, the way the server stores them. */
    private static void normalise(Student s) {
        if (s.firstName != null) s.firstName = s.firstName.trim();
        if (s.lastName != null) s.lastName = s.lastName.trim();
        if (s.email != null) {
            String e = s.email.trim().toLowerCase(Locale.ROOT);
            s.email = e.isEmpty() ? null : e;
        }
    }

    /** Soft delete: hide the record, keep the number reserved (see the brief's data-integrity rules). */
    public void softDelete(String id) {
        Student s = findById(id);
        if (s != null) {
            s.active = false;
            persist();
        }
    }

    // ---- Group change and number correction requests ----
    // A student raises a request; only a lecturer can approve or decline it.
    // Nothing changes on the record itself until then, matching the brief:
    // "An offline request is Pending until confirmed."

    public List<Student> getPendingRequests() {
        List<Student> out = new ArrayList<>();
        for (Student s : getActive()) if (s.pendingGroup != null || s.pendingNumber != null) out.add(s);
        return out;
    }

    public int pendingRequestCount() {
        return getPendingRequests().size();
    }

    private static String today() {
        return new SimpleDateFormat("d MMM", Locale.ENGLISH).format(new Date());
    }

    /** Answered requests, newest first. */
    public List<RequestRecord> getAnswered() {
        List<RequestRecord> out = new ArrayList<>(answered);
        java.util.Collections.reverse(out);
        return out;
    }

    private void log(Student s, String kind, String from, String to, String reason, String on, String status) {
        RequestRecord r = new RequestRecord();
        r.seq = nextSeq++;
        r.studentId = s.id;
        r.studentName = s.fullName();
        r.studentNumber = s.number;
        r.kind = kind;
        r.from = from;
        r.to = to;
        r.reason = reason;
        r.requestedOn = on == null ? today() : on;
        r.status = status;
        answered.add(r);
        persist();
    }

    public void requestGroupChange(String studentId, String toGroup) {
        Student s = findById(studentId);
        if (s != null) {
            s.pendingGroup = toGroup;
            s.pendingGroupOn = today();
            s.pendingGroupSeq = nextSeq++;
            persist();
        }
    }

    public void cancelGroupChange(String studentId) {
        Student s = findById(studentId);
        if (s != null) {
            s.pendingGroup = null;
            s.pendingGroupOn = null;
            persist();
        }
    }

    public void requestNumberCorrection(String studentId, String toNumber) {
        requestNumberCorrection(studentId, toNumber, null);
    }

    public void requestNumberCorrection(String studentId, String toNumber, String reason) {
        Student s = findById(studentId);
        if (s != null) {
            s.pendingNumber = toNumber;
            s.pendingNumberOn = today();
            s.pendingNumberSeq = nextSeq++;
            s.pendingNumberReason = (reason == null || reason.trim().isEmpty()) ? null : reason.trim();
            persist();
        }
    }

    /** The lecturer saved the corrected number from the roster sheet: log it as approved and clear the request. */
    public void resolveNumberRequest(String studentId, String fromNumber) {
        Student s = findById(studentId);
        if (s == null || s.pendingNumber == null) return;
        log(s, RequestRecord.NUMBER, fromNumber, s.pendingNumber, s.pendingNumberReason, s.pendingNumberOn, RequestRecord.APPROVED);
        s.pendingNumber = null;
        s.pendingNumberOn = null;
        s.pendingNumberReason = null;
        s.noticeKind = "approved";
        s.noticeSubject = "number";
        persist();
    }

    public void cancelNumberCorrection(String studentId) {
        Student s = findById(studentId);
        if (s != null) {
            s.pendingNumber = null;
            s.pendingNumberOn = null;
            s.pendingNumberReason = null;
            persist();
        }
    }

    /** Lecturer approves a pending group move. Fails if the target group filled up in the meantime. */
    public boolean approveGroupChange(String studentId) {
        Student s = findById(studentId);
        if (s == null || s.pendingGroup == null) return false;
        if (!UNASSIGNED.equals(s.pendingGroup) && groupCount(s.pendingGroup) >= CAPACITY) return false;
        log(s, RequestRecord.GROUP, s.group, s.pendingGroup, null, s.pendingGroupOn, RequestRecord.APPROVED);
        s.group = s.pendingGroup;
        s.pendingGroup = null;
        s.pendingGroupOn = null;
        s.noticeKind = "approved";
        s.noticeSubject = "group";
        persist();
        return true;
    }

    public void declineGroupChange(String studentId) {
        Student s = findById(studentId);
        if (s != null && s.pendingGroup != null) {
            log(s, RequestRecord.GROUP, s.group, s.pendingGroup, null, s.pendingGroupOn, RequestRecord.DECLINED);
        }
        cancelGroupChange(studentId);
        if (s != null) {
            s.noticeKind = "declined";
            s.noticeSubject = "group";
        }
        persist();
    }

    /** Lecturer approves a pending number correction. Fails if another record already owns that number. */
    public boolean approveNumberCorrection(String studentId) {
        Student s = findById(studentId);
        if (s == null || s.pendingNumber == null) return false;
        if (findOwnerOfNumber(s.pendingNumber, s.id) != null) return false;
        log(s, RequestRecord.NUMBER, s.number, s.pendingNumber, s.pendingNumberReason, s.pendingNumberOn, RequestRecord.APPROVED);
        s.number = s.pendingNumber;
        s.pendingNumber = null;
        s.pendingNumberOn = null;
        s.pendingNumberReason = null;
        s.noticeKind = "approved";
        s.noticeSubject = "number";
        persist();
        return true;
    }

    /** The student has seen the approved/declined notice: clear it and save, so it does not fire again after a restart. */
    public void clearNotice(String studentId) {
        Student s = findById(studentId);
        if (s != null) {
            s.noticeKind = null;
            s.noticeSubject = null;
            persist();
        }
    }

    public void declineNumberCorrection(String studentId) {
        Student s = findById(studentId);
        if (s != null && s.pendingNumber != null) {
            log(s, RequestRecord.NUMBER, s.number, s.pendingNumber, s.pendingNumberReason, s.pendingNumberOn, RequestRecord.DECLINED);
        }
        cancelNumberCorrection(studentId);
        if (s != null) {
            s.noticeKind = "declined";
            s.noticeSubject = "number";
        }
        persist();
    }
}
