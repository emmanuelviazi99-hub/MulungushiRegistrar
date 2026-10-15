package zm.ac.mulungushi.registrar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The phone's outbox (PendingOp + SyncEngine) and how waiting changes are shown
 * (Overlay), against a fake server. The live check runs the same code against
 * the real backend.
 */
public class OfflineSyncTest {

    private static final String ME = "7";

    /** A fake server: answers from a script, records what it was sent. */
    private static final class FakeServer implements SyncEngine.Sender {
        final List<String> sent = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        final Map<String, Integer> statusFor = new HashMap<>();
        final Map<String, String> errorFor = new HashMap<>();
        boolean offline;
        int nextVersion = 5;

        @Override
        public ApiClient.Response send(String method, String path, JSONObject body, String token, String key)
                throws IOException, JSONException {
            if (offline) throw new IOException("no network");
            String line = method + " " + path + (body == null ? "" : " " + body);
            sent.add(line);
            keys.add(key);
            String route = method + " " + path;
            if (statusFor.containsKey(route)) {
                return new ApiClient.Response(statusFor.get(route),
                        new JSONObject().put("success", false).put("error", errorFor.get(route)).put("message", "no"));
            }
            JSONObject ok = new JSONObject().put("success", true);
            if (path.startsWith("/students") && !"DELETE".equals(method)) {
                String id = "POST".equals(method) ? "server-id-1" : path.substring("/students/".length());
                ok.put("student", new JSONObject().put("studentId", id).put("version", nextVersion++));
            }
            return new ApiClient.Response("POST".equals(method) ? 201 : 200, ok);
        }
    }

    private static PendingOp op(String type, String target, String body) throws JSONException {
        return PendingOp.create(ME, type, target, body == null ? null : new JSONObject(body), type);
    }

    @Test public void changesGoUpInOrderEachWithItsOwnKeyAndLeaveTheOutbox() throws Exception {
        MemoryLocalStore store = new MemoryLocalStore();
        FakeServer server = new FakeServer();
        store.addOp(op(PendingOp.REQUEST_GROUP, null, "{\"groupCode\":\"G02\"}"));
        store.addOp(op(PendingOp.UPDATE_ME, null, "{\"fullName\":\"Ann Phiri\",\"programmeCode\":\"CS\"}"));
        SyncEngine.Result r = new SyncEngine(store, server).flush(ME, "t");
        assertEquals(2, r.sent);
        assertEquals(0, r.waiting);
        assertTrue(server.sent.get(0).startsWith("POST /group-requests"));
        assertTrue(server.sent.get(1).startsWith("PUT /students/me"));
        assertFalse(server.keys.get(0).equals(server.keys.get(1)));
        assertTrue(store.ops(ME).isEmpty());
    }

    @Test public void noNetworkKeepsEverythingWaitingAndTheRetryReusesTheSameKey() throws Exception {
        MemoryLocalStore store = new MemoryLocalStore();
        FakeServer server = new FakeServer();
        PendingOp first = op(PendingOp.REQUEST_GROUP, null, "{\"groupCode\":\"G02\"}");
        store.addOp(first);
        store.addOp(op(PendingOp.CANCEL_NUMBER, null, null));
        server.offline = true;
        SyncEngine engine = new SyncEngine(store, server);
        SyncEngine.Result r = engine.flush(ME, "t");
        assertTrue(r.offline);
        assertEquals(2, r.waiting);
        assertEquals(1, store.ops(ME).get(0).tries);

        server.offline = false;
        r = engine.flush(ME, "t");
        assertEquals(2, r.sent);
        assertEquals(first.opId, server.keys.get(0)); // same Idempotency-Key as the failed try
    }

    @Test public void aRefusalIsKeptWithTheReasonAndTheNextChangeStillGoes() throws Exception {
        MemoryLocalStore store = new MemoryLocalStore();
        FakeServer server = new FakeServer();
        server.statusFor.put("PUT /students/abc", 409);
        server.errorFor.put("PUT /students/abc", "GROUP_FULL");
        PendingOp full = op(PendingOp.SAVE_STUDENT, "abc", "{\"groupCode\":\"G01\"}");
        store.addOp(full);
        store.addOp(op(PendingOp.DELETE_STUDENT, "def", null));
        SyncEngine.Result r = new SyncEngine(store, server).flush(ME, "t");
        assertEquals(1, r.sent);
        assertEquals(1, r.refused.size());
        List<PendingOp> left = store.ops(ME);
        assertEquals(1, left.size());
        assertEquals(PendingOp.REFUSED, left.get(0).state);
        assertEquals("GROUP_FULL", left.get(0).error);
        assertEquals(0, r.waiting);
        // A refused change is never sent again by itself.
        new SyncEngine(store, server).flush(ME, "t");
        assertEquals(2, server.sent.size());
    }

