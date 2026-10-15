package zm.ac.mulungushi.registrar;

import android.content.Context;
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
 * The roster, lab groups and change requests: on the server, saved on the phone,
 * and changed offline-first.
 *
 *   lecturer  GET /students, POST /students, PUT /students/:id (fields + number + group in one go),
 *             DELETE /students/:id, GET /groups, GET/PUT /group-requests, /number-correction-requests
 *   student   GET /students/me, PUT /students/me, GET /groups, GET /groups/mine,
 *             GET /my-requests, POST /group-requests, POST /number-correction-requests,
 *             DELETE /my-requests/group|number
 *
 * READING: every answer from the server is saved on the phone (LocalStore, a Room
 * database). When the server cannot be reached, the screens get the saved copy,
 * marked with when it was saved, instead of nothing.
 *
 * CHANGING: a change is first saved on the phone as a PendingOp (the outbox), then
 * uploaded at once by SyncEngine. If the network is down it stays saved, the screens
 * show it marked "waiting to upload", and WorkManager (SyncWorker) uploads it when
 * the phone is back online, even if the app was closed. The server still decides:
 * a change it refuses is not kept as if it had happened, and the person is told why.
 *
 * Network and database work run on one background thread, in order; answers are
 * handed to StudentRepository on the main thread.
 */
public final class RosterApi {

    /** What happened: ok, or the backend's error code and message. NETWORK means the server was not reached. */
    public static final class Outcome {
        public static final String NETWORK = "NETWORK";
        public static final String SERVER = "SERVER";
        public static final String UNAUTHORIZED = "UNAUTHORIZED";

        public final boolean ok;
        public final String error;   // e.g. GROUP_FULL, DUPLICATE_STUDENT_NUMBER, UNAUTHORIZED, NETWORK
        public final String message; // the backend's plain-language message, may be null
        /** A change saved on the phone that could not be uploaded yet (ok is true: nothing was lost). */
        public final boolean queued;

        Outcome(boolean ok, String error, String message, boolean queued) {
            this.ok = ok;
            this.error = error;
            this.message = message;
            this.queued = queued;
        }

        Outcome(boolean ok, String error, String message) {
            this(ok, error, message, false);
        }

        static final Outcome OK = new Outcome(true, "", null);
        static final Outcome QUEUED = new Outcome(true, "", null, true);
        static final Outcome OFFLINE = new Outcome(false, NETWORK, null);

        static Outcome of(ApiClient.Response res) {
            if (res.ok()) return OK;
            String code = res.error();
            if (code.isEmpty()) code = res.status == 401 ? UNAUTHORIZED : SERVER;
            return new Outcome(false, code, res.message());
        }

        public boolean is(String code) {
            return code.equals(error);
        }
    }

    public interface Done {
        void onResult(Outcome outcome);
    }

    /** Asks for an upload later, when there is a network connection (SyncWorker on the phone). */
    public interface UploadScheduler {
        void scheduleUpload();

        void cancelUpload();
    }

    /** What a background sync did (SyncWorker, Sync now). */
    public static final class SyncReport {
        public boolean offline;
        public boolean signedOut;
        public int sent;
        public int refused;
        public int waiting;
    }

    private static RosterApi instance;

    private final Executor background;
    private final Executor callbacks;
    private final LocalStore store;
    private final SyncEngine engine;
    private final UploadScheduler scheduler;

    /** Called once from RegistrarApp: the Room database and WorkManager. */
    public static synchronized void init(Context context) {
        Context app = context.getApplicationContext();
        instance = new RosterApi(Executors.newSingleThreadExecutor(), new MainThread(), new RoomLocalStore(app),
                new UploadScheduler() {
                    @Override public void scheduleUpload() { SyncWorker.schedule(app); }
                    @Override public void cancelUpload() { SyncWorker.cancel(app); }
                });
        instance.restoreSaved();
    }

    public static synchronized RosterApi get() {
        if (instance == null) {
            // Only without RegistrarApp (plain-Java checks): kept in memory, no WorkManager.
            instance = new RosterApi(Executors.newSingleThreadExecutor(), new MainThread(), new MemoryLocalStore(), null);
        }
        return instance;
    }

