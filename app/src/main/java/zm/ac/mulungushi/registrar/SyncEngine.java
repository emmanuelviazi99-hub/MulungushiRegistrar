package zm.ac.mulungushi.registrar;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Uploads the outbox: the changes made on this phone (PendingOp), oldest first,
 * each to the same endpoint the screens would use online, with its opId as the
 * Idempotency-Key. Plain Java, so it is tested without a phone.
 *
 * For each change the server answers:
 *   - yes (2xx)                     -> removed from the outbox
 *   - a refusal (4xx: GROUP_FULL,   -> kept, marked REFUSED with the server's reason,
 *     VERSION_CONFLICT, …)             and the next change is tried
 *   - nothing (no network), 5xx,     -> stop; everything stays waiting for the next try
 *     429, "still processing"           (in the same order)
 *   - 401 (sign-in expired)          -> stop; waits until the person signs in again
 *
 * Changes that depend on each other stay correct:
 *   - a student added offline has a "local-…" id; once the server has created them,
 *     later changes to that student use the real id;
 *   - two edits of one student made offline: the second is sent with the version
 *     the server gave the first, so it is not mistaken for someone else's change.
 *
 * Only one flush runs at a time (the app's background thread and WorkManager share
 * one engine), so a change is never sent twice at the same moment.
 */
public final class SyncEngine {

    /** Sends one request. On the phone this is ApiClient.send. */
    public interface Sender {
        ApiClient.Response send(String method, String path, JSONObject body, String token, String idempotencyKey)
                throws IOException, JSONException;
    }

    /** What a flush did. */
    public static final class Result {
        public int sent;
        public boolean offline;      // the server could not be reached (or asked us to come back later)
        public boolean signedOut;    // 401: the person must sign in again before anything uploads
        /** Each change the server answered, by opId: its answer. */
        public final Map<String, ApiClient.Response> answers = new HashMap<>();
        /** Changes refused in this flush. */
        public final List<PendingOp> refused = new ArrayList<>();
        /** Changes still waiting after this flush. */
        public int waiting;
    }

    private final LocalStore store;
    private final Sender sender;

    public SyncEngine(LocalStore store, Sender sender) {
        this.store = store;
        this.sender = sender;
    }

    public SyncEngine(LocalStore store) {
        this(store, ApiClient::send);
    }

    public synchronized Result flush(String accountId, String token) {
        Result result = new Result();
        if (accountId == null) return result;
        List<PendingOp> queue = store.ops(accountId);
        for (int i = 0; i < queue.size(); i++) {
            PendingOp op = queue.get(i);
            if (!op.isWaiting()) continue;

            ApiClient.Response res;
            try {
                res = send(op, token);
            } catch (IOException e) {
                result.offline = true;
                markTried(op);
                break;
            } catch (JSONException e) {
                res = new ApiClient.Response(400, errorBody("BAD_CHANGE", null));
            }

            if (res.ok()) {
                result.answers.put(op.opId, res);
                result.sent++;
                store.removeOp(op.opId);
                carryForward(op, res, queue, i);
                continue;
            }
            if (res.status == 401) {
                result.signedOut = true;
                break;
            }
            String error = res.error();
            if (res.status >= 500 || res.status == 429 || error.isEmpty() || "CONFLICT".equals(error)) {
                // Not an answer about the change itself: try again later, in the same order.
                result.offline = true;
                markTried(op);
                break;
            }
            result.answers.put(op.opId, res);
            refuse(op, error, res.message(), result);
            if (PendingOp.ADD_STUDENT.equals(op.type)) {
                // Nothing else can be done to a student who was never added.
                for (int j = i + 1; j < queue.size(); j++) {
                    PendingOp later = queue.get(j);
                    if (later.isWaiting() && op.targetId.equals(later.targetId)) {
                        refuse(later, "NOT_ADDED", "The student was not added, so this change could not be made.", result);
                    }
                }
            }
        }
        for (PendingOp o : store.ops(accountId)) if (o.isWaiting()) result.waiting++;
        return result;
    }

    private void refuse(PendingOp op, String error, String message, Result result) {
        op.state = PendingOp.REFUSED;
        op.error = error;
        op.message = message;
        store.updateOp(op);
        result.refused.add(op.copy());
    }

    private void markTried(PendingOp op) {
        op.tries++;
        store.updateOp(op);
    }

    /** After a change worked: later changes to the same student use the server's id and version. */
    private void carryForward(PendingOp done, ApiClient.Response res, List<PendingOp> queue, int index) {
        JSONObject student = res.body.optJSONObject("student");
        if (student == null) return;
        String serverId = student.optString("studentId", null);
        int version = student.optInt("version", 0);
        for (int j = index + 1; j < queue.size(); j++) {
            PendingOp later = queue.get(j);
            if (!later.isWaiting() || later.targetId == null || !later.targetId.equals(done.targetId)) continue;
            boolean changed = false;
            if (PendingOp.isLocalId(later.targetId) && serverId != null) {
                later.targetId = serverId;
                changed = true;
            }
            if (PendingOp.SAVE_STUDENT.equals(later.type) && version > 0) {
                later.baseVersion = version;
                changed = true;
            }
            if (changed) store.updateOp(later);
        }
        if (PendingOp.isLocalId(done.targetId) && serverId != null) done.targetId = serverId;
    }

    private ApiClient.Response send(PendingOp op, String token) throws IOException, JSONException {
        JSONObject body = op.bodyJson();
        switch (op.type) {
            case PendingOp.ADD_STUDENT:
                return sender.send("POST", "/students", body, token, op.opId);
            case PendingOp.SAVE_STUDENT:
                if (op.baseVersion > 0) body.put("version", op.baseVersion);
                return sender.send("PUT", "/students/" + op.targetId, body, token, op.opId);
            case PendingOp.DELETE_STUDENT:
                return sender.send("DELETE", "/students/" + op.targetId, null, token, op.opId);
            case PendingOp.APPROVE:
            case PendingOp.DECLINE: {
                String base = StudentRepository.RequestRecord.GROUP.equals(op.kind)
                        ? "/group-requests/" : "/number-correction-requests/";
                String verb = PendingOp.APPROVE.equals(op.type) ? "approve" : "decline";
                return sender.send("PUT", base + op.targetId + "/" + verb, new JSONObject(), token, op.opId);
            }
            case PendingOp.UPDATE_ME:
                return sender.send("PUT", "/students/me", body, token, op.opId);
            case PendingOp.REQUEST_GROUP:
                return sender.send("POST", "/group-requests", body, token, op.opId);
            case PendingOp.REQUEST_NUMBER:
                return sender.send("POST", "/number-correction-requests", body, token, op.opId);
            case PendingOp.CANCEL_GROUP:
                return sender.send("DELETE", "/my-requests/group", null, token, op.opId);
            case PendingOp.CANCEL_NUMBER:
                return sender.send("DELETE", "/my-requests/number", null, token, op.opId);
            default:
                return new ApiClient.Response(400, errorBody("BAD_CHANGE",
                        "Unknown change " + op.type.toLowerCase(Locale.ROOT)));
        }
    }

    private static JSONObject errorBody(String code, String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("success", false).put("error", code);
            if (message != null) o.put("message", message);
        } catch (JSONException ignored) {
            // cannot happen with these values
        }
        return o;
    }
}
