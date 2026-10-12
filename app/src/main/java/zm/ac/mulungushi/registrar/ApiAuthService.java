package zm.ac.mulungushi.registrar;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * AuthService backed by the Node.js backend (see API_CONTRACT.md in the backend):
 *
 *   sign in            POST /auth/login
 *   student sign-up    POST /auth/register/request  -> /register/verify -> /register/complete
 *   lecturer sign-up   POST /auth/staff/request     -> /staff/verify    -> /staff/complete
 *   forgot password    POST /auth/password/request  -> /password/verify -> /password/reset
 *
 * Network work runs on a background thread; answers come back on the main thread.
 * Every answer is decided by the server. If the server cannot be reached the result
 * is NETWORK_ERROR, never a pretend success.
 */
public final class ApiAuthService implements AuthService {

    private final Executor background;
    private final Executor callbacks;

    public ApiAuthService() {
        this(Executors.newSingleThreadExecutor(), new MainThread());
    }

    /** For tests: run the work and the callbacks on the given executors. */
    ApiAuthService(Executor background, Executor callbacks) {
        this.background = background;
        this.callbacks = callbacks;
    }

    // ---------------------------------------------------------------- sign in / out

    @Override
    public void signIn(String loginKey, String password, Callback callback) {
        call(callback, () -> {
            JSONObject body = new JSONObject()
                    .put("loginKey", loginKey.trim())
                    .put("password", password);
            return signedIn(ApiClient.post("/auth/login", body, null));
        });
    }

    @Override
    public void signOut() {
        // Tokens are not stored on the server, so forgetting ours is all it takes.
        Session.clear();
    }

    // ---------------------------------------------------------------- student sign-up

    @Override
    public void requestRegistrationCode(Registration d, Callback callback) {
        call(callback, () -> {
            JSONObject body = new JSONObject()
                    .put("fullName", d.name)
                    .put("studentNumber", d.number)
                    .put("nrc", d.nrc)
                    .put("dateOfBirth", d.dateOfBirth)
                    .put("email", d.email.trim())
                    .put("phone", d.phone);
            return interpret(ApiClient.post("/auth/register/request", body, null));
        });
    }

    @Override
    public void verifyRegistrationCode(String email, String code, Callback callback) {
        verify("/auth/register/verify", email, code, callback);
    }

    @Override
    public void completeRegistration(String email, String programme, String password, Callback callback) {
        call(callback, () -> {
            JSONObject body = new JSONObject()
                    .put("email", email.trim())
                    .put("password", password);
            if (programme != null && !programme.isEmpty()) body.put("programmeCode", programme);
            return signedIn(ApiClient.post("/auth/register/complete", body, null));
        });
    }

    // ---------------------------------------------------------------- lecturer sign-up

    @Override
    public void requestStaffCode(StaffRegistration d, Callback callback) {
        call(callback, () -> {
            JSONObject body = new JSONObject()
                    .put("fullName", d.name)
                    .put("email", d.email.trim())
                    .put("employeeId", d.employeeId.trim());
            if (d.phone != null && !d.phone.isEmpty()) body.put("phone", d.phone);
            return interpret(ApiClient.post("/auth/staff/request", body, null));
        });
    }

    @Override
    public void verifyStaffCode(String email, String code, Callback callback) {
        verify("/auth/staff/verify", email, code, callback);
    }

    @Override
    public void completeStaffRegistration(String email, String password, Callback callback) {
        call(callback, () -> {
            JSONObject body = new JSONObject()
                    .put("email", email.trim())
                    .put("password", password);
            return signedIn(ApiClient.post("/auth/staff/complete", body, null));
        });
    }

    // ---------------------------------------------------------------- forgotten password

    @Override
    public void requestResetCode(String email, Callback callback) {
        call(callback, () -> {
            JSONObject body = new JSONObject().put("email", email.trim());
            return interpret(ApiClient.post("/auth/password/request", body, null));
        });
    }

    @Override
    public void verifyResetCode(String email, String code, Callback callback) {
        verify("/auth/password/verify", email, code, callback);
    }

    @Override
    public void setNewPassword(String email, String code, String newPassword, Callback callback) {
        call(callback, () -> {
            JSONObject body = new JSONObject()
                    .put("email", email.trim())
                    .put("code", code)
                    .put("newPassword", newPassword);
            return interpret(ApiClient.post("/auth/password/reset", body, null));
        });
    }

    // ---------------------------------------------------------------- shared

    private void verify(String path, String email, String code, Callback callback) {
        call(callback, () -> {
            JSONObject body = new JSONObject()
                    .put("email", email.trim())
                    .put("code", code);
            return interpret(ApiClient.post(path, body, null));
        });
    }

    /** A successful sign-in or sign-up carries the token and profile: remember them. */
    private static Result signedIn(ApiClient.Response res) {
        Result r = interpret(res);
        if (r.ok()) {
            if (res.body.optString("token", "").isEmpty()) return new Result(Status.SERVER_ERROR, 0, 0, null);
            Session.save(res.body);
        }
        return r;
    }

    /** Turns the backend's answer into a Status. The error code decides, not the HTTP number. */
    static Result interpret(ApiClient.Response res) {
        if (res.ok()) return Result.of(Status.OK);
        String message = res.message();
        switch (res.error()) {
            case "NOT_ON_ROSTER":       return Result.of(Status.NOT_ON_ROSTER);
            case "ALREADY_REGISTERED":  return Result.of(Status.ALREADY_REGISTERED);
            case "EMAIL_IN_USE":        return Result.of(Status.EMAIL_IN_USE);
            case "BAD_CODE":            return new Result(Status.BAD_CODE, res.body.optInt("triesLeft", 0));
            case "TOO_MANY_TRIES":      return Result.of(Status.TOO_MANY_TRIES);
            case "CODE_EXPIRED":        return Result.of(Status.CODE_EXPIRED);
            case "NOT_VERIFIED":        return Result.of(Status.NOT_VERIFIED);
            case "INVALID_CREDENTIALS": return Result.of(Status.BAD_CREDENTIALS);
            case "RESEND_TOO_SOON":
                return new Result(Status.RESEND_TOO_SOON, 0, res.body.optInt("retryAfterSeconds", RESEND_SECONDS), message);
            case "DELIVERY_FAILED":     return Result.of(Status.DELIVERY_FAILED);
            case "RATE_LIMITED":        return Result.of(Status.RATE_LIMITED);
            case "VALIDATION_ERROR":
            case "INVALID_PROGRAMME":
                return new Result(Status.INVALID_DETAILS, 0, 0, message);
            default:
                // No (known) error code: fall back on the HTTP status.
                if (res.status == 401) return Result.of(Status.BAD_CREDENTIALS);
                if (res.status == 429) return Result.of(Status.RATE_LIMITED);
                return new Result(Status.SERVER_ERROR, 0, 0, message);
        }
    }

    private interface Work {
        Result run() throws IOException, JSONException;
    }

    private void call(Callback callback, Work work) {
        background.execute(() -> {
            Result result;
            try {
                result = work.run();
            } catch (IOException e) {
                result = Result.of(Status.NETWORK_ERROR);
            } catch (JSONException | RuntimeException e) {
                result = Result.of(Status.SERVER_ERROR);
            }
            final Result answer = result;
            callbacks.execute(() -> callback.onResult(answer));
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