    @Test public void serverTroubleOrSignInExpiryStopsAndKeepsTheOrder() throws Exception {
        for (int status : new int[]{500, 503, 429, 401}) {
            MemoryLocalStore store = new MemoryLocalStore();
            FakeServer server = new FakeServer();
            server.statusFor.put("POST /group-requests", status);
            server.errorFor.put("POST /group-requests", status == 401 ? "UNAUTHORIZED" : "INTERNAL_ERROR");
            store.addOp(op(PendingOp.REQUEST_GROUP, null, "{\"groupCode\":\"G02\"}"));
            store.addOp(op(PendingOp.UPDATE_ME, null, "{\"fullName\":\"Ann\"}"));
            SyncEngine.Result r = new SyncEngine(store, server).flush(ME, "t");
            assertEquals("status " + status, 1, server.sent.size()); // the second was not sent out of order
            assertEquals(2, r.waiting);
            assertEquals(status == 401, r.signedOut);
            for (PendingOp o : store.ops(ME)) assertTrue(o.isWaiting());
        }
    }

    @Test public void aStudentAddedOfflineThenEditedGetsTheServerIdAndVersion() throws Exception {
        MemoryLocalStore store = new MemoryLocalStore();
        FakeServer server = new FakeServer();
        String local = PendingOp.LOCAL_ID_PREFIX + "1";
        store.addOp(op(PendingOp.ADD_STUDENT, local, "{\"fullName\":\"New One\",\"studentNumber\":\"202399001\"}"));
        PendingOp edit1 = op(PendingOp.SAVE_STUDENT, local, "{\"fullName\":\"New Two\"}");
        store.addOp(edit1);
        store.addOp(op(PendingOp.SAVE_STUDENT, local, "{\"groupCode\":\"G02\"}"));
        new SyncEngine(store, server).flush(ME, "t");
        assertEquals(3, server.sent.size());
        // The server created the student as server-id-1 (version 5), the first edit made version 6.
        assertTrue(server.sent.get(1).startsWith("PUT /students/server-id-1"));
        assertTrue(server.sent.get(1).contains("\"version\":5"));
        assertTrue(server.sent.get(2).startsWith("PUT /students/server-id-1"));
        assertTrue(server.sent.get(2).contains("\"version\":6"));
    }

    @Test public void ifTheAddIsRefusedLaterChangesToThatStudentAreToo() throws Exception {
        MemoryLocalStore store = new MemoryLocalStore();
        FakeServer server = new FakeServer();
        server.statusFor.put("POST /students", 409);
        server.errorFor.put("POST /students", "DUPLICATE_STUDENT_NUMBER");
        String local = PendingOp.LOCAL_ID_PREFIX + "2";
        store.addOp(op(PendingOp.ADD_STUDENT, local, "{\"fullName\":\"Dup\"}"));
        store.addOp(op(PendingOp.SAVE_STUDENT, local, "{\"fullName\":\"Dup Two\"}"));
        SyncEngine.Result r = new SyncEngine(store, server).flush(ME, "t");
        assertEquals(1, server.sent.size()); // the edit of a student who does not exist is not sent
        assertEquals(2, r.refused.size());
        assertEquals("NOT_ADDED", store.ops(ME).get(1).error);
    }

    @Test public void eachAccountOnlyUploadsItsOwnChangesAndSignOutForgetsThem() throws Exception {
        MemoryLocalStore store = new MemoryLocalStore();
        FakeServer server = new FakeServer();
        store.addOp(op(PendingOp.UPDATE_ME, null, "{\"fullName\":\"Mine\"}"));
        PendingOp theirs = PendingOp.create("8", PendingOp.UPDATE_ME, null, new JSONObject("{\"fullName\":\"Theirs\"}"), "x");
        store.addOp(theirs);
        new SyncEngine(store, server).flush(ME, "t");
        assertEquals(1, server.sent.size());
        assertTrue(server.sent.get(0).contains("Mine"));
        store.putEntry("8", "me", "{}", 1);
        store.clearAccount("8");
        assertTrue(store.ops("8").isEmpty());
        assertNull(store.entry("8", "me"));
    }

    @Test public void changesMadeInTheSameMillisecondKeepTheirOrder() throws Exception {
        MemoryLocalStore store = new MemoryLocalStore();
        PendingOp a = op(PendingOp.UPDATE_ME, null, "{}");
        PendingOp b = op(PendingOp.CANCEL_GROUP, null, null);
        b.seq = a.seq; // same clock tick
        store.addOp(a);
        store.addOp(b);
        List<PendingOp> ops = store.ops(ME);
        assertEquals(a.opId, ops.get(0).opId);
        assertEquals(b.opId, ops.get(1).opId);
    }

