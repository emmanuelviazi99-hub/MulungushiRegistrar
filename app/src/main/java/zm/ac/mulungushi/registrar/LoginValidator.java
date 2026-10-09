package zm.ac.mulungushi.registrar;

import java.util.regex.Pattern;

/**
 * Sign-in field rules, ported from checkIdentity/checkNumber in the prototype.
 * Plain Java on purpose so it can be unit tested without a device.
 */
public final class LoginValidator {

    public enum Result {
        EMPTY,
        EMAIL_INCOMPLETE,
        NOT_DIGITS,
        WRONG_LENGTH,
        NOT_REAL,
        OK_STUDENT,
        OK_STAFF
    }

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern SAME_DIGIT = Pattern.compile("(\\d)\\1{8}");

    private LoginValidator() {}

    /** One field takes both: an @ means staff email, anything else is a student number. */
    public static Result checkIdentity(String value) {
        String t = value == null ? "" : value.trim();
        if (t.isEmpty()) return Result.EMPTY;

        if (t.contains("@")) {
            return EMAIL.matcher(t).matches() ? Result.OK_STAFF : Result.EMAIL_INCOMPLETE;
        }
        if (!DIGITS.matcher(t).matches()) return Result.OK_STAFF;
        if (t.length() != 9) return Result.WRONG_LENGTH;
        if (SAME_DIGIT.matcher(t).matches()
                || "0123456789".contains(t)
                || "9876543210".contains(t)) {
            return Result.NOT_REAL;
        }
        return Result.OK_STUDENT;
    }

    public static boolean isOk(Result r) {
        return r == Result.OK_STUDENT || r == Result.OK_STAFF;
    }
}
