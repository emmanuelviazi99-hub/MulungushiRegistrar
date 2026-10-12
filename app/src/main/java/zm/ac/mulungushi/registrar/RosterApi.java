package zm.ac.mulungushi.registrar;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The roster, lab groups and change requests, on the backend (see API_CONTRACT.md):
 *
 *   lecturer  GET /students, POST /students, PUT /students/:id (fields + number + group in one go),
 *             DELETE /students/:id, GET /groups, GET/PUT /group-requests, /number-correction-requests
 *   student   GET /students/me, PUT /students/me, GET /groups, GET /groups/mine,
 *             GET /my-requests, POST /group-requests, POST /number-correction-requests,
 *             DELETE /my-requests/group|number
 *
 * Every change is made on the server first. After it the same background job
 * reloads, and the fresh answer is put into StudentRepository on the main thread,
 * so the screens always show what the server has. A failed change changes nothing
 * on the phone either. Network work runs on one background thread, in order.
 */
public final class RosterApi {

    /** What happened: ok, or the backend's error code and message. NETWORK means the server was not reached. */
    public static final class Outcome {
        public static final String NETWORK = "NETWORK";
        public static final String SERVER = "SERVER";

        public final boolean ok;
        public final String error;   // e.g. GROUP_FULL, DUPLICATE_STUDENT_NUMBER, UNAUTHORIZED, NETWORK
        public final String message; // the backend's plain-language message, may be null

        Outcome(boolean ok, String error, String message) {
            this.ok = ok;
            this.error = error;
            this.message = message;
        }

        static final Outcome OK = new Outcome(true, "", null);

        static Outcome of(ApiClient.Response res) {
            if (res.ok()) return OK;
            String code = res.error();
            if (code.isEmpty()) code = res.status == 401 ? "UNAUTHORIZED" : SERVER;
            return new Outcome(false, code, res.message());
        }

        public boolean is(String code) {
            return code.equals(error);
        }
    }

    public interface Done {
        void onResult(Outcome outcome);
    }

    private static RosterApi instance;

    private final Executor background;
    private final Executor callbacks;

    public static synchronized RosterApi get() {
        if (instance == null) instance = new RosterApi(Executors.newSingleThreadExecutor(), new MainThread());
        return instance;
    }

    /** For tests: run the work and the callbacks on the given executors. */
    RosterApi(Executor background, Executor callbacks) {
        this.background = background;
        this.callbacks = callbacks;
    }

    // =====================================================================
    // lecturer
    // =====================================================================

    /** Loads the roster, group totals and both queues of open requests. */
    public void loadRoster(Done done) {
        run(done, () -> reloadRoster(Outcome.OK));
    }

    /** Loads the Answered history (approved and declined, both kinds). */
    public void loadAnswered(Done done) {
        run(done, () -> {
            List<StudentRepository.RequestRecord> all = new ArrayList<>();
            for (String path : new String[]{"/group-requests", "/number-correction-requests"}) {
                ApiClient.Response res = ApiClient.get(path + "?status=ANSWERED", token());
                if (!res.ok()) return new Step(Outcome.of(res), null);
                all.addAll(requestsFrom(res.body.optJSONArray("requests")));
            }
            return new Step(Outcome.OK, () -> StudentRepository.getInstance().applyAnswered(all));
        });
    }

    /** Adds a student to the roster. groupCode may be Unassigned. */
    public void addStudent(String name, String number, String nrc, String dateOfBirth,
                           String programme, String group, Done done) {
        run(done, () -> {
            JSONObject body = new JSONObject()
                    .put("fullName", name)
                    .put("studentNumber", number)
                    .put("nrc", nrc)
                    .put("dateOfBirth", dateOfBirth)
                    .put("programmeCode", programme);
            if (!StudentRepository.UNASSIGNED.equals(group)) body.put("groupCode", group);
            ApiClient.Response res = ApiClient.post("/students", body, token());
            return reloadRoster(Outcome.of(res));
        });
    }