    /** For tests and the live check: chosen executors and store, no WorkManager. */
    RosterApi(Executor background, Executor callbacks, LocalStore store, UploadScheduler scheduler) {
        this.background = background;
        this.callbacks = callbacks;
        this.store = store;
        this.engine = new SyncEngine(store);
        this.scheduler = scheduler;
    }

    /** For the live check: make this the instance the app's classes use. */
    static synchronized void use(RosterApi api) {
        instance = api;
    }

    LocalStore store() {
        return store;
    }

    // =====================================================================
    // lecturer: reading
    // =====================================================================

    /** Uploads waiting changes, then loads the roster, group totals and both queues of open requests. */
    public void loadRoster(Done done) {
        run(done, () -> refreshRoster(Outcome.OK));
    }

    /** Loads the Answered history (approved and declined, both kinds). */
    public void loadAnswered(Done done) {
        run(done, () -> {
            String account = Session.accountId();
            JSONArray all = new JSONArray();
            try {
                for (String path : new String[]{"/group-requests", "/number-correction-requests"}) {
                    ApiClient.Response res = ApiClient.get(path + "?status=ANSWERED", token());
                    if (!res.ok()) return new Step(Outcome.of(res), answeredFromStore(account));
                    JSONArray list = res.body.optJSONArray("requests");
                    if (list != null) for (int i = 0; i < list.length(); i++) all.put(list.get(i));
                }
            } catch (IOException e) {
                return new Step(Outcome.OFFLINE, answeredFromStore(account));
            }
            store.putEntry(account, "answered", all.toString(), now());
            List<StudentRepository.RequestRecord> records = requestsFrom(all);
            return new Step(Outcome.OK, () -> StudentRepository.getInstance().applyAnswered(records));
        });
    }

    // =====================================================================
    // lecturer: changing (saved on the phone first, then uploaded)
    // =====================================================================

    /** Adds a student to the roster. groupCode may be Unassigned. */
    public void addStudent(String name, String number, String nrc, String dateOfBirth,
                           String programme, String group, Done done) {
        try {
            JSONObject body = new JSONObject()
                    .put("fullName", name)
                    .put("studentNumber", number)
                    .put("nrc", nrc)
                    .put("dateOfBirth", dateOfBirth)
                    .put("programmeCode", programme);
            if (!StudentRepository.UNASSIGNED.equals(group)) body.put("groupCode", group);
            String localId = PendingOp.LOCAL_ID_PREFIX + java.util.UUID.randomUUID();
            submit(PendingOp.create(Session.accountId(), PendingOp.ADD_STUDENT, localId, body,
                    "Add " + name + " (" + number + ")"), true, done);
        } catch (JSONException e) {
            done.onResult(new Outcome(false, Outcome.SERVER, null));
        }
    }

    /**
     * Saves the lecturer's edit: only what changed is sent, with the version the
     * sheet was opened on, so someone else's newer change is refused (VERSION_CONFLICT)
     * rather than overwritten. Name, programme, number and group are saved together
     * or not at all.
     */
    public void saveStudent(Student original, String name, String number, String programme, String group, Done done) {
        try {
            JSONObject body = new JSONObject();
            List<String> what = new ArrayList<>();
            if (!name.equals(original.name)) { body.put("fullName", name); what.add("name"); }
            if (!programme.equals(original.programme)) { body.put("programmeCode", programme); what.add("programme " + programme); }
            if (!number.equals(original.number)) { body.put("studentNumber", number); what.add("number " + number); }
            if (!group.equals(original.group)) {
                body.put("groupCode", StudentRepository.UNASSIGNED.equals(group) ? "UNASSIGNED" : group);
                what.add(StudentRepository.UNASSIGNED.equals(group) ? "no group" : "group " + group);
            }
            if (body.length() == 0) { // nothing changed
                if (done != null) done.onResult(Outcome.OK);
                return;
            }
            PendingOp op = PendingOp.create(Session.accountId(), PendingOp.SAVE_STUDENT, original.id, body,
                    name + ": " + join(what));
            op.baseVersion = original.version;
            submit(op, true, done);
        } catch (JSONException e) {
            done.onResult(new Outcome(false, Outcome.SERVER, null));
        }
    }

