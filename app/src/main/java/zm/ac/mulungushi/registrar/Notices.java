package zm.ac.mulungushi.registrar;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * Remembers which answered requests a student has already been told about
 * ("Your group change was approved"), so each answer is announced once on this
 * phone and not on every visit. Kept apart from Session, which is wiped at each
 * sign-in. Works in memory until {@link #init(Context)} has run (plain-Java tests).
 */
final class Notices {

    /** Answers older than this are not announced on a phone that never saw them. */
    private static final long FRESH_MS = 14L * 24 * 60 * 60 * 1000;

    private static SharedPreferences prefs;
    private static final Map<String, Integer> memory = new HashMap<>();

    private Notices() {}

    static synchronized void init(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("notices", Context.MODE_PRIVATE);
    }

    private static String key(String studentId, String kind) {
        return studentId + "." + kind;
    }

    private static synchronized int lastSeen(String key) {
        if (prefs != null) return prefs.getInt(key, 0);
        Integer v = memory.get(key);
        return v == null ? 0 : v;
    }

    static synchronized void markSeen(String studentId, String kind, int requestId) {
        String k = key(studentId, kind);
        if (requestId <= lastSeen(k)) return;
        if (prefs != null) prefs.edit().putInt(k, requestId).apply();
        else memory.put(k, requestId);
    }

    /** True if this answered request should be announced (and was not yet). Old ones are quietly marked seen. */
    static boolean isNew(String studentId, StudentRepository.RequestRecord r) {
        if (studentId == null || r.requestId <= lastSeen(key(studentId, r.kind))) return false;
        if (r.resolvedAtMillis > 0 && System.currentTimeMillis() - r.resolvedAtMillis > FRESH_MS) {
            markSeen(studentId, r.kind, r.requestId);
            return false;
        }
        return true;
    }
}
