package zm.ac.mulungushi.registrar;

import java.util.ArrayList;
import java.util.List;

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
        students.add(chanda);
        students.add(new Student("s2", "Bwalya Phiri", "202301112", "IT", "G01", true));
        students.add(new Student("s3", "Mutinta Banda", "202301198", "DS", "G02", true));
        students.add(new Student("s4", "Natasha Zulu", "202301203", "CS", "G04", true));
        students.add(new Student("s5", "Kelvin Tembo", "202301256", "IT", "G02", true));
        students.add(new Student("s6", "Bornface Sikaonga", "202301310", "CS", "G03", true));
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

    public void requestGroupChange(String studentId, String toGroup) {
        Student s = findById(studentId);
        if (s != null) s.pendingGroup = toGroup;
    }

    public void cancelGroupChange(String studentId) {
        Student s = findById(studentId);
        if (s != null) s.pendingGroup = null;
    }

    public void requestNumberCorrection(String studentId, String toNumber) {
        Student s = findById(studentId);
        if (s != null) s.pendingNumber = toNumber;
    }

    public void cancelNumberCorrection(String studentId) {
        Student s = findById(studentId);
        if (s != null) s.pendingNumber = null;
    }

    /** Lecturer approves a pending group move. Fails if the target group filled up in the meantime. */
    public boolean approveGroupChange(String studentId) {
        Student s = findById(studentId);
        if (s == null || s.pendingGroup == null) return false;
        if (!UNASSIGNED.equals(s.pendingGroup) && groupCount(s.pendingGroup) >= CAPACITY) return false;
        s.group = s.pendingGroup;
        s.pendingGroup = null;
        return true;
    }

    public void declineGroupChange(String studentId) {
        cancelGroupChange(studentId);
    }

    /** Lecturer approves a pending number correction. Fails if another record already owns that number. */
    public boolean approveNumberCorrection(String studentId) {
        Student s = findById(studentId);
        if (s == null || s.pendingNumber == null) return false;
        if (findOwnerOfNumber(s.pendingNumber, s.id) != null) return false;
        s.number = s.pendingNumber;
        s.pendingNumber = null;
        return true;
    }

    public void declineNumberCorrection(String studentId) {
        cancelNumberCorrection(studentId);
    }
}