    /**
     * Saves the lecturer's edit: only what changed is sent, with the version the
     * sheet was opened on, so someone else's newer change is refused (VERSION_CONFLICT)
     * rather than overwritten. Name, programme, number and group are saved together
     * or not at all.
     */
    public void saveStudent(Student original, String name, String number, String programme, String group, Done done) {
        run(done, () -> {
            JSONObject body = new JSONObject();
            if (!name.equals(original.name)) body.put("fullName", name);
            if (!programme.equals(original.programme)) body.put("programmeCode", programme);
            if (!number.equals(original.number)) body.put("studentNumber", number);
            if (!group.equals(original.group)) {
                body.put("groupCode", StudentRepository.UNASSIGNED.equals(group) ? "UNASSIGNED" : group);
            }
            if (body.length() == 0) return reloadRoster(Outcome.OK); // nothing changed
            if (original.version > 0) body.put("version", original.version);
            ApiClient.Response res = ApiClient.put("/students/" + original.id, body, token());
            return reloadRoster(Outcome.of(res));
        });
    }

    /** Soft delete: the seat is freed and the student's login disabled; the number stays reserved. */
    public void deleteStudent(String studentId, Done done) {
        run(done, () -> reloadRoster(Outcome.of(ApiClient.delete("/students/" + studentId, token()))));
    }

    /** Approve an open request. A group move is re-checked against the 15-seat limit at that moment. */
    public void approve(String kind, int requestId, Done done) {
        answer(kind, requestId, "approve", done);
    }

    public void decline(String kind, int requestId, Done done) {
        answer(kind, requestId, "decline", done);
    }

    private void answer(String kind, int requestId, String verb, Done done) {
        String base = StudentRepository.RequestRecord.GROUP.equals(kind) ? "/group-requests/" : "/number-correction-requests/";
        run(done, () -> reloadRoster(Outcome.of(ApiClient.put(base + requestId + "/" + verb, null, token()))));
    }

    /** Reloads everything the lecturer screens show; reports `first` (the change's outcome) if the reload works. */
    private Step reloadRoster(Outcome first) throws IOException, JSONException {
        List<Student> students = new ArrayList<>();
        int page = 1;
        int pages;
        do {
            ApiClient.Response res = ApiClient.get("/students?limit=100&page=" + page, token());
            if (!res.ok()) return new Step(first.ok ? Outcome.of(res) : first, null);
            JSONArray list = res.body.optJSONArray("students");
            if (list != null) for (int i = 0; i < list.length(); i++) students.add(studentFrom(list.getJSONObject(i)));
            pages = res.body.optInt("totalPages", 1);
            page++;
        } while (page <= pages);

        GroupTotals totals = fetchGroups();
        if (totals.failure != null) return new Step(first.ok ? totals.failure : first, null);

        List<StudentRepository.RequestRecord> pending = new ArrayList<>();
        for (String path : new String[]{"/group-requests", "/number-correction-requests"}) {
            ApiClient.Response res = ApiClient.get(path + "?status=PENDING", token());
            if (!res.ok()) return new Step(first.ok ? Outcome.of(res) : first, null);
            pending.addAll(requestsFrom(res.body.optJSONArray("requests")));
        }

        return new Step(first, () -> {
            StudentRepository repo = StudentRepository.getInstance();
            repo.applyGroups(totals.codes, totals.counts);
            repo.applyRoster(students, pending);
        });
    }

    // =====================================================================
    // student
    // =====================================================================

    /** Loads the signed-in student's own record, latest requests, group totals and group members. */
    public void loadMine(Done done) {
        run(done, () -> reloadMine(Outcome.OK));
    }

    /** Name and programme only; the number goes through a correction request. */
    public void updateMe(String name, String programme, Done done) {
        run(done, () -> {
            JSONObject body = new JSONObject().put("fullName", name).put("programmeCode", programme);
            return reloadMine(Outcome.of(ApiClient.put("/students/me", body, token())));
        });
    }