    // ---- what the screens show ----

    private static List<Student> roster() {
        List<Student> list = new ArrayList<>();
        Student ann = new Student("a", "Ann Banda", "202301001", "CS", "G01", true);
        ann.pendingGroup = "G03";
        ann.pendingGroupRequestId = 11;
        list.add(ann);
        list.add(new Student("b", "Ben Mulenga", "202301002", "IT", "G02", true));
        return list;
    }

    private static Map<String, Integer> counts() {
        Map<String, Integer> c = new HashMap<>();
        c.put("G01", 14);
        c.put("G02", 3);
        c.put("G03", 5);
        return c;
    }

    @Test public void theLecturerSeesTheirWaitingChangesMarkedAndTheTotalsFollow() throws Exception {
        List<Student> list = roster();
        Map<String, Integer> c = counts();
        List<PendingOp> ops = new ArrayList<>();
        ops.add(op(PendingOp.SAVE_STUDENT, "b", "{\"groupCode\":\"G03\",\"fullName\":\"Ben K. Mulenga\"}"));
        ops.add(op(PendingOp.ADD_STUDENT, "local-9", "{\"fullName\":\"Cee\",\"studentNumber\":\"202399009\",\"programmeCode\":\"DS\"}"));
        Overlay.applyToRoster(list, c, ops);
        Student ben = list.get(1);
        assertEquals("G03", ben.group);
        assertEquals("Ben K. Mulenga", ben.name);
        assertTrue(ben.unsynced);
        assertFalse(list.get(0).unsynced);
        assertEquals(3, list.size());
        assertEquals(StudentRepository.UNASSIGNED, list.get(2).group);
        assertTrue(list.get(2).unsynced);
        assertEquals(2, (int) c.get("G02"));
        assertEquals(6, (int) c.get("G03"));
    }

    @Test public void approvingOfflineMovesTheStudentAndEmptiesTheQueue() throws Exception {
        List<Student> list = roster();
        Map<String, Integer> c = counts();
        PendingOp approve = op(PendingOp.APPROVE, "11", "{\"studentId\":\"a\"}");
        approve.kind = StudentRepository.RequestRecord.GROUP;
        List<PendingOp> ops = new ArrayList<>();
        ops.add(approve);
        Overlay.applyToRoster(list, c, ops);
        assertEquals("G03", list.get(0).group);
        assertNull(list.get(0).pendingGroup);
        assertEquals(13, (int) c.get("G01"));
        assertEquals(6, (int) c.get("G03"));
    }

    @Test public void deletingOfflineHidesTheStudentAndFreesTheSeat() throws Exception {
        List<Student> list = roster();
        Map<String, Integer> c = counts();
        List<PendingOp> ops = new ArrayList<>();
        ops.add(op(PendingOp.DELETE_STUDENT, "a", null));
        Overlay.applyToRoster(list, c, ops);
        assertEquals(1, list.size());
        assertEquals(13, (int) c.get("G01"));
    }

    @Test public void aRefusedChangeIsNotShownAsIfItHappened() throws Exception {
        List<Student> list = roster();
        PendingOp refused = op(PendingOp.SAVE_STUDENT, "b", "{\"groupCode\":\"G01\"}");
        refused.state = PendingOp.REFUSED;
        List<PendingOp> ops = new ArrayList<>();
        ops.add(refused);
        Overlay.applyToRoster(list, counts(), ops);
        assertEquals("G02", list.get(1).group);
        assertFalse(list.get(1).unsynced);
    }

    @Test public void theStudentSeesTheirOfflineRequestAndEdit() throws Exception {
        Student me = new Student("a", "Ann Banda", "202301001", "CS", "G01", true);
        List<PendingOp> ops = new ArrayList<>();
        ops.add(op(PendingOp.UPDATE_ME, null, "{\"fullName\":\"Ann M. Banda\",\"programmeCode\":\"IT\"}"));
        ops.add(op(PendingOp.REQUEST_GROUP, null, "{\"groupCode\":\"UNASSIGNED\"}"));
        ops.add(op(PendingOp.REQUEST_NUMBER, null, "{\"newNumber\":\"202301099\",\"reason\":\"Typo\"}"));
        Overlay.applyToMe(me, ops);
        assertEquals("Ann M. Banda", me.name);
        assertEquals("IT", me.programme);
        assertEquals(StudentRepository.UNASSIGNED, me.pendingGroup);
        assertEquals("202301099", me.pendingNumber);
        assertEquals("Typo", me.pendingNumberReason);
        assertTrue(me.unsynced);

        List<PendingOp> cancel = new ArrayList<>();
        cancel.add(op(PendingOp.CANCEL_GROUP, null, null));
        Overlay.applyToMe(me, cancel);
        assertNull(me.pendingGroup);
    }
}
