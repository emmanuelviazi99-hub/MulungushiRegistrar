package zm.ac.mulungushi.registrar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LocalStore kept in memory: used by the unit tests and the live check, which
 * run without a phone. Behaves like RoomLocalStore, but nothing survives the
 * process ending.
 */
public final class MemoryLocalStore implements LocalStore {

    private final Map<String, List<String>> students = new HashMap<>();
    private final Map<String, String> entries = new HashMap<>();
    private final Map<String, Long> savedAt = new HashMap<>();
    private final Map<String, PendingOp> ops = new HashMap<>();

    private static String key(String accountId, String name) {
        return accountId + "\n" + name;
    }

    @Override
    public synchronized void replaceStudents(String accountId, List<String> studentJson, long when) {
        students.put(accountId, new ArrayList<>(studentJson));
        savedAt.put(key(accountId, "students"), when);
    }

    @Override
    public synchronized List<String> students(String accountId) {
        List<String> list = students.get(accountId);
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    @Override
    public synchronized void putEntry(String accountId, String name, String json, long when) {
        entries.put(key(accountId, name), json);
        savedAt.put(key(accountId, name), when);
    }

    @Override
    public synchronized String entry(String accountId, String name) {
        return entries.get(key(accountId, name));
    }

    @Override
    public synchronized long entrySavedAt(String accountId, String name) {
        Long t = savedAt.get(key(accountId, name));
        return t == null ? 0 : t;
    }

    @Override
    public synchronized void addOp(PendingOp op) {
        long last = 0;
        for (PendingOp o : ops.values()) if (o.accountId.equals(op.accountId)) last = Math.max(last, o.seq);
        if (op.seq <= last) op.seq = last + 1;
        ops.put(op.opId, op.copy());
    }

    @Override
    public synchronized void updateOp(PendingOp op) {
        if (ops.containsKey(op.opId)) ops.put(op.opId, op.copy());
    }

    @Override
    public synchronized void removeOp(String opId) {
        ops.remove(opId);
    }

    @Override
    public synchronized List<PendingOp> ops(String accountId) {
        List<PendingOp> out = new ArrayList<>();
        for (PendingOp o : ops.values()) if (o.accountId.equals(accountId)) out.add(o.copy());
        out.sort(Comparator.comparingLong(o -> o.seq));
        return out;
    }

    @Override
    public synchronized void clearAccount(String accountId) {
        students.remove(accountId);
        entries.keySet().removeIf(k -> k.startsWith(accountId + "\n"));
        savedAt.keySet().removeIf(k -> k.startsWith(accountId + "\n"));
        ops.values().removeIf(o -> o.accountId.equals(accountId));
    }
}
