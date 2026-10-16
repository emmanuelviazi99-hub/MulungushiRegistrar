package zm.ac.mulungushi.registrar;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

/**
 * Who is signed in, for the screens after sign-in: the welcome line and the
 * role checks. (The sign-in form itself is LoginViewModel.)
 *
 * The welcome decision is made here and in Greeting, not in the Activities: the
 * server says whether a sign-in was the account's first (firstSignIn, kept in
 * Session for this run of the app), and this ViewModel turns that into
 * "Welcome" or "Welcome back". Being a ViewModel, the value survives rotation,
 * so recreating the screen shows the same line instead of deciding again.
 */
public class AuthViewModel extends ViewModel {

    private final MutableLiveData<Greeting> welcome = new MutableLiveData<>();

    public LiveData<Greeting> getWelcome() {
        return welcome;
    }

    /** Sets the welcome for this person; repeated calls with the same answer change nothing. */
    public void showWelcome(String fullName) {
        Greeting g = Greeting.decide(Session.firstSignInThisRun(), fullName);
        if (!g.equals(welcome.getValue())) welcome.setValue(g);
    }

    /** Lecturer screens: the server refuses students anyway (403); this keeps them off the screen. */
    public boolean isLecturer() {
        return Session.isSignedIn() && Session.isLecturer();
    }

    public boolean isStudent() {
        return Session.isSignedIn() && Session.isStudent();
    }
}
