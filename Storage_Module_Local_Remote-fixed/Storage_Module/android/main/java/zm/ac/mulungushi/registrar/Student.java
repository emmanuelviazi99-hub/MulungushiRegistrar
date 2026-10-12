package zm.ac.mulungushi.registrar;

/**
 * One student record. Mirrors the {id, name, number, programme, group, status}
 * objects in the prototype, with the name kept as two separate fields
 * (firstName, lastName) everywhere: on screen, in local storage and on the server.
 * "active" stands in for status === 'active'; a soft-deleted record keeps
 * active = false instead of being removed, so its number stays reserved.
 *
 * The student number is always a String of exactly nine digits (see
 * StudentValidator.isValidStudentNumber), never a number, so leading zeros survive.
 */
public class Student {

    public String id;
    public String firstName;
    public String lastName;
    public String number;
    public String programme; // CS, IT or DS
    public String group;     // G01..G04 or Unassigned
    public boolean active;
    /** Email address given at registration (verified with the emailed claim code). Null for records added by a lecturer. */
    public String email;
    /** Epoch millis when this student completed registration by email claim code; 0 = not self-registered. */
    public long registeredAt;
    /** Group this student has asked to move to, or null. Stands in for the prototype's groupRequest until the sync queue exists. */
    public String pendingGroup;
    /** Number correction this student has requested, or null. Stands in for the prototype's numberRequest. */
    public String pendingNumber;
    /** When each open request was made, e.g. "4 Oct", and the optional reason for a number correction. */
    public String pendingGroupOn;
    public String pendingNumberOn;
    public String pendingNumberReason;
    /** Order the requests came in, so the lecturer handles oldest first. */
    public int pendingGroupSeq;
    public int pendingNumberSeq;
    /** Set when a lecturer answers a request: "approved" or "declined". Shown to the student on their next visit, then cleared. */
    public String noticeKind;
    /** "group" or "number" - which request the notice is about. */
    public String noticeSubject;
    /** Epoch millis of the last local or remote change. Used to pick the newer copy when syncing. 0 = never edited. */
    public long updatedAt;

    public Student(String id, String firstName, String lastName, String number, String programme, String group, boolean active) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.number = number;
        this.programme = programme;
        this.group = group;
        this.active = active;
    }

    /** "First Last", for display only. The two parts are stored separately. */
    public String fullName() {
        String f = firstName == null ? "" : firstName.trim();
        String l = lastName == null ? "" : lastName.trim();
        if (f.isEmpty()) return l;
        if (l.isEmpty()) return f;
        return f + " " + l;
    }

    /** True once the student has registered with a verified email claim code. */
    public boolean isRegistered() {
        return registeredAt > 0;
    }

    /** Shallow copy, used so an open edit sheet never mutates the stored record until Save. */
    public Student copy() {
        Student c = new Student(id, firstName, lastName, number, programme, group, active);
        c.copyFrom(this);
        return c;
    }

    /** Overwrites this record with another's values, keeping this same object so open screens stay valid. */
    public void copyFrom(Student o) {
        id = o.id; firstName = o.firstName; lastName = o.lastName; number = o.number;
        programme = o.programme; group = o.group; active = o.active;
        email = o.email; registeredAt = o.registeredAt;
        pendingGroup = o.pendingGroup; pendingNumber = o.pendingNumber;
        pendingGroupOn = o.pendingGroupOn; pendingNumberOn = o.pendingNumberOn; pendingNumberReason = o.pendingNumberReason;
        pendingGroupSeq = o.pendingGroupSeq; pendingNumberSeq = o.pendingNumberSeq;
        noticeKind = o.noticeKind; noticeSubject = o.noticeSubject;
        updatedAt = o.updatedAt;
    }
}