    /** Ask to move to another group (or Unassigned). Nothing moves until a lecturer approves. */
    public void requestGroupChange(String group, Done done) {
        run(done, () -> {
            JSONObject body = new JSONObject()
                    .put("groupCode", StudentRepository.UNASSIGNED.equals(group) ? "UNASSIGNED" : group);
            return reloadMine(Outcome.of(ApiClient.post("/group-requests", body, token())));
        });
    }

    public void requestNumberCorrection(String number, String reason, Done done) {
        run(done, () -> {
            JSONObject body = new JSONObject().put("newNumber", number);
            if (reason != null && !reason.trim().isEmpty()) body.put("reason", reason.trim());
            return reloadMine(Outcome.of(ApiClient.post("/number-correction-requests", body, token())));
        });
    }

    public void cancelGroupChange(Done done) {
        run(done, () -> reloadMine(Outcome.of(ApiClient.delete("/my-requests/group", token()))));
    }

    public void cancelNumberCorrection(Done done) {
        run(done, () -> reloadMine(Outcome.of(ApiClient.delete("/my-requests/number", token()))));
    }

    private Step reloadMine(Outcome first) throws IOException, JSONException {
        ApiClient.Response meRes = ApiClient.get("/students/me", token());
        if (!meRes.ok()) return new Step(first.ok ? Outcome.of(meRes) : first, null);
        Student self = studentFrom(meRes.body.getJSONObject("student"));
        self.email = Session.email();

        ApiClient.Response reqRes = ApiClient.get("/my-requests", token());
        if (!reqRes.ok()) return new Step(first.ok ? Outcome.of(reqRes) : first, null);
        StudentRepository.RequestRecord latestGroup = requestFrom(reqRes.body.optJSONObject("group"));
        StudentRepository.RequestRecord latestNumber = requestFrom(reqRes.body.optJSONObject("number"));

        GroupTotals totals = fetchGroups();
        if (totals.failure != null) return new Step(first.ok ? totals.failure : first, null);

        ApiClient.Response mineRes = ApiClient.get("/groups/mine", token());
        if (!mineRes.ok()) return new Step(first.ok ? Outcome.of(mineRes) : first, null);
        List<Student> members = new ArrayList<>();
        JSONArray list = mineRes.body.optJSONArray("members");
        String groupCode = text(mineRes.body, "groupCode");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject m = list.getJSONObject(i);
                members.add(new Student(null, m.optString("fullName"), m.optString("studentNumber"), null,
                        groupCode == null ? StudentRepository.UNASSIGNED : groupCode, true));
            }
        }

        return new Step(first, () -> {
            StudentRepository repo = StudentRepository.getInstance();
            repo.applyGroups(totals.codes, totals.counts);
            repo.applyMine(self, latestGroup, latestNumber, members);
        });
    }

    // =====================================================================
    // shared
    // =====================================================================

    private static final class GroupTotals {
        final List<String> codes = new ArrayList<>();
        final Map<String, Integer> counts = new HashMap<>();
        Outcome failure;
    }

    private static GroupTotals fetchGroups() throws IOException, JSONException {
        GroupTotals t = new GroupTotals();
        ApiClient.Response res = ApiClient.get("/groups", token());
        if (!res.ok()) {
            t.failure = Outcome.of(res);
            return t;
        }
        JSONArray groups = res.body.optJSONArray("groups");
        if (groups != null) {
            for (int i = 0; i < groups.length(); i++) {
                JSONObject g = groups.getJSONObject(i);
                t.codes.add(g.getString("groupCode"));
                t.counts.put(g.getString("groupCode"), g.optInt("activeCount", 0));
            }
        }
        return t;
    }

    /** A student record from the backend, in the shape the screens use. groupCode null = Unassigned. */
    static Student studentFrom(JSONObject o) throws JSONException {
        String group = text(o, "groupCode");
        Student s = new Student(o.getString("studentId"), o.optString("fullName"), o.optString("studentNumber"),
                text(o, "programmeCode"), group == null ? StudentRepository.UNASSIGNED : group, o.optBoolean("active", true));
        s.version = o.optInt("version", 0);
        s.nrc = text(o, "nrc") != null ? text(o, "nrc") : text(o, "nrcMasked");
        s.dateOfBirth = text(o, "dateOfBirth");
        return s;
    }

    private static List<StudentRepository.RequestRecord> requestsFrom(JSONArray list) throws JSONException {
        List<StudentRepository.RequestRecord> out = new ArrayList<>();
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) out.add(requestFrom(list.getJSONObject(i)));
        return out;
    }

    /** A change request from the backend, or null for JSON null. */
    static StudentRepository.RequestRecord requestFrom(JSONObject o) {
        if (o == null) return null;
        StudentRepository.RequestRecord r = new StudentRepository.RequestRecord();
        r.requestId = o.optInt("requestId");
        r.studentId = text(o, "studentId");
        r.studentName = text(o, "studentName");
        r.studentNumber = text(o, "studentNumber");
        boolean group = "GROUP".equals(o.optString("kind"));
        r.kind = group ? StudentRepository.RequestRecord.GROUP : StudentRepository.RequestRecord.NUMBER;
        if (group) {
            String from = text(o, "fromGroupCode");
            String to = text(o, "toGroupCode");
            r.from = from == null ? StudentRepository.UNASSIGNED : from;
            r.to = to == null ? StudentRepository.UNASSIGNED : to;
        } else {
            r.from = text(o, "oldNumber");
            r.to = text(o, "newNumber");
        }
        r.reason = text(o, "reason");
        r.status = o.optString("status", "PENDING").toLowerCase(Locale.ROOT);
        r.requestedOn = shortDate(text(o, "requestedAt"));
        Date resolved = parseIso(text(o, "resolvedAt"));
        r.resolvedAtMillis = resolved == null ? 0 : resolved.getTime();
        return r;
    }

    /** "2026-10-04T09:12:33.000Z" -> "4 Oct" in the phone's time zone; null stays null. */
    static String shortDate(String iso) {
        Date d = parseIso(iso);
        return d == null ? null : new SimpleDateFormat("d MMM", Locale.ENGLISH).format(d);
    }

    private static Date parseIso(String iso) {
        if (iso == null) return null;
        for (String pattern : new String[]{"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"}) {
            SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.ROOT);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            try {
                return f.parse(iso);
            } catch (ParseException ignored) {
                // try the next shape
            }
        }
        return null;
    }

    /** A JSON string field, or null when it is missing, JSON null or empty. */
    private static String text(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return null;
        String v = o.optString(key, null);
        return v == null || v.isEmpty() ? null : v;
    }

    private static String token() {
        return Session.token();
    }

    /** The outcome of a job, plus the change to make to StudentRepository on the main thread (or null). */
    private static final class Step {
        final Outcome outcome;
        final Runnable apply;

        Step(Outcome outcome, Runnable apply) {
            this.outcome = outcome;
            this.apply = apply;
        }
    }

    private interface Work {
        Step run() throws IOException, JSONException;
    }

    private void run(Done done, Work work) {
        background.execute(() -> {
            Step step;
            try {
                step = work.run();
            } catch (IOException e) {
                step = new Step(new Outcome(false, Outcome.NETWORK, null), null);
            } catch (JSONException | RuntimeException e) {
                step = new Step(new Outcome(false, Outcome.SERVER, null), null);
            }
            final Step answer = step;
            callbacks.execute(() -> {
                if (answer.apply != null) answer.apply.run();
                if (done != null) done.onResult(answer.outcome);
            });
        });
    }

    /** Posts callbacks to the main (UI) thread. */
    private static final class MainThread implements Executor {
        private final Handler handler = new Handler(Looper.getMainLooper());

        @Override
        public void execute(Runnable r) {
            handler.post(r);
        }
    }
}
