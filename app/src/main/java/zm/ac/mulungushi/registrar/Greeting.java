package zm.ac.mulungushi.registrar;

/**
 * Which welcome a home screen shows. Plain Java, so it is unit-tested
 * (GreetingTest) without Android.
 *
 *   "Welcome, Riley!"       the account's first sign-in, made during this run of the app
 *   "Welcome back, Riley!"  every other case: a later sign-in, or the app reopened
 *                           with a saved sign-in (that is a return, not a first visit)
 *
 * Whether a sign-in was the first is decided by the server, once per account
 * (accounts.first_signed_in_at), so it does not depend on this phone, on
 * signing out, or on the profile existing (sign-up creates the profile first).
 */
final class Greeting {

    final boolean firstTime;
    /** First name only, e.g. "Riley" from "Riley Banda". Empty if unknown. */
    final String name;

    private Greeting(boolean firstTime, String name) {
        this.firstTime = firstTime;
        this.name = name;
    }

    /**
     * @param firstSignInThisRun Session.firstSignInThisRun(): true / false for a sign-in
     *                           during this run, null when the app reopened signed in
     */
    static Greeting decide(Boolean firstSignInThisRun, String fullName) {
        return new Greeting(Boolean.TRUE.equals(firstSignInThisRun), firstName(fullName));
    }

    static String firstName(String fullName) {
        if (fullName == null || fullName.trim().isEmpty()) return "";
        return fullName.trim().split("\\s+")[0];
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Greeting)) return false;
        Greeting g = (Greeting) o;
        return firstTime == g.firstTime && name.equals(g.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode() * 31 + (firstTime ? 1 : 0);
    }
}
