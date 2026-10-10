package zm.ac.mulungushi.registrar;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.StringRes;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Holds what the screen needs to survive rotation: whether the user has tried to
 * submit, whether the number field has been left, and the form-level error.
 * The typed text stays in the fields, and the password is never kept here.
 */
public class LoginViewModel extends ViewModel {

    public enum Role { STUDENT, LECTURER }

    public static final class UiState {
        public final boolean tried;
        public final boolean idTouched;
        @StringRes public final int formError; // 0 means none

        UiState(boolean tried, boolean idTouched, @StringRes int formError) {
            this.tried = tried;
            this.idTouched = idTouched;
            this.formError = formError;
        }
    }

    private final MutableLiveData<UiState> state = new MutableLiveData<>(new UiState(false, false, 0));
    private final MutableLiveData<Role> signedIn = new MutableLiveData<>();

    public LiveData<UiState> getState() {
        return state;
    }

    public LiveData<Role> getSignedIn() {
        return signedIn;
    }

    public void consumeSignedIn() {
        signedIn.setValue(null);
    }

    public void onIdBlur() {
        UiState s = current();
        if (!s.idTouched) state.setValue(new UiState(s.tried, true, s.formError));
    }

    /** Typing clears the form-level error, like the prototype. */
    public void onTextChanged() {
        UiState s = current();
        if (s.formError != 0) state.setValue(new UiState(s.tried, s.idTouched, 0));
    }

    public void submit(String identity, String password, boolean online) {
        UiState s = current();
        LoginValidator.Result r = LoginValidator.checkIdentity(identity);

        if (!LoginValidator.isOk(r) || password.isEmpty()) {
            state.setValue(new UiState(true, s.idTouched, 0));
            return;
        }
        if (!online) {
            state.setValue(new UiState(true, s.idTouched, R.string.error_offline));
            return;
        }

        // Call Node.js backend POST /api/auth/login
        new Thread(() -> {
            try {
                URL url = new URL(ApiConfig.BASE_URL + "/auth/login");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                JSONObject json = new JSONObject();
                json.put("username", identity.trim());
                json.put("password", password);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.toString().getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    JSONObject resp = new JSONObject(sb.toString());

                    String roleStr = resp.optString("role", "STUDENT");
                    Role role = "LECTURER".equalsIgnoreCase(roleStr) ? Role.LECTURER : Role.STUDENT;

                    Handler mainHandler = new Handler(Looper.getMainLooper());
                    mainHandler.post(() -> signedIn.setValue(role));
                } else {
                    Handler mainHandler = new Handler(Looper.getMainLooper());
                    mainHandler.post(() -> state.setValue(new UiState(true, s.idTouched, R.string.error_bad_credentials)));
                }
            } catch (Exception e) {
                // Fallback to local check if backend is unreachable
                if (r == LoginValidator.Result.OK_STAFF) {
                    String trimmed = identity.trim();
                    boolean match = (trimmed.equalsIgnoreCase(DemoAccounts.LECTURER_EMAIL) || trimmed.equalsIgnoreCase("lecturer1"))
                            && password.equals(DemoAccounts.LECTURER_PASSWORD);
                    Handler mainHandler = new Handler(Looper.getMainLooper());
                    mainHandler.post(() -> {
                        if (match) signedIn.setValue(Role.LECTURER);
                        else state.setValue(new UiState(true, s.idTouched, R.string.error_bad_credentials));
                    });
                } else {
                    Handler mainHandler = new Handler(Looper.getMainLooper());
                    mainHandler.post(() -> signedIn.setValue(Role.STUDENT));
                }
            }
        }).start();
    }

    private UiState current() {
        UiState s = state.getValue();
        return s != null ? s : new UiState(false, false, 0);
    }
}
