package zm.ac.mulungushi.registrar;

/**
 * The one place the app talks to a server for registering and resetting a password.
 *
 * HOW TO CONNECT THE REAL SERVER: write a class that implements this interface
 * (for example ApiAuthService, using Retrofit), then change one line in Auth.get()
 * to return it. RegisterActivity and ForgotPasswordActivity do not change.
 * DemoAuthService is the in-app stand-in used until then.
 *
 * Rules the server should enforce (the app only shows the result):
 *  - the student number must be on the university list and not already registered
 *  - a code is valid for CODE_VALID_MINUTES, allows MAX_CODE_TRIES wrong tries, and a
 *    new code request must be rate limited
 *  - reset: never reveal whether an email exists, always answer OK to requestResetCode
 *  - passwords are stored as a salted hash, never as plain text
 *
 * Callbacks must be delivered on the main thread.
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
        NETWORK_ERROR
    }

    final class Result {
        public final Status status;
        /** Only meaningful for BAD_CODE: how many tries are left. */
        public final int triesLeft;

        public Result(Status status, int triesLeft) {
            this.status = status;
            this.triesLeft = triesLeft;
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

    /** What the student typed on page 1 of Register. The phone is already in +260XXXXXXXXX form. */
    final class Registration {
        public final String name;
        public final String number;
        public final String phone;
        public final String nrc;
        public final String email;

        public Registration(String name, String number, String phone, String nrc, String email) {
            this.name = name;
            this.number = number;
            this.phone = phone;
            this.nrc = nrc;
            this.email = email;
        }
    }

    /** Checks the details, then emails a claim code. Calling it again is "Resend code". */
    void requestRegistrationCode(Registration details, Callback callback);

    /** Checks the code the student typed. */
    void verifyRegistrationCode(String email, String code, Callback callback);

    /** Creates the account. Only works after the code was verified. */
    void completeRegistration(String email, String programme, String password, Callback callback);

    /** Emails a reset code if the email is registered. Always answers OK, so it never reveals which emails exist. */
    void requestResetCode(String email, Callback callback);

    void verifyResetCode(String email, String code, Callback callback);

    /** Sets the new password. Only works after the code was verified. */
    void setNewPassword(String email, String code, String newPassword, Callback callback);
}
