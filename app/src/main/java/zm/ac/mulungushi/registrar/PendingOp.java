package zm.ac.mulungushi.registrar;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * One change made on this phone, waiting to reach the server (the "outbox").
 *
 * It is saved on the phone the moment the person makes the change, so it
 * survives the app being closed or the phone restarting. SyncEngine sends the
 * waiting changes in the order they were made. {@link #opId} is sent as the
 * Idempotency-Key header on every try, so the server applies each change once
 * even when an answer was lost and the change is sent again.
 *
 * A change the server refuses (group full, number taken, someone else changed
 * the record first) is kept with state REFUSED and the server's reason, so the
 * Sync screen can show it until the person deals with it.
 */
public final class PendingOp {

    // ---- what kind of change ----
    public static final String ADD_STUDENT = "ADD_STUDENT";       // lecturer: POST /students
    public static final String SAVE_STUDENT = "SAVE_STUDENT";     // lecturer: PUT /students/:id
    public static final String DELETE_STUDENT = "DELETE_STUDENT"; // lecturer: DELETE /students/:id
    public static final String APPROVE = "APPROVE";               // lecturer: PUT /…-requests/:id/approve
    public static final String DECLINE = "DECLINE";               // lecturer: PUT /…-requests/:id/decline
    public static final String UPDATE_ME = "UPDATE_ME";           // student: PUT /students/me
    public static final String REQUEST_GROUP = "REQUEST_GROUP";   // student: POST /group-requests
    public static final String REQUEST_NUMBER = "REQUEST_NUMBER"; // student: POST /number-correction-requests
    public static final String CANCEL_GROUP = "CANCEL_GROUP";     // student: DELETE /my-requests/group
    public static final String CANCEL_NUMBER = "CANCEL_NUMBER";   // student: DELETE /my-requests/number

    public static final String WAITING = "WAITING";
    public static final String REFUSED = "REFUSED";

    /** A student added on this phone has no server id yet; until it has, it goes by "local-<uuid>". */
    public static final String LOCAL_ID_PREFIX = "local-";

    public String opId;        // UUID, also the Idempotency-Key
    public String accountId;   // whose change it is
    public long seq;           // order of making (milliseconds, made unique by the store)
    public String type;
    public String targetId;    // studentId (or a local- id), or the request id for APPROVE / DECLINE
    public String kind;        // APPROVE / DECLINE: "group" or "number"
    public String body;        // JSON text sent to the server (SAVE_STUDENT: without the version)
    public int baseVersion;    // SAVE_STUDENT: the record's version the edit was made on
    public String label;       // what the person did, in words: "Move Natasha Banda to G02"
    public long createdAt;
    public String state = WAITING;
    public String error;       // REFUSED: the server's error code, e.g. GROUP_FULL
    public String message;     // REFUSED: the server's message
    public int tries;          // attempts that did not get an answer

    public PendingOp() {}

    public static PendingOp create(String accountId, String type, String targetId, JSONObject body, String label) {
        PendingOp op = new PendingOp();
        op.opId = UUID.randomUUID().toString();
        op.accountId = accountId;
        op.type = type;
        op.targetId = targetId;
        op.body = body == null ? null : body.toString();
        op.label = label;
        op.createdAt = System.currentTimeMillis();
        op.seq = op.createdAt;
        return op;
    }

    public JSONObject bodyJson() {
        if (body == null) return new JSONObject();
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public boolean isWaiting() {
        return WAITING.equals(state);
    }

    public boolean isRefused() {
        return REFUSED.equals(state);
    }

    /** True when the server said someone else changed or removed the record first. */
    public boolean isConflict() {
        return isRefused() && ("VERSION_CONFLICT".equals(error) || "STUDENT_NOT_FOUND".equals(error)
                || "REQUEST_NOT_FOUND".equals(error));
    }

    static boolean isLocalId(String id) {
        return id != null && id.startsWith(LOCAL_ID_PREFIX);
    }

    public PendingOp copy() {
        PendingOp c = new PendingOp();
        c.opId = opId;
        c.accountId = accountId;
        c.seq = seq;
        c.type = type;
        c.targetId = targetId;
        c.kind = kind;
        c.body = body;
        c.baseVersion = baseVersion;
        c.label = label;
        c.createdAt = createdAt;
        c.state = state;
        c.error = error;
        c.message = message;
        c.tries = tries;
        return c;
    }
}
