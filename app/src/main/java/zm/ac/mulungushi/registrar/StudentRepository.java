package zm.ac.mulungushi.registrar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the screens show: what the server last said (from the server, or from the
 * copy saved on the phone when the server can't be reached), with this phone's
 * waiting changes shown on top and marked (Student.unsynced). RosterApi hands
 * every answer to one of the apply...() methods below; nothing here invents data.
 *
 *   lecturer  the roster, group totals, open requests, answered history
 *   student   their own record, group totals, their latest requests, their group's members
 *   both      the outbox: changes waiting to upload, and changes the server refused
 *
 * Screens that are open can listen (addListener) to redraw when a background
 * upload brings new data. Used on the main thread only.
 */
public class StudentRepository {

    public static final int CAPACITY = 15;
    public static final String[] PROGRAMMES = {"CS", "IT", "DS"};
    /** The lab groups. Replaced by the server's list (GET /groups) once it has been loaded. */
    public static String[] GROUPS = {"G01", "G02", "G03", "G04"};
    public static final String UNASSIGNED = "Unassigned";

    private static StudentRepository instance;

    /** One request, as the server reported it. */
    public static class RequestRecord {
        public static final String GROUP = "group";
        public static final String NUMBER = "number";
        public static final String PENDING = "pending";
        public static final String APPROVED = "approved";
        public static final String DECLINED = "declined";

        public int requestId;
        public String studentId;
        public String studentName;
        public String studentNumber;
        public String kind;        // group | number
        public String from;        // group code / Unassigned, or the old number
        public String to;
        public String reason;      // may be null
        public String requestedOn; // e.g. "4 Oct"
        public String status;      // pending | approved | declined
        public long resolvedAtMillis;
    }

    // ---- what the server last said ----
    private final List<Student> roster = new ArrayList<>();          // lecturer
    private final Map<String, Integer> groupCounts = new HashMap<>(); // both
    private final List<RequestRecord> answered = new ArrayList<>();   // lecturer, newest first
    private Student me;                                               // student
    private final List<Student> myGroupMembers = new ArrayList<>();   // student
    private boolean rosterLoaded;

    // ---- this phone's changes (the outbox) ----
    private final List<PendingOp> waiting = new ArrayList<>();
    private final List<PendingOp> refused = new ArrayList<>();
    /** When the data on screen came from the server (ms), 0 if never. */
    private long dataSavedAt;
    /** The last attempt to reach the server failed: the screens show the saved copy. */
    private boolean offline;

    private final List<Runnable> listeners = new ArrayList<>();

    private StudentRepository() {}

    public static synchronized StudentRepository getInstance() {
        if (instance == null) instance = new StudentRepository();
        return instance;
    }

    /** Forget everything (sign-out), so the next person on this phone never sees it. */
    public static synchronized void reset() {
        List<Runnable> keep = instance == null ? new ArrayList<>() : instance.listeners;
        instance = new StudentRepository();
        instance.listeners.addAll(keep);
        GROUPS = new String[]{"G01", "G02", "G03", "G04"};
    }

    // =====================================================================
    // reads
    // =====================================================================

    public boolean isRosterLoaded() { return rosterLoaded; }

    /** The lecturer's roster (active students only; the server never lists deleted ones). */
    public List<Student> getActive() {
        return new ArrayList<>(roster);
    }

    /** Active members of a group: the server's total when known, else counted from the roster. */
    public int groupCount(String group) {
        Integer n = groupCounts.get(group);
        if (n != null) return n;
        int c = 0;
        for (Student s : roster) if (group.equals(s.group)) c++;
        return c;
    }

    /** Someone else on the roster who already has this number, or null (the server re-checks on Save). */
    public Student findOwnerOfNumber(String number, String excludeId) {
        for (Student s : roster) {
            if (s.number.equals(number) && !s.id.equals(excludeId)) return s;
        }
        return null;
    }

    public Student findById(String id) {
        if (id == null) return null;
        for (Student s : roster) if (id.equals(s.id)) return s;
        return null;
    }

    /**
     * The signed-in student: the server's latest answer, or (before that has
     * arrived) the record saved at sign-in. Null for a lecturer.
     */
    public Student me() {
        if (me == null) me = Session.toStudent();
        return me;
    }

    public List<Student> myGroupMembers() {
        return new ArrayList<>(myGroupMembers);
    }

    /** Students with an open request, for the lecturer. */
    public List<Student> getPendingRequests() {
        List<Student> out = new ArrayList<>();
        for (Student s : roster) if (s.pendingGroup != null || s.pendingNumber != null) out.add(s);
        return out;
    }

    public int pendingRequestCount() {
        int n = 0;
        for (Student s : roster) {
            if (s.pendingGroup != null) n++;
            if (s.pendingNumber != null) n++;
        }
        return n;
    }

    /** Answered requests, newest first. */
    public List<RequestRecord> getAnswered() {
        return new ArrayList<>(answered);
    }

    /** Changes made on this phone that are not on the server yet, oldest first. */
    public List<PendingOp> getWaiting() {
        return new ArrayList<>(waiting);
    }

    /** Changes the server refused, with its reason, until the person deals with them. */
    public List<PendingOp> getRefused() {
        return new ArrayList<>(refused);
    }

    /** Refused changes: shown as "Action required" on the dashboard and the Sync screen. */
    public int conflictCount() {
        return refused.size();
    }

    public long dataSavedAt() { return dataSavedAt; }

    public boolean isOffline() { return offline; }

    // =====================================================================
    // listeners: open screens redraw when new data arrives
    // =====================================================================

    public void addListener(Runnable r) {
        if (!listeners.contains(r)) listeners.add(r);
    }

    public void removeListener(Runnable r) {
        listeners.remove(r);
    }

    private void changed() {
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    // =====================================================================
    // apply the server's answers (called by RosterApi on the main thread)
    // =====================================================================

    void applyGroups(List<String> codes, Map<String, Integer> counts) {
        if (codes != null && !codes.isEmpty()) GROUPS = codes.toArray(new String[0]);
        groupCounts.clear();
        if (counts != null) groupCounts.putAll(counts);
    }

    /** Lecturer: the whole roster plus the open requests, with this phone's waiting changes on top. */
    void applyRoster(List<Student> students, List<RequestRecord> pending, List<PendingOp> ops) {
        roster.clear();
        roster.addAll(students);
        for (RequestRecord r : pending) {
            Student s = findById(r.studentId);
            if (s == null) continue;
            attachPending(s, r);
        }
        Overlay.applyToRoster(roster, groupCounts, ops);
        rosterLoaded = true;
    }

    void applyAnswered(List<RequestRecord> records) {
        answered.clear();
        answered.addAll(records);
        Collections.sort(answered, (a, b) -> Long.compare(b.resolvedAtMillis, a.resolvedAtMillis));
        changed();
    }

    /** The outbox and where the data on screen came from. Called last after every reload. */
    void applySyncState(List<PendingOp> ops, long savedAt, boolean offline) {
        waiting.clear();
        refused.clear();
        for (PendingOp op : ops) {
            if (op.isWaiting()) waiting.add(op);
            else if (op.isRefused()) refused.add(op);
        }
        dataSavedAt = savedAt;
        this.offline = offline;
        changed();
    }

    /**
     * Student: their record, their latest request of each kind and their group's
     * members. An answered request they have not been told about yet becomes the
     * notice the home screen shows once.
     */
    void applyMine(Student self, RequestRecord latestGroup, RequestRecord latestNumber, List<Student> members,
                   List<PendingOp> ops) {
        if (self == null) {
            // Nothing saved yet (first start offline): the record from sign-in, with waiting changes.
            Student fromSignIn = Session.toStudent();
            if (fromSignIn != null) {
                Overlay.applyToMe(fromSignIn, ops);
                me = fromSignIn;
            }
            return;
        }
        for (RequestRecord r : new RequestRecord[]{latestGroup, latestNumber}) {
            if (r == null) continue;
            if (RequestRecord.PENDING.equals(r.status)) {
                attachPending(self, r);
            } else if (self.noticeKind == null && Notices.isNew(self.id, r)) {
                self.noticeKind = r.status;    // approved | declined
                self.noticeSubject = r.kind;   // group | number
                self.noticeRequestId = r.requestId;
            }
        }
        Overlay.applyToMe(self, ops);
        me = self;
        myGroupMembers.clear();
        if (members != null) myGroupMembers.addAll(members);
    }

    private static void attachPending(Student s, RequestRecord r) {
        if (RequestRecord.GROUP.equals(r.kind)) {
            s.pendingGroup = r.to;
            s.pendingGroupOn = r.requestedOn;
            s.pendingGroupSeq = r.requestId;
            s.pendingGroupRequestId = r.requestId;
        } else {
            s.pendingNumber = r.to;
            s.pendingNumberOn = r.requestedOn;
            s.pendingNumberReason = r.reason;
            s.pendingNumberSeq = r.requestId;
            s.pendingNumberRequestId = r.requestId;
        }
    }
}
