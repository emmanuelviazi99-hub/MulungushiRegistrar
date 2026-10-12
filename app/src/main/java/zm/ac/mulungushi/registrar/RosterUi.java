package zm.ac.mulungushi.registrar;

import android.app.Activity;
import android.content.Intent;

/**
 * Turns a failed RosterApi call into what the person sees. The backend's own
 * message is already plain language ("This lab group already has 15 active
 * students."), so it is used as it is; only the cases without one get a
 * message from strings.xml. An expired sign-in sends the person back to the
 * sign-in screen.
 */
final class RosterUi {

    private RosterUi() {}

    /** The signed-in lecturer's name, as on their account. */
    static String lecturerName() {
        String name = Session.displayName();
        return name == null || name.trim().isEmpty() ? "Lecturer" : name.trim();
    }

    static String message(Activity a, RosterApi.Outcome o) {
        if (o.is(RosterApi.Outcome.NETWORK)) return a.getString(R.string.error_network);
        if (o.is("VERSION_CONFLICT")) return a.getString(R.string.error_version_conflict);
        if (o.is("STUDENT_NOT_FOUND")) return a.getString(R.string.error_student_gone);
        if (o.is("REQUEST_NOT_FOUND")) return a.getString(R.string.error_request_gone);
        if (o.message != null) return o.message;
        return a.getString(R.string.error_server);
    }

    /**
     * Handles a failure the screen has no special place for: shows it, or for an
     * expired sign-in goes back to the sign-in screen. Returns true if it went there.
     */
    static boolean report(Activity a, RosterApi.Outcome o) {
        if (signedOut(a, o)) return true;
        Feedback.show(a, message(a, o));
        return false;
    }

    /**
     * The signed-in student for a student screen, or null after sending the person
     * to the sign-in screen (no saved sign-in, e.g. the app data was cleared).
     */
    static Student meOrSignIn(Activity a) {
        Student me = StudentRepository.getInstance().me();
        if (me != null && Session.isSignedIn()) return me;
        Intent intent = new Intent(a, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        a.startActivity(intent);
        a.finish();
        return null;
    }

    /**
     * After a background refresh failed: say so once per screen visit (the
     * screen keeps showing the last known data), or go to sign-in if expired.
     */
    static void refreshFailed(Activity a, RosterApi.Outcome o, boolean alreadyWarned) {
        if (signedOut(a, o) || alreadyWarned) return;
        Feedback.show(a, message(a, o));
    }

    /** UNAUTHORIZED: the token is missing, expired, or from before a password reset. */
    static boolean signedOut(Activity a, RosterApi.Outcome o) {
        if (!o.is("UNAUTHORIZED")) return false;
        Session.clear();
        StudentRepository.reset();
        Feedback.postForNext(a.getString(R.string.error_signed_out));
        Intent intent = new Intent(a, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        a.startActivity(intent);
        a.finish();
        return true;
    }
}
