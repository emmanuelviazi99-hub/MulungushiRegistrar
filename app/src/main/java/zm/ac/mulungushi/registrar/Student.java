package zm.ac.mulungushi.registrar;

/**
 * One student record. Mirrors the {id, name, number, programme, group, status}
 * objects in the prototype. "active" stands in for status === 'active';
 * a soft-deleted record keeps active = false instead of being removed,
 * so its number stays reserved.
 */
public class Student {

    public String id;
    public String name;
    public String number;
    public String programme; // CS, IT or DS
    public String group;     // G01..G04 or Unassigned
    public boolean active;
    /** Group this student has asked to move to, or null. Stands in for the prototype's groupRequest until the sync queue exists. */
    public String pendingGroup;
    /** Number correction this student has requested, or null. Stands in for the prototype's numberRequest. */
    public String pendingNumber;

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
        c.pendingGroup = pendingGroup;
        c.pendingNumber = pendingNumber;
        return c;
    }
}
