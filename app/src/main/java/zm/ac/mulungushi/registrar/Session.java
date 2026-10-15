package zm.ac.mulungushi.registrar;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Who is signed in: the token the backend gave us plus the account and student
 * details from the same answer. Filled in by ApiAuthService after a successful
 * sign-in or sign-up, cleared on sign-out.
 *
 * Kept in this app's private SharedPreferences so it survives the app being
 * closed. The file is excluded from Android backups (see res/xml/backup_rules.xml
 * and data_extraction_rules.xml), so a token never leaves the phone.
 *
 * Until {@link #init(Context)} has run (for example in plain-Java tests) it works
 * in memory only.
 */
public final class Session {

    static final String FILE = "session";

    private static final String TOKEN = "token";
    private static final String ROLE = "role";
    private static final String ACCOUNT_ID = "accountId";
    private static final String EMAIL = "email";
    private static final String PHONE = "phone";
    private static final String DISPLAY_NAME = "displayName";
    private static final String EMPLOYEE_ID = "employeeId";
    private static final String STUDENT_ID = "studentId";
    private static final String STUDENT_NUMBER = "studentNumber";
    private static final String STUDENT_NAME = "studentName";
    private static final String PROGRAMME = "programme";
    private static final String GROUP = "group";
    private static final String NRC = "nrc";
    private static final String DATE_OF_BIRTH = "dateOfBirth";

    private static final String[] KEYS = {TOKEN, ROLE, ACCOUNT_ID, EMAIL, PHONE, DISPLAY_NAME, EMPLOYEE_ID,
            STUDENT_ID, STUDENT_NUMBER, STUDENT_NAME, PROGRAMME, GROUP, NRC, DATE_OF_BIRTH};

    private static SharedPreferences prefs;
    private static final Map<String, String> values = new HashMap<>();

    private Session() {}

    /** Called once from RegistrarApp.onCreate. */
    public static synchronized void init(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        values.clear();
        for (String k : KEYS) {
            String v = prefs.getString(k, null);
            if (v != null) values.put(k, v);
        }
    }

    /**
     * Stores the answer to a successful sign-in or sign-up:
     *   { token, account: { accountId, role, email, phone, displayName, employeeId, studentId },
     *     student?: { studentId, studentNumber, fullName, programmeCode, groupCode, nrc, dateOfBirth } }
     */
    static synchronized void save(JSONObject body) {
        values.clear();
        put(TOKEN, body.optString("token", null));
        JSONObject account = body.optJSONObject("account");
        if (account != null) {
            put(ACCOUNT_ID, account.optString("accountId", null));
            put(ROLE, account.optString("role", null));
            put(EMAIL, text(account, "email"));
            put(PHONE, text(account, "phone"));
            put(DISPLAY_NAME, text(account, "displayName"));
            put(EMPLOYEE_ID, text(account, "employeeId"));
            put(STUDENT_ID, text(account, "studentId"));
        }
        JSONObject student = body.optJSONObject("student");
        if (student != null) {
            put(STUDENT_ID, text(student, "studentId"));
            put(STUDENT_NUMBER, text(student, "studentNumber"));
            put(STUDENT_NAME, text(student, "fullName"));
            put(PROGRAMME, text(student, "programmeCode"));
            put(GROUP, text(student, "groupCode"));
            put(NRC, text(student, "nrc"));
            put(DATE_OF_BIRTH, text(student, "dateOfBirth"));
        }
        persist();
    }

    public static synchronized void clear() {
        values.clear();
        persist();
    }

    public static synchronized boolean isSignedIn() {
        return values.get(TOKEN) != null;
    }

    public static synchronized String token() { return values.get(TOKEN); }
    /** The signed-in account's id: what this phone's saved data and queued changes belong to. */
    public static synchronized String accountId() { return values.get(ACCOUNT_ID); }
    public static synchronized boolean isLecturer() { return "LECTURER".equals(values.get(ROLE)); }
    public static synchronized boolean isStudent() { return "STUDENT".equals(values.get(ROLE)); }
    public static synchronized String email() { return values.get(EMAIL); }
    public static synchronized String displayName() { return values.get(DISPLAY_NAME); }
    public static synchronized String employeeId() { return values.get(EMPLOYEE_ID); }
    public static synchronized String studentNumber() { return values.get(STUDENT_NUMBER); }
    public static synchronized String studentId() { return values.get(STUDENT_ID); }

    /**
     * The signed-in student's record from the server, in the shape the rest of the
     * app uses, or null for a lecturer. Group null on the server means Unassigned.
     */
    static synchronized Student toStudent() {
        String number = values.get(STUDENT_NUMBER);
        if (number == null) return null;
        String group = values.get(GROUP);
        Student s = new Student(values.get(STUDENT_ID), values.get(STUDENT_NAME), number,
                values.get(PROGRAMME), group == null ? StudentRepository.UNASSIGNED : group, true);
        s.email = values.get(EMAIL);
        s.phone = values.get(PHONE);
        s.nrc = values.get(NRC);
        return s;
    }

    // ---- helpers ----

    /** A JSON string field, or null when it is missing or JSON null. */
    private static String text(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) return null;
        String v = o.optString(key, null);
        return v == null || v.isEmpty() ? null : v;
    }

    private static void put(String key, String value) {
        if (value != null && !value.isEmpty() && !"null".equals(value)) values.put(key, value);
    }

    private static void persist() {
        if (prefs == null) return;
        SharedPreferences.Editor e = prefs.edit().clear();
        for (Map.Entry<String, String> entry : values.entrySet()) e.putString(entry.getKey(), entry.getValue());
        e.apply();
    }
}
