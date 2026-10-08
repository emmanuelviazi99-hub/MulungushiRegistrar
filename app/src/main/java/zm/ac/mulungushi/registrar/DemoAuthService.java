package zm.ac.mulungushi.registrar;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Demo stand-in for the server. It keeps everything in memory and "sends" the
 * same code every time: DemoAccounts.CLAIM_CODE. No email is really sent. It has
 * the same rules as the real server should have (10 minute expiry, 5 tries,
 * one verified code per account), so the screens behave the way they will later.
 *
 * Demo shortcuts, all of which belong on the server in the real build:
 *  - the "university list" accepts any 9-digit student number that is not taken
 *  - passwords are not stored (the demo sign-in accepts any student password)
 */
final class DemoAuthService implements AuthService {

    private static final long LATENCY_MS = 700; // so the loading state is visible

    private static final class Pending {
        final String code;
        final long expiresAt;
        int tries;
        boolean verified;

        Pending(String code, long expiresAt) {
            this.code = code;
            this.expiresAt = expiresAt;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Pending> registration = new HashMap<>();
    private final Map<String, Pending> reset = new HashMap<>();

    // ---------- register ----------

    @Override
    public void requestRegistrationCode(Registration d, Callback cb) {
        later(cb, () -> {
            StudentRepository repo = StudentRepository.getInstance();
            if (repo.findOwnerOfNumber(d.number, "") != null) return Result.of(Status.ALREADY_REGISTERED);
            if (repo.findByEmail(d.email) != null) return Result.of(Status.EMAIL_IN_USE);
            registration.put(key(d.email), newPending());
            return Result.of(Status.OK);
        });
    }

    @Override
    public void verifyRegistrationCode(String email, String code, Callback cb) {
        later(cb, () -> check(registration, email, code));
    }

    @Override
    public void completeRegistration(String email, String programme, String password, Callback cb) {
        later(cb, () -> {
            Pending p = registration.get(key(email));
            if (p == null || !p.verified) return Result.of(Status.NOT_VERIFIED);
            registration.remove(key(email));
            return Result.of(Status.OK);
        });
    }

    // ---------- forgot password ----------

    @Override
    public void requestResetCode(String email, Callback cb) {
        later(cb, () -> {
            // only a registered email gets a code, but the answer is always OK
            if (isKnownEmail(email)) reset.put(key(email), newPending());
            return Result.of(Status.OK);
        });
    }

    @Override
    public void verifyResetCode(String email, String code, Callback cb) {
        later(cb, () -> check(reset, email, code));
    }

    @Override
    public void setNewPassword(String email, String code, String newPassword, Callback cb) {
        later(cb, () -> {
            Pending p = reset.get(key(email));
            if (p == null || !p.verified || !p.code.equalsIgnoreCase(code)) return Result.of(Status.NOT_VERIFIED);
            reset.remove(key(email));
            return Result.of(Status.OK);
        });
    }

    // ---------- helpers ----------

    private static boolean isKnownEmail(String email) {
        return DemoAccounts.LECTURER_EMAIL.equalsIgnoreCase(email == null ? "" : email.trim())
                || StudentRepository.getInstance().findByEmail(email) != null;
    }

    private static Pending newPending() {
        long ttl = AuthService.CODE_VALID_MINUTES * 60L * 1000L;
        return new Pending(DemoAccounts.CLAIM_CODE, SystemClock.elapsedRealtime() + ttl);
    }

    private static String key(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Wrong code, expired, locked: the same rules for register and reset. */
    private static Result check(Map<String, Pending> store, String email, String code) {
        Pending p = store.get(key(email));
        if (p == null) {
            // for reset this is also what an unknown email sees, so it looks like a wrong code
            return new Result(Status.BAD_CODE, AuthService.MAX_CODE_TRIES - 1);
        }
        if (SystemClock.elapsedRealtime() > p.expiresAt) {
            store.remove(key(email));
            return Result.of(Status.CODE_EXPIRED);
        }
        if (p.tries >= AuthService.MAX_CODE_TRIES) return Result.of(Status.TOO_MANY_TRIES);
        if (p.code.equalsIgnoreCase(code)) {
            p.verified = true;
            return Result.of(Status.OK);
        }
        p.tries++;
        int left = AuthService.MAX_CODE_TRIES - p.tries;
        return left <= 0 ? Result.of(Status.TOO_MANY_TRIES) : new Result(Status.BAD_CODE, left);
    }

    private interface Work {
        Result run();
    }

    private void later(Callback cb, Work work) {
        main.postDelayed(() -> cb.onResult(work.run()), LATENCY_MS);
    }
}
