package zm.ac.mulungushi.registrar;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Device-local persistence (SharedPreferences, private to the app). Data remains after the
 * app is closed and reopened and works with no network. Also holds the sync bookkeeping:
 * which version of each record the server is known to hold.
 *
 * Writes use commit(): the data is on disk when the call returns, so a registration that
 * succeeded is never lost if the app is closed straight afterwards.
 */
public final class LocalStorage {
    static final String PREFS = "mulungushi_registrar_local_v1";
    private static final String STUDENTS = "students_json";
    private static final String ANSWERED = "answered_json";
    private static final String SYNCED = "synced_sig_json";
    private final SharedPreferences prefs;

    public LocalStorage(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean hasSavedStudents() { return prefs.contains(STUDENTS); }

    // ---- Student <-> JSON (also the server payload for /api/students/sync) ----

    public static JSONObject toJson(Student s) throws Exception {
        JSONObject o = new JSONObject();
        put(o, "id", s.id);
        put(o, "firstName", s.firstName);
        put(o, "lastName", s.lastName);
        put(o, "number", s.number); // always a String: leading zeros are kept
        put(o, "programme", s.programme);
        put(o, "group", s.group);
        o.put("active", s.active);
        put(o, "email", s.email);
        o.put("registeredAt", s.registeredAt);
        put(o, "pendingGroup", s.pendingGroup);
        put(o, "pendingNumber", s.pendingNumber);
        put(o, "pendingGroupOn", s.pendingGroupOn);
        put(o, "pendingNumberOn", s.pendingNumberOn);
        put(o, "pendingNumberReason", s.pendingNumberReason);
        o.put("pendingGroupSeq", s.pendingGroupSeq);
        o.put("pendingNumberSeq", s.pendingNumberSeq);
        put(o, "noticeKind", s.noticeKind);
        put(o, "noticeSubject", s.noticeSubject);
        o.put("updatedAt", s.updatedAt);
        return o;
    }

    public static Student fromJson(JSONObject o) {
        String first = nullable(o, "firstName");
        String last = nullable(o, "lastName");
        if (first == null && last == null) {
            // Data saved by the earlier build had one "name" field: split it once, at the first space.
            String[] parts = splitName(str(o, "name"));
            first = parts[0];
            last = parts[1];
        }
        Student s = new Student(str(o, "id"), first == null ? "" : first, last == null ? "" : last,
                numberOf(o), str(o, "programme"), str(o, "group"), o.optBoolean("active", true));
        s.email = nullable(o, "email");
        s.registeredAt = o.optLong("registeredAt", 0L);
        s.pendingGroup = nullable(o, "pendingGroup");
        s.pendingNumber = nullable(o, "pendingNumber");
        s.pendingGroupOn = nullable(o, "pendingGroupOn");
        s.pendingNumberOn = nullable(o, "pendingNumberOn");
        s.pendingNumberReason = nullable(o, "pendingNumberReason");
        s.pendingGroupSeq = o.optInt("pendingGroupSeq");
        s.pendingNumberSeq = o.optInt("pendingNumberSeq");
        s.noticeKind = nullable(o, "noticeKind");
        s.noticeSubject = nullable(o, "noticeSubject");
        s.updatedAt = o.optLong("updatedAt", 0L);
        return s;
    }

    /** "Chanda Mwansa" -> {"Chanda", "Mwansa"}; a one-word name keeps it as the first name. */
    static String[] splitName(String name) {
        String t = name == null ? "" : name.trim();
        int space = t.indexOf(' ');
        if (space < 0) return new String[]{t, ""};
        return new String[]{t.substring(0, space), t.substring(space + 1).trim()};
    }

    /** The number as text. A number stored by mistake as a JSON number is turned back into text. */
    private static String numberOf(JSONObject o) {
        if (o.isNull("number")) return "";
        Object v = o.opt("number");
        if (v instanceof String) return (String) v;
        return v == null ? "" : String.valueOf(v);
    }

    /** The record's content without its timestamp: two records with the same signature hold the same data. */
    public static String signature(Student s) {
        try {
            JSONObject o = toJson(s);
            o.remove("updatedAt");
            return o.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Could not read student data", e);
        }
    }

    /** Saves everything in one write and waits until it is on disk. */
    public void saveAll(List<Student> students, List<StudentRepository.RequestRecord> answered, Map<String, String> synced) {
        try {
            SharedPreferences.Editor e = prefs.edit();
            e.putString(STUDENTS, studentsJson(students));
            e.putString(ANSWERED, answeredJson(answered));
            e.putString(SYNCED, syncedJson(synced));
            if (!e.commit()) throw new IllegalStateException("The phone refused to save the data");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not save student data locally", e);
        }
    }

    public void saveStudents(List<Student> list) {
        try {
            if (!prefs.edit().putString(STUDENTS, studentsJson(list)).commit()) {
                throw new IllegalStateException("The phone refused to save the data");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not save student data locally", e);
        }
    }

    public List<Student> loadStudents() {
        List<Student> out = new ArrayList<>();
        String raw = prefs.getString(STUDENTS, null);
        if (raw == null) return out;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) out.add(fromJson(a.getJSONObject(i)));
        } catch (Exception e) {
            throw new IllegalStateException("Saved student data could not be read", e);
        }
        return out;
    }

