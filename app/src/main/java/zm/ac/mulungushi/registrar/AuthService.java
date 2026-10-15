package zm.ac.mulungushi.registrar;

/**
 * Everything the app asks the server about accounts: signing in, creating an
 * account (students and lecturers) and resetting a forgotten password.
 * ApiAuthService is the implementation; Auth.get() hands it out.
 *
 * The screens only show the answer. The server enforces the rules:
 *  - a new student can sign up straight away; if the student number is already on the
 *    roster, its NRC + date of birth must match (so nobody takes over someone's record)
 *  - a lecturer's email + employee ID must be on the staff list
 *  - a code is valid for CODE_VALID_MINUTES, allows MAX_CODE_TRIES tries, and a new
 *    one can be asked for every RESEND_SECONDS
 *  - reset never reveals whether an email has an account (requestResetCode is always OK)
 *  - passwords are stored only as a bcrypt hash
 *
 * Callbacks are delivered on the main thread.
 */
public interface AuthService {

    int CODE_VALID_MINUTES = 10;
    int RESEND_SECONDS = 30;
    int MAX_CODE_TRIES = 5;

    enum Status {
        OK,
        NOT_ON_ROSTER,
        ALREADY_REGISTERED,
        EMAIL_IN_USE,
        BAD_CODE,
        CODE_EXPIRED,
        TOO_MANY_TRIES,
        NOT_VERIFIED,
        /** Sign-in: wrong number/email or password (the server never says which). */
        BAD_CREDENTIALS,
        /** Change password: the current password isn't right. */
        WRONG_PASSWORD,
        /** Change password: the sign-in on this phone has expired; sign in again. */
        SIGNED_OUT,
        /** A new code was asked for within 30 seconds; see retryAfterSeconds. */
        RESEND_TOO_SOON,
        /** The server could not send the email. Nothing was saved; try again. */
        DELIVERY_FAILED,
        /** Too many requests from this phone's network; wait a few minutes. */
        RATE_LIMITED,
        /** The server rejected a field; message says which. */
        INVALID_DETAILS,
        /** The server answered with something unexpected (wrong address, server bug). */
        SERVER_ERROR,
        /** The server could not be reached at all. */
        NETWORK_ERROR
    }

    final class Result {
        public final Status status;
        /** Only meaningful for BAD_CODE: how many tries are left. */
        public final int triesLeft;
        /** Only meaningful for RESEND_TOO_SOON: seconds until a new code can be asked for. */
        public final int retryAfterSeconds;
        /** The server's own explanation, when it gave one (shown for INVALID_DETAILS). May be null. */
        public final String message;

        public Result(Status status, int triesLeft, int retryAfterSeconds, String message) {
            this.status = status;
            this.triesLeft = triesLeft;
            this.retryAfterSeconds = retryAfterSeconds;
            this.message = message;
        }

        public Result(Status status, int triesLeft) {
            this(status, triesLeft, 0, null);
        }

        public static Result of(Status status) {
            return new Result(status, 0);
        }

        public boolean ok() {
            return status == Status.OK;
        }
    }

    interface Callback {
        void onResult(Result result);
    }

    /** What a student typed on page 1 of Register. Phone is +260XXXXXXXXX, dateOfBirth is YYYY-MM-DD. */
    final class Registration {
        public final String name;
        public final String number;
        public final String phone;
        public final String nrc;
        public final String dateOfBirth;
        public final String email;

        public Registration(String name, String number, String phone, String nrc, String dateOfBirth, String email) {
            this.name = name;
            this.number = number;
            this.phone = phone;
            this.nrc = nrc;
            this.dateOfBirth = dateOfBirth;
            this.email = email;
        }
    }

    /** What a lecturer typed on page 1 of Register. Phone is optional (empty string if not given). */
    final class StaffRegistration {
        public final String name;
        public final String email;
        public final String employeeId;
        public final String phone;

        public StaffRegistration(String name, String email, String employeeId, String phone) {
            this.name = name;
            this.email = email;
            this.employeeId = employeeId;
            this.phone = phone;
        }
    }

    // ---- sign in / out ----

    /** loginKey is a 9-digit student number or an email. On OK, Session holds the signed-in user. */
    void signIn(String loginKey, String password, Callback callback);

    /** Forgets the signed-in user on this phone. */
    void signOut();

    // ---- student sign-up ----

    /** Checks the details (against the roster if the number is on it), then emails a code. Calling it again is "Resend code". */
    void requestRegistrationCode(Registration details, Callback callback);

    /** Checks the code the student typed. */
    void verifyRegistrationCode(String email, String code, Callback callback);

    /**
     * Creates the account (and, for a student not on the roster yet, their student record)
     * and signs in. Only works after the code was verified. programme is required for a new student.
     */
    void completeRegistration(String email, String programme, String password, Callback callback);

    // ---- lecturer sign-up ----

    /** Checks the email + employee ID against the staff list, then emails a code. */
    void requestStaffCode(StaffRegistration details, Callback callback);

    void verifyStaffCode(String email, String code, Callback callback);

    /** Creates the lecturer account and signs in. Only works after the code was verified. */
    void completeStaffRegistration(String email, String password, Callback callback);

    // ---- forgotten password ----

    /** Emails a reset code if the email is registered. Always answers OK, so it never reveals which emails exist. */
    void requestResetCode(String email, Callback callback);

    void verifyResetCode(String email, String code, Callback callback);

    /** Sets the new password. Only works with a code that was verified. */
    void setNewPassword(String email, String code, String newPassword, Callback callback);

    // ---- change password (signed in, Edit my details) ----

    /**
     * Changes the signed-in person's password. OK: changed, and this phone gets a fresh
     * sign-in (every other phone is signed out). WRONG_PASSWORD: current password wrong.
     * Needs the server: offline gives NETWORK_ERROR and nothing changes.
     */
    void changePassword(String currentPassword, String newPassword, Callback callback);
}
