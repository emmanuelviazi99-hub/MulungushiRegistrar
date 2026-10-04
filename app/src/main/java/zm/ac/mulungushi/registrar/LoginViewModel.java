package zm.ac.mulungushi.registrar;

import androidx.annotation.StringRes;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

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
        if (r == LoginValidator.Result.OK_STAFF) {
            boolean match = identity.trim().equalsIgnoreCase(DemoAccounts.LECTURER_EMAIL)
                    && password.equals(DemoAccounts.LECTURER_PASSWORD);
            if (match) {
                signedIn.setValue(Role.LECTURER);
            } else {
                // same message whatever was wrong
                state.setValue(new UiState(true, s.idTouched, R.string.error_bad_credentials));
            }
            return;
        }
        // demo: any password works for a student
        signedIn.setValue(Role.STUDENT);
    }

    private UiState current() {
        UiState s = state.getValue();
        return s != null ? s : new UiState(false, false, 0);
    }
}
