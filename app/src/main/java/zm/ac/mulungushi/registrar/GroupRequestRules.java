package zm.ac.mulungushi.registrar;

import java.util.ArrayList;
import java.util.List;

/**
 * The rules for a student's group request, in one place (used by GroupViewModel;
 * plain Java, unit-tested in GroupRequestRulesTest).
 *
 * A student with no group asks for their first one: "Request for Group".
 * A student in a group asks to move: "Request for Group Change".
 * Both are the SAME request on the server (POST /group-requests, kind GROUP): an
 * unassigned student's request is stored with no "from" group. Which wording to
 * show comes from the student's group as the server last sent it
 * (GET /students/me), never from what was opened or tapped on this phone.
 *
 * The server checks everything again when the request arrives (one open request,
 * not your current group, a real group, 15 seats on approval).
 */
final class GroupRequestRules {

    private GroupRequestRules() {}

    enum Mode { FIRST_GROUP, GROUP_CHANGE }

    /** Why a request cannot be sent (checked against the latest record before sending). */
    enum Refusal { NONE, NO_CHOICE, OPEN_REQUEST, SAME_GROUP, NOT_A_GROUP }

    static boolean isAssigned(Student s) {
        return s != null && s.group != null && !StudentRepository.UNASSIGNED.equals(s.group);
    }

    static Mode modeFor(Student s) {
        return isAssigned(s) ? Mode.GROUP_CHANGE : Mode.FIRST_GROUP;
    }

    static boolean hasOpenRequest(Student s) {
        return s != null && s.pendingGroup != null;
    }

    /** The groups this student may ask for: real lab groups other than their own (never Unassigned). */
    static List<String> choices(Student s, String[] groups) {
        List<String> out = new ArrayList<>();
        if (groups == null) return out;
        for (String g : groups) {
            if (g == null || StudentRepository.UNASSIGNED.equals(g)) continue;
            if (s != null && g.equals(s.group)) continue;
            out.add(g);
        }
        return out;
    }

    /** Checked with the student's latest record just before the request is sent. */
    static Refusal check(Student latest, String choice, String[] groups) {
        if (choice == null) return Refusal.NO_CHOICE;
        if (hasOpenRequest(latest)) return Refusal.OPEN_REQUEST;
        if (latest != null && choice.equals(latest.group)) return Refusal.SAME_GROUP;
        if (!choices(latest, groups).contains(choice)) return Refusal.NOT_A_GROUP;
        return Refusal.NONE;
    }
}
