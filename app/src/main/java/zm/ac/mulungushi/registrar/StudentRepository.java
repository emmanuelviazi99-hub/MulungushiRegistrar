package zm.ac.mulungushi.registrar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the screens show: a copy of what the server last said. The server is the
 * only place anything is changed (see RosterApi); after every change RosterApi
 * reloads and hands the fresh answer to one of the apply...() methods below.
 * Nothing here invents or edits data on its own, so the phone can never show a
 * group, a request or a student that the server does not have.
 *
 *   lecturer  the roster, group totals, open requests, answered history
 *   student   their own record, group totals, their latest requests, their group's members
 *
 * Used on the main thread only (RosterApi applies answers on the main thread).
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

    /** The one demo sync conflict on the lecturer's Sync screen (the prototype's showcase, not server data). */
    private boolean conflictResolved = false;
    public boolean isConflictResolved() { return conflictResolved; }
    public void resolveConflict() { conflictResolved = true; }
    public int conflictCount() { return conflictResolved ? 0 : 1; }

    private StudentRepository() {}

    public static synchronized StudentRepository getInstance() {
        if (instance == null) instance = new StudentRepository();
        return instance;
    }

    /** Forget everything (sign-out), so the next person on this phone never sees it. */
    public static synchronized void reset() {
        instance = new StudentRepository();
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

    // =====================================================================
    // apply the server's answers (called by RosterApi on the main thread)
    // =====================================================================

    void applyGroups(List<String> codes, Map<String, Integer> counts) {
        if (codes != null && !codes.isEmpty()) GROUPS = codes.toArray(new String[0]);
        groupCounts.clear();
        if (counts != null) groupCounts.putAll(counts);
    }

    /** Lecturer: the whole roster plus the open requests, from one reload. */
    void applyRoster(List<Student> students, List<RequestRecord> pending) {
        roster.clear();
        roster.addAll(students);
        for (RequestRecord r : pending) {
            Student s = findById(r.studentId);
            if (s == null) continue;
            attachPending(s, r);
        }
        rosterLoaded = true;
    }

    void applyAnswered(List<RequestRecord> records) {
        answered.clear();
        answered.addAll(records);
        Collections.sort(answered, (a, b) -> Long.compare(b.resolvedAtMillis, a.resolvedAtMillis));
    }

    /**
     * Student: their record, their latest request of each kind and their group's
     * members. An answered request they have not been told about yet becomes the
     * notice the home screen shows once.
     */
    void applyMine(Student self, RequestRecord latestGroup, RequestRecord latestNumber, List<Student> members) {
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
