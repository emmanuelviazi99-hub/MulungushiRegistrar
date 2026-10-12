package zm.ac.mulungushi.registrar;

import java.util.List;

/**
 * Pure merge rule (no Android code, so it unit-tests on a laptop).
 *
 * Per student id, the copy with the newer updatedAt wins (an equal or older server copy never
 * overwrites local data). A student number belongs to one record only, so a server record
 * whose number is already used by a DIFFERENT local record never creates a duplicate:
 *  - if the local record has no unsynced changes (e.g. demo seed data), the server's record
 *    replaces it;
 *  - if the local record still has changes waiting to upload, the local record is kept and the
 *    server's record is skipped (the server will report the clash when the upload is tried).
 */
final class SyncMerge {
    private SyncMerge() {}

    /** Tells the merge whether a local record still has changes the server has not got. */
    interface PendingCheck {
        boolean isPending(Student local);
    }

    private static final PendingCheck NOTHING_PENDING = s -> false;

    static int apply(List<Student> local, List<Student> remote) {
        return apply(local, remote, NOTHING_PENDING);
    }

    /** Applies the server's records to the local list. Returns how many local records were added or changed. */
    static int apply(List<Student> local, List<Student> remote, PendingCheck pending) {
        int changed = 0;
        for (Student r : remote) {
            if (r == null || r.id == null || r.id.isEmpty()) continue;
            Student mine = findById(local, r.id);
            if (mine != null) {
                if (r.updatedAt > mine.updatedAt) {
                    mine.copyFrom(r);
                    changed++;
                }
                continue;
            }
            Student sameNumber = findByNumber(local, r.number, r.id);
            if (sameNumber == null) {
                local.add(r);
                changed++;
            } else if (!pending.isPending(sameNumber)) {
                sameNumber.copyFrom(r); // same student, server's record takes over (no duplicate)
                changed++;
            }
            // else: keep the unsynced local record; never hold two records with one number
        }
        return changed;
    }

    static Student findById(List<Student> list, String id) {
        for (Student s : list) if (s.id != null && s.id.equals(id)) return s;
        return null;
    }

    static Student findByNumber(List<Student> list, String number, String excludeId) {
        if (number == null || number.isEmpty()) return null;
        for (Student s : list) {
            if (number.equals(s.number) && (s.id == null || !s.id.equals(excludeId))) return s;
        }
        return null;
    }
}
