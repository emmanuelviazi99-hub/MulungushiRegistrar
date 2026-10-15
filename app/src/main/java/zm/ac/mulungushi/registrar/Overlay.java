package zm.ac.mulungushi.registrar;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shows changes that are still waiting to upload on top of what the server last
 * said, so the person sees their own change straight away (offline too), marked
 * "waiting to upload" (Student.unsynced). Nothing here is sent anywhere or saved
 * as server data: when the server has accepted the change, the next reload
 * brings the real record and the mark goes away. A refused change is not shown.
 * Plain Java, tested without a phone.
 */
final class Overlay {

    private Overlay() {}

    /** Lecturer: the roster and group totals, with this lecturer's waiting changes applied. */
    static void applyToRoster(List<Student> roster, Map<String, Integer> counts, List<PendingOp> ops) {
        for (PendingOp op : ops) {
            if (!op.isWaiting()) continue;
            JSONObject b = op.bodyJson();
            switch (op.type) {
                case PendingOp.ADD_STUDENT: {
                    String group = groupOf(b.optString("groupCode", null));
                    Student s = new Student(op.targetId, b.optString("fullName"), b.optString("studentNumber"),
                            b.optString("programmeCode", null), group, true);
                    s.nrc = b.optString("nrc", null);
                    s.dateOfBirth = b.optString("dateOfBirth", null);
                    s.unsynced = true;
                    roster.add(s);
                    move(counts, null, group);
                    break;
                }
                case PendingOp.SAVE_STUDENT: {
                    Student s = find(roster, op.targetId);
                    if (s == null) break;
                    if (b.has("fullName")) s.name = b.optString("fullName");
                    if (b.has("programmeCode")) s.programme = b.optString("programmeCode");
                    if (b.has("studentNumber")) s.number = b.optString("studentNumber");
                    if (b.has("groupCode")) {
                        String group = groupOf(b.optString("groupCode"));
                        move(counts, s.group, group);
                        s.group = group;
                        // Doing what the student asked for answers their request too (as on the server).
                        if (group.equals(s.pendingGroup)) clearGroupRequest(s);
                    }
                    if (s.pendingNumber != null && s.pendingNumber.equals(s.number)) clearNumberRequest(s);
                    s.unsynced = true;
                    break;
                }
                case PendingOp.DELETE_STUDENT: {
                    Iterator<Student> it = roster.iterator();
                    while (it.hasNext()) {
                        Student s = it.next();
                        if (s.id != null && s.id.equals(op.targetId)) {
                            move(counts, s.group, null);
                            it.remove();
                        }
                    }
                    break;
                }
                case PendingOp.APPROVE:
                case PendingOp.DECLINE: {
                    Student s = find(roster, b.optString("studentId", null));
                    if (s == null) break;
                    boolean approve = PendingOp.APPROVE.equals(op.type);
                    if (StudentRepository.RequestRecord.GROUP.equals(op.kind)) {
                        if (approve && s.pendingGroup != null) {
                            move(counts, s.group, s.pendingGroup);
                            s.group = s.pendingGroup;
                        }
                        clearGroupRequest(s);
                    } else {
                        if (approve && s.pendingNumber != null) s.number = s.pendingNumber;
                        clearNumberRequest(s);
                    }
                    s.unsynced = true;
                    break;
                }
                default:
                    break; // student changes do not touch the lecturer's roster
            }
        }
    }

    /** Student: their own record, with their waiting changes applied. */
    static void applyToMe(Student me, List<PendingOp> ops) {
        if (me == null) return;
        for (PendingOp op : ops) {
            if (!op.isWaiting()) continue;
            JSONObject b = op.bodyJson();
            switch (op.type) {
                case PendingOp.UPDATE_ME:
                    if (b.has("fullName")) me.name = b.optString("fullName");
                    if (b.has("programmeCode")) me.programme = b.optString("programmeCode");
                    break;
                case PendingOp.REQUEST_GROUP:
                    me.pendingGroup = groupOf(b.optString("groupCode"));
                    me.pendingGroupOn = shortDate(op.createdAt);
                    me.pendingGroupRequestId = 0;
                    break;
                case PendingOp.REQUEST_NUMBER:
                    me.pendingNumber = b.optString("newNumber");
                    me.pendingNumberReason = b.optString("reason", null);
                    me.pendingNumberOn = shortDate(op.createdAt);
                    me.pendingNumberRequestId = 0;
                    break;
                case PendingOp.CANCEL_GROUP:
                    clearGroupRequest(me);
                    break;
                case PendingOp.CANCEL_NUMBER:
                    clearNumberRequest(me);
                    break;
                default:
                    continue; // lecturer changes do not touch a student's own record
            }
            me.unsynced = true;
        }
    }

    // ---------------------------------------------------------------------

    /** "UNASSIGNED", "" or null on the wire -> Unassigned on the screens. */
    static String groupOf(String code) {
        if (code == null || code.isEmpty() || "UNASSIGNED".equalsIgnoreCase(code)
                || StudentRepository.UNASSIGNED.equalsIgnoreCase(code)) {
            return StudentRepository.UNASSIGNED;
        }
        return code;
    }

    private static Student find(List<Student> roster, String id) {
        if (id == null) return null;
        for (Student s : roster) if (id.equals(s.id)) return s;
        return null;
    }

    private static void move(Map<String, Integer> counts, String from, String to) {
        if (from != null && !StudentRepository.UNASSIGNED.equals(from) && counts.containsKey(from)) {
            counts.put(from, Math.max(0, counts.get(from) - 1));
        }
        if (to != null && !StudentRepository.UNASSIGNED.equals(to)) {
            Integer n = counts.get(to);
            counts.put(to, (n == null ? 0 : n) + 1);
        }
    }

    private static void clearGroupRequest(Student s) {
        s.pendingGroup = null;
        s.pendingGroupOn = null;
        s.pendingGroupSeq = 0;
        s.pendingGroupRequestId = 0;
    }

    private static void clearNumberRequest(Student s) {
        s.pendingNumber = null;
        s.pendingNumberOn = null;
        s.pendingNumberReason = null;
        s.pendingNumberSeq = 0;
        s.pendingNumberRequestId = 0;
    }

    static String shortDate(long millis) {
        return new SimpleDateFormat("d MMM", Locale.ENGLISH).format(new Date(millis));
    }
}