    /** Soft delete: the seat is freed and the student's login disabled; the number stays reserved. */
    public void deleteStudent(String studentId, Done done) {
        Student s = StudentRepository.getInstance().findById(studentId);
        String who = s == null ? "a student" : s.name + " (" + s.number + ")";
        submit(PendingOp.create(Session.accountId(), PendingOp.DELETE_STUDENT, studentId, null, "Remove " + who), true, done);
    }

    /** Approve an open request. A group move is re-checked against the 15-seat limit when it reaches the server. */
    public void approve(String kind, int requestId, Done done) {
        answer(kind, requestId, PendingOp.APPROVE, done);
    }

    public void decline(String kind, int requestId, Done done) {
        answer(kind, requestId, PendingOp.DECLINE, done);
    }

    private void answer(String kind, int requestId, String type, Done done) {
        Student s = findByRequest(kind, requestId);
        JSONObject meta = new JSONObject();
        try {
            if (s != null) meta.put("studentId", s.id);
        } catch (JSONException ignored) {
            // cannot happen
        }
        boolean group = StudentRepository.RequestRecord.GROUP.equals(kind);
        String verb = PendingOp.APPROVE.equals(type) ? "Approve" : "Decline";
        String what = s == null ? "a request"
                : group ? s.name + " → " + s.pendingGroup : s.name + " → number " + s.pendingNumber;
        PendingOp op = PendingOp.create(Session.accountId(), type, String.valueOf(requestId), meta, verb + " " + what);
        op.kind = kind;
        submit(op, true, done);
    }

    private static Student findByRequest(String kind, int requestId) {
        for (Student s : StudentRepository.getInstance().getActive()) {
            if (StudentRepository.RequestRecord.GROUP.equals(kind) ? s.pendingGroupRequestId == requestId
                    : s.pendingNumberRequestId == requestId) return s;
        }
        return null;
    }

    // =====================================================================
    // student
    // =====================================================================

    /** Uploads waiting changes, then loads the student's own record, latest requests, group totals and group members. */
    public void loadMine(Done done) {
        run(done, () -> refreshMine(Outcome.OK));
    }

    /** Name and programme only; the number goes through a correction request. */
    public void updateMe(String name, String programme, Done done) {
        try {
            JSONObject body = new JSONObject().put("fullName", name).put("programmeCode", programme);
            submit(PendingOp.create(Session.accountId(), PendingOp.UPDATE_ME, null, body,
                    "Name and programme: " + name + ", " + programme), false, done);
        } catch (JSONException e) {
            done.onResult(new Outcome(false, Outcome.SERVER, null));
        }
    }

    /** Ask to move to another group (or Unassigned). Nothing moves until a lecturer approves. */
    public void requestGroupChange(String group, Done done) {
        try {
            boolean none = StudentRepository.UNASSIGNED.equals(group);
            JSONObject body = new JSONObject().put("groupCode", none ? "UNASSIGNED" : group);
            submit(PendingOp.create(Session.accountId(), PendingOp.REQUEST_GROUP, null, body,
                    "Ask to move to " + (none ? "no group" : group)), false, done);
        } catch (JSONException e) {
            done.onResult(new Outcome(false, Outcome.SERVER, null));
        }
    }

    public void requestNumberCorrection(String number, String reason, Done done) {
        try {
            JSONObject body = new JSONObject().put("newNumber", number);
            if (reason != null && !reason.trim().isEmpty()) body.put("reason", reason.trim());
            submit(PendingOp.create(Session.accountId(), PendingOp.REQUEST_NUMBER, null, body,
                    "Ask to correct my number to " + number), false, done);
        } catch (JSONException e) {
            done.onResult(new Outcome(false, Outcome.SERVER, null));
        }
    }

    public void cancelGroupChange(Done done) {
        submit(PendingOp.create(Session.accountId(), PendingOp.CANCEL_GROUP, null, null,
                "Withdraw my group change request"), false, done);
    }

    public void cancelNumberCorrection(Done done) {
        submit(PendingOp.create(Session.accountId(), PendingOp.CANCEL_NUMBER, null, null,
                "Withdraw my number correction request"), false, done);
    }

    // =====================================================================
    // the outbox
    // =====================================================================

    /** "Sync now": uploads what is waiting, then reloads whatever the signed-in person sees. */
    public void syncNow(Done done) {
        run(done, () -> Session.isLecturer() ? refreshRoster(Outcome.OK) : refreshMine(Outcome.OK));
    }

