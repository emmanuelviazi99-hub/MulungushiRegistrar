package zm.ac.mulungushi.registrar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The server's answer to POST /students/import: a preview (applied false) or what
 * was saved (applied true). Plain Java, so the wording of the preview is unit-tested.
 *
 * The server decides everything (which rows are students, which numbers are already
 * on the roster, which groups must grow to keep their list whole); this class only
 * reads the answer and puts it into sentences for the confirm dialog.
 */
final class ClassListImport {

    static final class Group {
        final String code;
        final boolean exists;
        final int membersNow;
        final int adding;
        final int willHave;
        final int capacity;           // as the dashboard will show it: 15, or 16 for a list of 16
        final boolean overUsualSize;  // more members than the limit: kept together, the group shows as full
        final boolean reopened;

        Group(String code, boolean exists, int membersNow, int adding, int willHave, int capacity,
              boolean overUsualSize, boolean reopened) {
            this.code = code;
            this.exists = exists;
            this.membersNow = membersNow;
            this.adding = adding;
            this.willHave = willHave;
            this.capacity = capacity;
            this.overUsualSize = overUsualSize;
            this.reopened = reopened;
        }
    }

    static final class Problem {
        final String row;
        final String kind;    // BAD_STUDENT_NUMBER, BAD_NAME, UNKNOWN_PROGRAMME, DUPLICATE_IN_FILE
        final String message;

        Problem(String row, String kind, String message) {
            this.row = row;
            this.kind = kind;
            this.message = message;
        }
    }

    final boolean applied;
    final int studentsInFile;
    final int toAdd;
    final int added;
    final int alreadyOnRoster;
    final int unassigned;
    final int blankRowsSkipped;
    final int usualGroupSize;
    final List<Group> groups;
    final Map<String, Integer> programmes;
    final List<Problem> problems;

    private ClassListImport(boolean applied, int studentsInFile, int toAdd, int added, int alreadyOnRoster,
                            int unassigned, int blankRowsSkipped, int usualGroupSize, List<Group> groups,
                            Map<String, Integer> programmes, List<Problem> problems) {
        this.applied = applied;
        this.studentsInFile = studentsInFile;
        this.toAdd = toAdd;
        this.added = added;
        this.alreadyOnRoster = alreadyOnRoster;
        this.unassigned = unassigned;
        this.blankRowsSkipped = blankRowsSkipped;
        this.usualGroupSize = usualGroupSize;
        this.groups = groups;
        this.programmes = programmes;
        this.problems = problems;
    }

    static ClassListImport from(JSONObject body) {
        List<Group> groups = new ArrayList<>();
        JSONArray g = body.optJSONArray("groups");
        if (g != null) {
            for (int i = 0; i < g.length(); i++) {
                JSONObject o = g.optJSONObject(i);
                if (o == null) continue;
                groups.add(new Group(o.optString("groupCode", "?"), o.optBoolean("exists"),
                        o.optInt("membersNow"), o.optInt("adding"), o.optInt("willHave"),
                        o.optInt("capacity", StudentRepository.CAPACITY),
                        o.optBoolean("overUsualSize"), o.optBoolean("reopened")));
            }
        }
        Map<String, Integer> programmes = new LinkedHashMap<>();
        JSONObject p = body.optJSONObject("programmes");
        if (p != null) {
            List<String> keys = new ArrayList<>();
            for (Iterator<String> it = p.keys(); it.hasNext(); ) keys.add(it.next());
            Collections.sort(keys);
            for (String k : keys) programmes.put(k, p.optInt(k));
        }
        List<Problem> problems = new ArrayList<>();
        JSONArray pr = body.optJSONArray("problems");
        if (pr != null) {
            for (int i = 0; i < pr.length(); i++) {
                JSONObject o = pr.optJSONObject(i);
                if (o == null) continue;
                problems.add(new Problem(o.optString("row", ""), o.optString("kind", ""), o.optString("message", "")));
            }
        }
        return new ClassListImport(body.optBoolean("applied"), body.optInt("studentsInFile"),
                body.optInt("toAdd"), body.optInt("added"), body.optInt("alreadyOnRoster"),
                body.optInt("unassigned"), body.optInt("blankRowsSkipped"),
                body.optInt("usualGroupSize", StudentRepository.CAPACITY),
                Collections.unmodifiableList(groups), Collections.unmodifiableMap(programmes),
                Collections.unmodifiableList(problems));
    }

