package zm.ac.mulungushi.registrar;

import java.util.regex.Pattern;

/**
 * Field rules for the Register and Forgot password screens: email, phone, NRC,
 * claim code / token, and password. Plain Java (no Android classes) so it can
 * be unit tested without a device, like StudentValidator and LoginValidator.
 * Nothing here uses Pattern.UNICODE_CHARACTER_CLASS, which Android rejects.
 */
public final class ContactValidator {

    public enum EmailResult { EMPTY, INVALID, OK }
    public enum PhoneResult { EMPTY, INVALID, OK }
    public enum NrcResult { EMPTY, INVALID, OK }
    public enum CodeResult { EMPTY, INVALID, OK }
    public enum PasswordResult { EMPTY, TOO_SHORT, OK }

    public static final int MIN_PASSWORD = 8;

    private static final Pattern EMAIL =
            Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9\\-]+(\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,}$");
    /** +260 followed by exactly 9 digits. */
    private static final Pattern PHONE = Pattern.compile("^\\+260\\d{9}$");
    /** 6 digits / 2 digits / 1 digit, e.g. 123456/10/1. */
    private static final Pattern NRC = Pattern.compile("^\\d{6}/\\d{2}/\\d$");
    /** 6 to 14 letters, digits or hyphens, e.g. MU-7K4Q. */
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9\\-]{4,13}$");

    private ContactValidator() {}

    // ---- email ----

    public static EmailResult checkEmail(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) return EmailResult.EMPTY;
        if (t.length() > 254 || t.contains("..") || !EMAIL.matcher(t).matches()) return EmailResult.INVALID;
        return EmailResult.OK;
    }

    /** chanda.mwansa@example.com becomes c***@example.com, so the screen can say where the code went. */
    public static String maskEmail(String email) {
        if (email == null) return "";
        String t = email.trim();
        int at = t.indexOf('@');
        if (at <= 0) return t;
        return t.substring(0, 1) + "***" + t.substring(at);
    }

    // ---- phone ----

    /**
     * Turns what the user typed into +260XXXXXXXXX where it can: removes spaces, hyphens and
     * brackets, and accepts 0977123456, 260977123456 and 00260977123456 as well as +260977123456.
     * Anything else is returned cleaned but unchanged, so checkPhone will reject it.
     */
    public static String normalisePhone(String raw) {
        if (raw == null) return "";
        String t = raw.replaceAll("[\\s\\-()]", "");
        if (t.startsWith("00260")) return "+" + t.substring(2);
        if (t.startsWith("260") && !t.startsWith("+")) return "+" + t;
        if (t.startsWith("0") && t.length() == 10) return "+260" + t.substring(1);
        return t;
    }

    public static PhoneResult checkPhone(String raw) {
        String t = normalisePhone(raw);
        if (t.isEmpty()) return PhoneResult.EMPTY;
        return PHONE.matcher(t).matches() ? PhoneResult.OK : PhoneResult.INVALID;
    }

    // ---- NRC ----

    /** Keeps the digits (up to 9) and puts the slashes in: 123456, 123456/10, 123456/10/1. */
    public static String formatNrc(String raw) {
        if (raw == null) return "";
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < raw.length() && d.length() < 9; i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') d.append(c);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < d.length(); i++) {
            if (i == 6 || i == 8) out.append('/');
            out.append(d.charAt(i));
        }
        return out.toString();
    }

    public static NrcResult checkNrc(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) return NrcResult.EMPTY;
        return NRC.matcher(t).matches() ? NrcResult.OK : NrcResult.INVALID;
    }

    // ---- claim code / token ----

    /** Trim, remove spaces, upper case: " mu-7k4q " becomes MU-7K4Q. */
    public static String normaliseCode(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("\\s", "").toUpperCase(java.util.Locale.ROOT);
    }

    public static CodeResult checkCode(String raw) {
        String t = normaliseCode(raw);
        if (t.isEmpty()) return CodeResult.EMPTY;
        return CODE.matcher(t).matches() ? CodeResult.OK : CodeResult.INVALID;
    }

    // ---- password ----

    public static PasswordResult checkPassword(String raw) {
        if (raw == null || raw.isEmpty()) return PasswordResult.EMPTY;
        return raw.length() < MIN_PASSWORD ? PasswordResult.TOO_SHORT : PasswordResult.OK;
    }

    public static boolean passwordsMatch(String a, String b) {
        return a != null && a.equals(b);
    }

    public static boolean isOk(EmailResult r) { return r == EmailResult.OK; }
    public static boolean isOk(PhoneResult r) { return r == PhoneResult.OK; }
    public static boolean isOk(NrcResult r) { return r == NrcResult.OK; }
    public static boolean isOk(CodeResult r) { return r == CodeResult.OK; }
    public static boolean isOk(PasswordResult r) { return r == PasswordResult.OK; }
}
