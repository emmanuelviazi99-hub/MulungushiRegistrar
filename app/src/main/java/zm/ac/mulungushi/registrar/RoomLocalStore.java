package zm.ac.mulungushi.registrar;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * LocalStore on the phone: the Room database (RegistrarDatabase). Writes that
 * belong together run in one transaction, so a crash half-way never leaves half
 * a roster or a gap in the order of queued changes. Call off the main thread.
 */
public final class RoomLocalStore implements LocalStore {

    private final RegistrarDatabase db;
    private final RegistrarDao dao;

    public RoomLocalStore(Context context) {
        db = RegistrarDatabase.get(context);
        dao = db.dao();
    }

    @Override
    public void replaceStudents(String accountId, List<String> studentJson, long savedAt) {
        List<CachedStudentEntity> rows = new ArrayList<>();
        for (int i = 0; i < studentJson.size(); i++) {
            CachedStudentEntity row = new CachedStudentEntity();
            row.accountId = accountId;
            row.position = i;
            row.json = studentJson.get(i);
            try {
                JSONObject o = new JSONObject(row.json);
                row.studentId = o.optString("studentId", "row-" + i);
                row.studentNumber = o.optString("studentNumber", null);
                row.fullName = o.optString("fullName", null);
                row.groupCode = o.isNull("groupCode") ? null : o.optString("groupCode", null);
            } catch (Exception e) {
                row.studentId = "row-" + i;
            }
            rows.add(row);
        }
        db.runInTransaction(() -> {
            dao.deleteStudents(accountId);
            dao.insertStudents(rows);
            CacheEntryEntity when = new CacheEntryEntity();
            when.accountId = accountId;
            when.name = "students";
            when.json = null;
            when.savedAt = savedAt;
            dao.putEntry(when);
        });
    }

    @Override
    public List<String> students(String accountId) {
        List<String> out = new ArrayList<>();
        for (CachedStudentEntity row : dao.students(accountId)) out.add(row.json);
        return out;
    }

    @Override
    public void putEntry(String accountId, String name, String json, long savedAt) {
        CacheEntryEntity e = new CacheEntryEntity();
        e.accountId = accountId;
        e.name = name;
        e.json = json;
        e.savedAt = savedAt;
        dao.putEntry(e);
    }

    @Override
    public String entry(String accountId, String name) {
        CacheEntryEntity e = dao.entry(accountId, name);
        return e == null ? null : e.json;
    }

    @Override
    public long entrySavedAt(String accountId, String name) {
        CacheEntryEntity e = dao.entry(accountId, name);
        return e == null ? 0 : e.savedAt;
    }

    @Override
    public void addOp(PendingOp op) {
        db.runInTransaction(() -> {
            Long last = dao.lastSeq(op.accountId);
            if (last != null && op.seq <= last) op.seq = last + 1;
            dao.putOp(toRow(op));
        });
    }

    @Override
    public void updateOp(PendingOp op) {
        db.runInTransaction(() -> {
            if (dao.countOp(op.opId) > 0) dao.putOp(toRow(op));
        });
    }

    @Override
    public void removeOp(String opId) {
        dao.removeOp(opId);
    }

    @Override
    public List<PendingOp> ops(String accountId) {
        List<PendingOp> out = new ArrayList<>();
        for (PendingOpEntity row : dao.ops(accountId)) out.add(fromRow(row));
        return out;
    }

    @Override
    public void clearAccount(String accountId) {
        db.runInTransaction(() -> {
            dao.deleteStudents(accountId);
            dao.deleteEntries(accountId);
            dao.deleteOps(accountId);
        });
    }

    // ---------------------------------------------------------------------

    private static PendingOpEntity toRow(PendingOp op) {
        PendingOpEntity r = new PendingOpEntity();
        r.opId = op.opId;
        r.accountId = op.accountId;
        r.seq = op.seq;
        r.type = op.type;
        r.targetId = op.targetId;
        r.kind = op.kind;
        r.body = op.body;
        r.baseVersion = op.baseVersion;
        r.label = op.label;
        r.createdAt = op.createdAt;
        r.state = op.state;
        r.error = op.error;
        r.message = op.message;
        r.tries = op.tries;
        return r;
    }

    private static PendingOp fromRow(PendingOpEntity r) {
        PendingOp op = new PendingOp();
        op.opId = r.opId;
        op.accountId = r.accountId;
        op.seq = r.seq;
        op.type = r.type;
        op.targetId = r.targetId;
        op.kind = r.kind;
        op.body = r.body;
        op.baseVersion = r.baseVersion;
        op.label = r.label;
        op.createdAt = r.createdAt;
        op.state = r.state == null ? PendingOp.WAITING : r.state;
        op.error = r.error;
        op.message = r.message;
        op.tries = r.tries;
        return op;
    }
}