    /** For SyncWorker: the same as Sync now, on the calling (worker) thread. */
    SyncReport syncNowBlocking() {
        SyncReport report = new SyncReport();
        String account = Session.accountId();
        if (account == null || !Session.isSignedIn()) return report;
        SyncEngine.Result flushed = flush();
        report.sent = flushed.sent;
        report.refused = flushed.refused.size();
        report.signedOut = flushed.signedOut;
        report.offline = flushed.offline;
        try {
            Step step = Session.isLecturer() ? refreshRoster(Outcome.OK) : refreshMine(Outcome.OK);
            if (step.apply != null) callbacks.execute(step.apply);
            if (step.outcome.is(Outcome.NETWORK)) report.offline = true;
        } catch (Exception e) {
            report.offline = true;
        }
        for (PendingOp op : store.ops(account)) if (op.isWaiting()) report.waiting++;
        return report;
    }

    /** The student's latest request of each kind, as last saved on the phone (for AnswerCheckWorker). */
    List<StudentRepository.RequestRecord> savedLatestRequests() {
        List<StudentRepository.RequestRecord> out = new ArrayList<>();
        try {
            JSONObject requests = parseObject(store.entry(Session.accountId(), "myRequests"));
            out.add(requestFrom(requests.optJSONObject("group")));
            out.add(requestFrom(requests.optJSONObject("number")));
        } catch (JSONException ignored) {
            // nothing usable saved
        }
        return out;
    }

    /** Removes a refused change from the list ("OK, got it" / "Use theirs"). */
    public void dismiss(String opId, Done done) {
        run(done, () -> {
            store.removeOp(opId);
            return Session.isLecturer() ? fromStoreRoster(Outcome.OK) : fromStoreMine(Outcome.OK);
        });
    }

    /**
     * "Keep mine" for a change refused because someone else changed the record first:
     * the same change is queued again on top of the server's current version, and uploaded.
     */
    public void keepMine(PendingOp refused, int currentVersion, Done done) {
        PendingOp again = PendingOp.create(refused.accountId, refused.type, refused.targetId, refused.bodyJson(), refused.label);
        again.kind = refused.kind;
        again.baseVersion = currentVersion;
        run(done, () -> {
            store.removeOp(refused.opId);
            return submitNow(again, Session.isLecturer());
        });
    }

    /** Sign-out: forgets this account's saved data and waiting changes on this phone. */
    public void forgetAccount(Runnable after) {
        String account = Session.accountId();
        background.execute(() -> {
            if (account != null) store.clearAccount(account);
            if (scheduler != null) scheduler.cancelUpload();
            callbacks.execute(after);
        });
    }

    // =====================================================================
    // how a change is made
    // =====================================================================

    private void submit(PendingOp op, boolean lecturer, Done done) {
        run(done, () -> submitNow(op, lecturer));
    }

    /** Saves the change on the phone, tries to upload everything waiting, then reloads. */
    private Step submitNow(PendingOp op, boolean lecturer) throws IOException, JSONException {
        if (op.accountId == null) return new Step(new Outcome(false, Outcome.UNAUTHORIZED, null), null);
        store.addOp(op);
        SyncEngine.Result flushed = flush();
        ApiClient.Response answer = flushed.answers.get(op.opId);
        Outcome mine;
        if (answer != null && answer.ok()) {
            mine = Outcome.OK;
        } else if (answer != null) {
            // Refused while the person is looking at it: they are told now, so it is not kept.
            store.removeOp(op.opId);
            mine = Outcome.of(answer);
        } else if (flushed.signedOut) {
            mine = new Outcome(false, Outcome.UNAUTHORIZED, null); // stays saved; uploads after signing in again
        } else {
            mine = Outcome.QUEUED; // no network, or behind an earlier waiting change
        }
        Step reloaded = lecturer ? refreshRoster(mine) : refreshMine(mine);
        // The change's own outcome matters more than how the reload went.
        return new Step(mine, reloaded.apply);
    }

    private SyncEngine.Result flush() {
        SyncEngine.Result r = engine.flush(Session.accountId(), token());
        if (r.waiting > 0 && scheduler != null && !r.signedOut) scheduler.scheduleUpload();
        return r;
    }

