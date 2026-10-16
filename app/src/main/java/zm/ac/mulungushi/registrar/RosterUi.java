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

    /**
     * What to say after a change worked: the screen's own words, or, when the change
     * is saved on the phone and waiting for a connection, that it will upload later.
     */
    static String okText(Activity a, RosterApi.Outcome o, String okMessage) {
        return o.queued ? a.getString(R.string.toast_saved_offline) : okMessage;
    }

    static String okText(Activity a, RosterApi.Outcome o, int okRes) {
        return okText(a, o, a.getString(okRes));
    }

    /** "14:05" today, "Yesterday 14:05", or "12 Oct 14:05". */
    static String savedAtText(long millis) {
        if (millis <= 0) return "";
        java.util.Calendar then = java.util.Calendar.getInstance();
        then.setTimeInMillis(millis);
        java.util.Calendar today = java.util.Calendar.getInstance();
        String time = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.ENGLISH).format(then.getTime());
        if (then.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR)) {
            int days = today.get(java.util.Calendar.DAY_OF_YEAR) - then.get(java.util.Calendar.DAY_OF_YEAR);
            if (days == 0) return time;
            if (days == 1) return "Yesterday " + time;
        }
        return new java.text.SimpleDateFormat("d MMM", java.util.Locale.ENGLISH).format(then.getTime()) + " " + time;
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
     * Lecturer screens call this first. A student (or nobody) is sent to their own
     * home or the sign-in screen, and the screen closes. Returns true for a lecturer.
     * The backend refuses students on every lecturer endpoint (403) anyway; this keeps
     * a student from even seeing an empty lecturer screen.
     */
    static boolean lecturerOrLeave(Activity a) {
        if (Session.isSignedIn() && Session.isLecturer()) return true;
        Intent intent = Session.isSignedIn() && Session.isStudent()
                ? new Intent(a, StudentHomeActivity.class)
                        .putExtra(StudentHomeActivity.EXTRA_STUDENT_NUMBER, Session.studentNumber())
                : new Intent(a, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        a.startActivity(intent);
        a.finish();
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
        long saved = StudentRepository.getInstance().dataSavedAt();
        if (o.is(RosterApi.Outcome.NETWORK) && saved > 0) {
            Feedback.show(a, a.getString(R.string.offline_showing_saved, savedAtText(saved)));
            return;
        }
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