    // ---- Sync bookkeeping: id -> signature of the version the server is known to hold ----

    public void saveSynced(Map<String, String> synced) {
        try {
            prefs.edit().putString(SYNCED, syncedJson(synced)).commit();
        } catch (Exception e) {
            throw new IllegalStateException("Could not save sync state", e);
        }
    }

    public Map<String, String> loadSynced() {
        Map<String, String> out = new HashMap<>();
        String raw = prefs.getString(SYNCED, null);
        if (raw == null) return out;
        try {
            JSONObject o = new JSONObject(raw);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                out.put(k, o.getString(k));
            }
        } catch (Exception e) {
            /* corrupt sync state: treat everything as unsynced, the data itself is safe */
        }
        return out;
    }

    public void saveAnswered(List<StudentRepository.RequestRecord> list) {
        try {
            prefs.edit().putString(ANSWERED, answeredJson(list)).commit();
        } catch (Exception e) {
            throw new IllegalStateException("Could not save request history", e);
        }
    }

    public List<StudentRepository.RequestRecord> loadAnswered() {
        List<StudentRepository.RequestRecord> out = new ArrayList<>();
        String raw = prefs.getString(ANSWERED, null);
        if (raw == null) return out;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                StudentRepository.RequestRecord r = new StudentRepository.RequestRecord();
                r.seq = o.optInt("seq");
                r.studentId = nullable(o, "studentId");
                r.studentName = nullable(o, "studentName");
                r.studentNumber = nullable(o, "studentNumber");
                r.kind = nullable(o, "kind");
                r.from = nullable(o, "from");
                r.to = nullable(o, "to");
                r.reason = nullable(o, "reason");
                r.requestedOn = nullable(o, "requestedOn");
                r.status = nullable(o, "status");
                out.add(r);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Saved request history could not be read", e);
        }
        return out;
    }

    // ---- JSON helpers ----

    private static String studentsJson(List<Student> list) throws Exception {
        JSONArray a = new JSONArray();
        for (Student s : list) a.put(toJson(s));
        return a.toString();
    }

    private static String answeredJson(List<StudentRepository.RequestRecord> list) throws Exception {
        JSONArray a = new JSONArray();
        for (StudentRepository.RequestRecord r : list) {
            JSONObject o = new JSONObject();
            o.put("seq", r.seq);
            put(o, "studentId", r.studentId);
            put(o, "studentName", r.studentName);
            put(o, "studentNumber", r.studentNumber);
            put(o, "kind", r.kind);
            put(o, "from", r.from);
            put(o, "to", r.to);
            put(o, "reason", r.reason);
            put(o, "requestedOn", r.requestedOn);
            put(o, "status", r.status);
            a.put(o);
        }
        return a.toString();
    }

    private static String syncedJson(Map<String, String> synced) throws Exception {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, String> e : synced.entrySet()) o.put(e.getKey(), e.getValue());
        return o.toString();
    }

    private static void put(JSONObject o, String k, String v) throws Exception {
        if (v == null) o.put(k, JSONObject.NULL);
        else o.put(k, v);
    }

    private static String str(JSONObject o, String k) { return o.isNull(k) ? "" : o.optString(k, ""); }

    private static String nullable(JSONObject o, String k) { return o.isNull(k) ? null : o.optString(k, null); }
}