    // =====================================================================
    // reloading: server first, the phone's saved copy when the server can't be reached
    // =====================================================================

    /** Lecturer: uploads, then reloads everything the lecturer screens show and saves it on the phone. */
    private Step refreshRoster(Outcome first) throws JSONException {
        String account = Session.accountId();
        SyncEngine.Result flushed = flush();
        if (flushed.signedOut) return fromStoreRoster(new Outcome(false, Outcome.UNAUTHORIZED, null));
        try {
            List<String> students = new ArrayList<>();
            int page = 1;
            int pages;
            do {
                ApiClient.Response res = ApiClient.get("/students?limit=100&page=" + page, token());
                if (!res.ok()) return fromStoreRoster(first.ok ? Outcome.of(res) : first);
                JSONArray list = res.body.optJSONArray("students");
                if (list != null) for (int i = 0; i < list.length(); i++) students.add(list.getJSONObject(i).toString());
                pages = res.body.optInt("totalPages", 1);
                page++;
            } while (page <= pages);

            ApiClient.Response groups = ApiClient.get("/groups", token());
            if (!groups.ok()) return fromStoreRoster(first.ok ? Outcome.of(groups) : first);

            JSONArray pending = new JSONArray();
            for (String path : new String[]{"/group-requests", "/number-correction-requests"}) {
                ApiClient.Response res = ApiClient.get(path + "?status=PENDING", token());
                if (!res.ok()) return fromStoreRoster(first.ok ? Outcome.of(res) : first);
                JSONArray list = res.body.optJSONArray("requests");
                if (list != null) for (int i = 0; i < list.length(); i++) pending.put(list.get(i));
            }

            long now = now();
            store.replaceStudents(account, students, now);
            store.putEntry(account, "groups", arrayOrEmpty(groups.body.optJSONArray("groups")).toString(), now);
            store.putEntry(account, "pending", pending.toString(), now);
            store.putEntry(account, "lastSync", String.valueOf(now), now);
        } catch (IOException e) {
            return fromStoreRoster(first.queued ? first : Outcome.OFFLINE);
        }
        return fromStoreRoster(first);
    }

    /** Lecturer: what the phone has saved, with the waiting changes shown on top. */
    private Step fromStoreRoster(Outcome outcome) throws JSONException {
        String account = Session.accountId();
        List<Student> students = new ArrayList<>();
        for (String json : store.students(account)) students.add(studentFrom(new JSONObject(json)));
        List<StudentRepository.RequestRecord> pending = requestsFrom(parseArray(store.entry(account, "pending")));
        GroupTotals totals = groupsFrom(parseArray(store.entry(account, "groups")));
        List<PendingOp> ops = store.ops(account);
        long savedAt = store.entrySavedAt(account, "students");
        boolean saved = savedAt > 0;
        boolean offline = outcome.is(Outcome.NETWORK) || outcome.queued;
        return new Step(outcome, () -> {
            StudentRepository repo = StudentRepository.getInstance();
            repo.applyGroups(totals.codes, totals.counts);
            if (saved) repo.applyRoster(students, pending, ops);
            repo.applySyncState(ops, savedAt, offline);
        });
    }

    private Runnable answeredFromStore(String account) throws JSONException {
        List<StudentRepository.RequestRecord> records = requestsFrom(parseArray(store.entry(account, "answered")));
        if (store.entry(account, "answered") == null) return null;
        return () -> StudentRepository.getInstance().applyAnswered(records);
    }

    /** Student: uploads, then reloads their record, requests, group totals and group members, and saves them. */
    private Step refreshMine(Outcome first) throws JSONException {
        String account = Session.accountId();
        SyncEngine.Result flushed = flush();
        if (flushed.signedOut) return fromStoreMine(new Outcome(false, Outcome.UNAUTHORIZED, null));
        try {
            ApiClient.Response me = ApiClient.get("/students/me", token());
            if (!me.ok()) return fromStoreMine(first.ok ? Outcome.of(me) : first);
            ApiClient.Response requests = ApiClient.get("/my-requests", token());
            if (!requests.ok()) return fromStoreMine(first.ok ? Outcome.of(requests) : first);
            ApiClient.Response groups = ApiClient.get("/groups", token());
            if (!groups.ok()) return fromStoreMine(first.ok ? Outcome.of(groups) : first);
            ApiClient.Response mine = ApiClient.get("/groups/mine", token());
            if (!mine.ok()) return fromStoreMine(first.ok ? Outcome.of(mine) : first);

            long now = now();
            store.putEntry(account, "me", me.body.getJSONObject("student").toString(), now);
            store.putEntry(account, "myRequests", requests.body.toString(), now);
            store.putEntry(account, "groups", arrayOrEmpty(groups.body.optJSONArray("groups")).toString(), now);
            store.putEntry(account, "myGroup", mine.body.toString(), now);
            store.putEntry(account, "lastSync", String.valueOf(now), now);
        } catch (IOException e) {
            return fromStoreMine(first.queued ? first : Outcome.OFFLINE);
        }
        return fromStoreMine(first);
    }

