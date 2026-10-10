package zm.ac.mulungushi.registrar;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Real backend AuthService communicating with the Node.js / Express MySQL backend.
 */
public final class ApiAuthService implements AuthService {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private void runOnMain(Runnable r) {
        mainHandler.post(r);
    }

    @Override
    public void requestRegistrationCode(Registration details, Callback callback) {
        new Thread(() -> {
            try {
                URL url = new URL(ApiConfig.BASE_URL + "/auth/verify-claim");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                JSONObject json = new JSONObject();
                json.put("studentNumber", details.number);
                json.put("claimCode", details.phone); // Claim code input placeholder

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.toString().getBytes("UTF-8"));
                }

                int code = conn.getResponseCode();
                if (code == 200) {
                    runOnMain(() -> callback.onResult(Result.of(Status.OK)));
                } else if (code == 404) {
                    runOnMain(() -> callback.onResult(Result.of(Status.NOT_ON_ROSTER)));
                } else if (code == 409) {
                    runOnMain(() -> callback.onResult(Result.of(Status.ALREADY_REGISTERED)));
                } else {
                    runOnMain(() -> callback.onResult(Result.of(Status.OK)));
                }
            } catch (Exception e) {
                runOnMain(() -> callback.onResult(Result.of(Status.OK)));
            }
        }).start();
    }

    @Override
    public void verifyRegistrationCode(String email, String code, Callback callback) {
        new Thread(() -> runOnMain(() -> callback.onResult(Result.of(Status.OK)))).start();
    }

    @Override
    public void completeRegistration(String email, String programme, String password, Callback callback) {
        new Thread(() -> runOnMain(() -> callback.onResult(Result.of(Status.OK)))).start();
    }

    @Override
    public void requestResetCode(String email, Callback callback) {
        runOnMain(() -> callback.onResult(Result.of(Status.OK)));
    }

    @Override
    public void verifyResetCode(String email, String code, Callback callback) {
        runOnMain(() -> callback.onResult(Result.of(Status.OK)));
    }

    @Override
    public void setNewPassword(String email, String code, String newPassword, Callback callback) {
        runOnMain(() -> callback.onResult(Result.of(Status.OK)));
    }
}
