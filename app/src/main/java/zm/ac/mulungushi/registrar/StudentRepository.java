package zm.ac.mulungushi.registrar;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Holds the student list in memory for this stage of the build.
 * This is a stand-in for Room + Retrofit, which come later in the brief
 * (see ICT361_Handoff, section 8). Seed data is copied from the prototype's
 * initialStudents so the roster looks the same on first run.
 *
 * Not thread-safe on purpose: everything runs on the main thread for now.
 * When Room replaces this, reads/writes move to a background thread.
 */
public class StudentRepository {

    public static final int CAPACITY = 15;
    public static final String[] PROGRAMMES = {"CS", "IT", "DS"};
    public static final String[] GROUPS = {"G01", "G02", "G03", "G04"};
    public static final String UNASSIGNED = "Unassigned";
    public static final String DEMO_NUMBER_FALLBACK = "202301045";

    private static StudentRepository instance;

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
    public void resolveConflict() { conflictResolved = true; }
    public int conflictCount() { return conflictResolved ? 0 : 1; }

    /** Newest last; the screen reverses it. In memory only, like the rest of this stage. */
    private final List<RequestRecord> answered = new ArrayList<>();
    private int nextSeq = 10;

    private StudentRepository() {
        seed();
    }

    public static synchronized StudentRepository getInstance() {
        if (instance == null) instance = new StudentRepository();
        return instance;
    }

    private void seed() {
        Student chanda = new Student("s1", "Chanda Mwansa", "202301045", "CS", "G01", true);
        chanda.pendingGroup = "G02"; // same starting state as the prototype: one open group-change request
        chanda.pendingGroupOn = "12 Sep";
        chanda.pendingGroupSeq = 4;
        students.add(chanda);
        students.add(new Student("s2", "Bwalya Phiri", "202301112", "IT", "G01", true));
        Student mutinta = new Student("s3", "Mutinta Banda", "202301198", "DS", "G02", true);
        mutinta.pendingNumber = "202301189";
        mutinta.pendingNumberOn = "11 Sep";
        mutinta.pendingNumberReason = "Two digits were swapped when I registered.";
        mutinta.pendingNumberSeq = 3;
        students.add(mutinta);
        students.add(new Student("s4", "Natasha Zulu", "202301203", "CS", "G04", true));
        Student kelvin = new Student("s5", "Kelvin Tembo", "202301256", "IT", "G02", true);
        kelvin.pendingGroup = "G04";
        kelvin.pendingGroupOn = "10 Sep";
        kelvin.pendingGroupSeq = 2;
        students.add(kelvin);
        students.add(new Student("s6", "Bornface Sikaonga", "202301310", "CS", "G03", true));

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
            if (s.number.equals(number) && !s.id.equals(excludeId)) return s;
        }
        return null;
    }

    public Student findById(String id) {
        for (Student s : students) if (s.id.equals(id)) return s;
        return null;
    }

    /** Looks a signed-in student up by number, or fabricates a demo record for a number not on the roster. */
    public Student findOrCreateDemoStudent(String number) {
        for (Student s : students) {
            if (s.active && s.number.equals(number)) return s;
        }
        Student demo = new Student("demo-" + number, "Demo Student", number, "CS", UNASSIGNED, true);
        students.add(demo);
        return demo;
    }

    public void add(Student s) {
        s.id = "s" + System.currentTimeMillis();
        students.add(s);
    }

    public void update(Student updated) {
        for (int i = 0; i < students.size(); i++) {
            if (students.get(i).id.equals(updated.id)) {
                students.set(i, updated);
                return;
            }
        }
    }

    /** Soft delete: hide the record, keep the number reserved (see the brief's data-integrity rules). */
    public void softDelete(String id) {
        Student s = findById(id);
        if (s != null) s.active = false;
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
        r.studentName = s.name;
        r.studentNumber = s.number;
        r.kind = kind;
        r.from = from;
        r.to = to;
        r.reason = reason;
        r.requestedOn = on == null ? today() : on;
        r.status = status;
        answered.add(r);
    }

    public void requestGroupChange(String studentId, String toGroup) {
        Student s = findById(studentId);
        if (s != null) {
            s.pendingGroup = toGroup;
            s.pendingGroupOn = today();
            s.pendingGroupSeq = nextSeq++;
        }
    }

    public void cancelGroupChange(String studentId) {
        Student s = findById(studentId);
        if (s != null) {
            s.pendingGroup = null;
            s.pendingGroupOn = null;
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
    }

    public void cancelNumberCorrection(String studentId) {
        Student s = findById(studentId);
        if (s != null) {
            s.pendingNumber = null;
            s.pendingNumberOn = null;
            s.pendingNumberReason = null;
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
        return true;
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
    }
}
