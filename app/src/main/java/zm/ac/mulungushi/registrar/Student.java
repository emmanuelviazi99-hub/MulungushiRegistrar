package zm.ac.mulungushi.registrar;

/**
 * One student record, as the server reported it (see RosterApi.studentFrom).
 * Group "Unassigned" stands for the server's groupCode null.
 */
public class Student {

    public String id;        // the server's studentId (UUID)
    public String name;
    public String number;
    public String programme; // CS, IT or DS
    public String group;     // G01..G04 or Unassigned
    public boolean active;
    /** The server's version of this record; sent back on Save so a newer change is never overwritten. */
    public int version;
    public String email;
    public String phone; // +260XXXXXXXXX
    public String nrc;   // 123456/10/1 (lists carry only a masked one)
    public String dateOfBirth;
    /** Open group-change request: the group asked for, or null. */
    public String pendingGroup;
    /** Open number-correction request: the number asked for, or null. */
    public String pendingNumber;
    /** When each open request was made, e.g. "4 Oct", and the optional reason for a number correction. */
    public String pendingGroupOn;
    public String pendingNumberOn;
    public String pendingNumberReason;
    /** Order the requests came in, so the lecturer handles oldest first. */
    public int pendingGroupSeq;
    public int pendingNumberSeq;
    /** The server's id of each open request, for approve / decline. */
    public int pendingGroupRequestId;
    public int pendingNumberRequestId;
    /** Set when a lecturer has answered a request the student has not been told about: "approved" or "declined". */
    public String noticeKind;
    /** "group" or "number" - which request the notice is about. */
    public String noticeSubject;
    public int noticeRequestId;
    /** Changed on this phone and not on the server yet (waiting to upload). Shown with a label. */
    public boolean unsynced;

    public Student(String id, String name, String number, String programme, String group, boolean active) {
        this.id = id;
        this.name = name;
        this.number = number;
        this.programme = programme;
        this.group = group;
        this.active = active;
    }

    /** Shallow copy, used so an open edit sheet never mutates the stored record until Save. */
    public Student copy() {
        Student c = new Student(id, name, number, programme, group, active);
        c.version = version;
        c.email = email;
        c.phone = phone;
        c.nrc = nrc;
        c.dateOfBirth = dateOfBirth;
        c.pendingGroup = pendingGroup;
        c.pendingNumber = pendingNumber;
        c.pendingGroupOn = pendingGroupOn;
        c.pendingNumberOn = pendingNumberOn;
        c.pendingNumberReason = pendingNumberReason;
        c.pendingGroupSeq = pendingGroupSeq;
        c.pendingNumberSeq = pendingNumberSeq;
        c.pendingGroupRequestId = pendingGroupRequestId;
        c.pendingNumberRequestId = pendingNumberRequestId;
        c.noticeKind = noticeKind;
        c.noticeSubject = noticeSubject;
        c.noticeRequestId = noticeRequestId;
        c.unsynced = unsynced;
        return c;
    }
}
