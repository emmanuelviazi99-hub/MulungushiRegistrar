package zm.ac.mulungushi.registrar;

import androidx.annotation.StringRes;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;


/**
 * Holds what the screen needs to survive rotation: whether the user has tried to
 * submit, whether the number field has been left, the form-level error and whether
 * a sign-in is in progress. The typed text stays in the fields, and the password is
 * never kept here.
 *
 * Signing in goes through Auth.get() (ApiAuthService -> POST /api/auth/login). Only the
 * server can say yes: if it cannot be reached, the user is told so and stays signed out.
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
    private final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);

    public LiveData<UiState> getState() {
        return state;
    }

    public LiveData<Boolean> getBusy() {
        return busy;
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

        if (Boolean.TRUE.equals(busy.getValue())) return; // one sign-in at a time
        busy.setValue(true);
        Auth.get().signIn(identity.trim(), password, result -> {
            busy.setValue(false);
            UiState now = current();
            switch (result.status) {
                case OK:
                    signedIn.setValue(Session.isLecturer() ? Role.LECTURER : Role.STUDENT);
                    break;
                case BAD_CREDENTIALS:
                    state.setValue(new UiState(true, now.idTouched, R.string.error_bad_credentials));
                    break;
                case RATE_LIMITED:
                    state.setValue(new UiState(true, now.idTouched, R.string.error_rate_limited));
                    break;
                case NETWORK_ERROR:
                    state.setValue(new UiState(true, now.idTouched, R.string.error_network));
                    break;
                default:
                    state.setValue(new UiState(true, now.idTouched, R.string.error_server));
            }
        });
    }

    private UiState current() {
        UiState s = state.getValue();
        return s != null ? s : new UiState(false, false, 0);
    }
}
