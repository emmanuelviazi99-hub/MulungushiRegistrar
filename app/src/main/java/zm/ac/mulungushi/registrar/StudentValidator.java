package zm.ac.mulungushi.registrar;

import java.util.regex.Pattern;

/**
 * Field rules for the roster's Add/Edit sheet, ported from checkName and
 * checkNumber in the prototype. Plain Java so it can be unit tested without
 * a device, the same way LoginValidator is.
 *
 * Simplified from the prototype on purpose: the brief only requires the
 * length/character/uniqueness rules below (see lab_3_.pdf, Data validation
 * and integrity). The prototype's extra typing-mistake heuristics (junk
 * words, keyboard-mash detection, vowel checks) are a nice-to-have for the
 * web build, not a marked requirement, so they were left out here to keep
 * this class small. Add them later if you want to match the prototype
 * exactly.
 */
public final class StudentValidator {

    public enum NameResult { EMPTY, TOO_SHORT, TOO_LONG, HAS_DIGIT, INVALID_CHARS, NEED_TWO_WORDS, OK }

    public enum NumberResult { EMPTY, HAS_SPACE, NOT_DIGITS, WRONG_LENGTH, NOT_REAL, OK }

    private static final Pattern DIGIT = Pattern.compile("\\d");
    private static final Pattern NAME_CHARS =
            Pattern.compile("[\\p{L}][\\p{L}' .\\-]*");
    private static final Pattern DIGITS_ONLY = Pattern.compile("\\d+");
    private static final Pattern SAME_DIGIT = Pattern.compile("(\\d)\\1{8}");
    private static final Pattern SPACE = Pattern.compile("\\s");

    private StudentValidator() {}

    public static NameResult checkName(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) return NameResult.EMPTY;
        if (t.length() < 2) return NameResult.TOO_SHORT;
        if (t.length() > 100) return NameResult.TOO_LONG;
        if (DIGIT.matcher(t).find()) return NameResult.HAS_DIGIT;
        if (!NAME_CHARS.matcher(t).matches()) return NameResult.INVALID_CHARS;
        if (t.split("\\s+").length < 2) return NameResult.NEED_TWO_WORDS;
        return NameResult.OK;
    }

    public static NumberResult checkNumber(String raw) {
        String t = raw == null ? "" : raw;
        if (t.isEmpty()) return NumberResult.EMPTY;
        if (SPACE.matcher(t).find()) return NumberResult.HAS_SPACE;
        if (!DIGITS_ONLY.matcher(t).matches()) return NumberResult.NOT_DIGITS;
        if (t.length() != 9) return NumberResult.WRONG_LENGTH;
        if (SAME_DIGIT.matcher(t).matches()
                || "0123456789".contains(t)
                || "9876543210".contains(t)) {
            return NumberResult.NOT_REAL;
        }
        return NumberResult.OK;
    }

    public static boolean isOk(NameResult r) { return r == NameResult.OK; }

    public static boolean isOk(NumberResult r) { return r == NumberResult.OK; }
}