    /** Student: what the phone has saved, with the waiting changes shown on top. */
    private Step fromStoreMine(Outcome outcome) throws JSONException {
        String account = Session.accountId();
        String meJson = store.entry(account, "me");
        Student self = meJson == null ? null : studentFrom(new JSONObject(meJson));
        if (self != null) self.email = Session.email();
        JSONObject requests = parseObject(store.entry(account, "myRequests"));
        StudentRepository.RequestRecord latestGroup = requestFrom(requests.optJSONObject("group"));
        StudentRepository.RequestRecord latestNumber = requestFrom(requests.optJSONObject("number"));
        GroupTotals totals = groupsFrom(parseArray(store.entry(account, "groups")));
        JSONObject mine = parseObject(store.entry(account, "myGroup"));
        String groupCode = text(mine, "groupCode");
        List<Student> members = new ArrayList<>();
        JSONArray list = mine.optJSONArray("members");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject m = list.getJSONObject(i);
                members.add(new Student(null, m.optString("fullName"), m.optString("studentNumber"), null,
                        groupCode == null ? StudentRepository.UNASSIGNED : groupCode, true));
            }
        }
        List<PendingOp> ops = store.ops(account);
        long savedAt = store.entrySavedAt(account, "me");
        boolean offline = outcome.is(Outcome.NETWORK) || outcome.queued;
        return new Step(outcome, () -> {
            StudentRepository repo = StudentRepository.getInstance();
            repo.applyGroups(totals.codes, totals.counts);
            repo.applyMine(self, latestGroup, latestNumber, members, ops);
            repo.applySyncState(ops, savedAt, offline);
        });
    }

    /** At app start: shows what was saved for the signed-in account before anything is loaded. */
    private void restoreSaved() {
        background.execute(() -> {
            if (!Session.isSignedIn() || Session.accountId() == null) return;
            try {
                Step step = Session.isLecturer() ? fromStoreRoster(Outcome.OK) : fromStoreMine(Outcome.OK);
                callbacks.execute(step.apply);
                for (PendingOp op : store.ops(Session.accountId())) {
                    if (op.isWaiting() && scheduler != null) {
                        scheduler.scheduleUpload();
                        break;
                    }
                }
            } catch (Exception ignored) {
                // nothing saved, or saved by an older version: the screens load from the server
            }
        });
    }

    // =====================================================================
    // shared
    // =====================================================================

    private static final class GroupTotals {
        final List<String> codes = new ArrayList<>();
        final Map<String, Integer> counts = new HashMap<>();
    }

    private static GroupTotals groupsFrom(JSONArray groups) throws JSONException {
        GroupTotals t = new GroupTotals();
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.getJSONObject(i);
            t.codes.add(g.getString("groupCode"));
            t.counts.put(g.getString("groupCode"), g.optInt("activeCount", 0));
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

    private static JSONArray parseArray(String json) throws JSONException {
        return json == null ? new JSONArray() : new JSONArray(json);
    }

    private static JSONObject parseObject(String json) throws JSONException {
        return json == null ? new JSONObject() : new JSONObject(json);
    }

    private static JSONArray arrayOrEmpty(JSONArray a) {
        return a == null ? new JSONArray() : a;
    }

    private static String join(List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) b.append(i == parts.size() - 1 ? " and " : ", ");
            b.append(parts.get(i));
        }
        return b.toString();
    }

    private static long now() {
        return System.currentTimeMillis();
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
                step = new Step(Outcome.OFFLINE, null);
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