    /** Problems of one kind (e.g. how many rows were listed twice). */
    int count(String kind) {
        int n = 0;
        for (Problem p : problems) if (kind.equals(p.kind)) n++;
        return n;
    }

    /** Rows that will not be imported because something on them is wrong (not counting repeats). */
    int rowsWithMistakes() {
        return problems.size() - count("DUPLICATE_IN_FILE");
    }

    int newGroups() {
        int n = 0;
        for (Group g : groups) if (!g.exists) n++;
        return n;
    }

    /** The preview, as the confirm dialog shows it. */
    String previewText() {
        StringBuilder b = new StringBuilder();
        b.append(plural(studentsInFile, "student", "students")).append(" found in the file.\n");
        b.append(toAdd == 0 ? "Nobody new to add" : plural(toAdd, "student", "students") + " will be added");
        b.append(alreadyOnRoster > 0
                ? "; " + alreadyOnRoster + " " + (alreadyOnRoster == 1 ? "is" : "are") + " already on the roster and stay as they are.\n"
                : ".\n");
        if (!programmes.isEmpty()) {
            b.append("Programmes: ");
            boolean first = true;
            for (Map.Entry<String, Integer> e : programmes.entrySet()) {
                if (!first) b.append(", ");
                b.append(e.getKey()).append(' ').append(e.getValue());
                first = false;
            }
            b.append(".\n");
        }
        if (!groups.isEmpty()) {
            b.append("\nGroups:\n");
            for (Group g : groups) b.append("• ").append(groupLine(g)).append('\n');
        }
        if (unassigned > 0) b.append("• ").append(plural(unassigned, "student", "students")).append(" with no group\n");
        appendProblems(b);
        return b.toString().trim();
    }

    /** After saving: one short paragraph for the message shown on the dashboard. */
    String resultText() {
        StringBuilder b = new StringBuilder();
        b.append(added == 0 ? "Nobody new was added" : plural(added, "student was", "students were") + " added");
        if (newGroups() > 0) b.append(", ").append(plural(newGroups(), "new group", "new groups"));
        b.append('.');
        if (alreadyOnRoster > 0) b.append(' ').append(alreadyOnRoster).append(" already on the roster were left as they were.");
        if (rowsWithMistakes() > 0) b.append(' ').append(plural(rowsWithMistakes(), "row was", "rows were")).append(" skipped.");
        return b.toString();
    }

    String groupLine(Group g) {
        StringBuilder b = new StringBuilder(g.code).append(": ");
        if (!g.exists) b.append("new group, ").append(g.willHave).append(" of ").append(g.capacity).append(" places");
        else {
            b.append("+").append(g.adding).append(" (").append(g.willHave).append(" of ").append(g.capacity).append(')');
            if (g.reopened) b.append(", reopened");
        }
        if (g.overUsualSize) b.append(", more than ").append(usualGroupSize).append(": all kept, full until below ").append(usualGroupSize);
        return b.toString();
    }

    private void appendProblems(StringBuilder b) {
        int repeats = count("DUPLICATE_IN_FILE");
        int mistakes = rowsWithMistakes();
        if (repeats == 0 && mistakes == 0) return;
        b.append('\n');
        if (repeats > 0) {
            b.append(plural(repeats, "student is", "students are"))
                    .append(" listed twice: the first entry is kept.\n");
        }
        if (mistakes > 0) {
            b.append(plural(mistakes, "row", "rows")).append(" can't be imported and will be skipped:\n");
            int shown = 0;
            for (Problem p : problems) {
                if ("DUPLICATE_IN_FILE".equals(p.kind)) continue;
                if (shown == 5) { b.append("• and ").append(mistakes - 5).append(" more\n"); break; }
                b.append("• ").append(p.row.isEmpty() ? "" : capitalise(p.row) + ": ").append(p.message).append('\n');
                shown++;
            }
        }
    }

    /** "Import 206 students", the confirm button. */
    String confirmLabel() {
        return "Import " + plural(toAdd, "student", "students");
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }
}
