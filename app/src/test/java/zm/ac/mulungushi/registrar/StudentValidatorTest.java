package zm.ac.mulungushi.registrar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import zm.ac.mulungushi.registrar.StudentValidator.NameResult;
import zm.ac.mulungushi.registrar.StudentValidator.NumberResult;

public class StudentValidatorTest {

    @Test public void emptyName() {
        assertEquals(NameResult.EMPTY, StudentValidator.checkName(""));
        assertEquals(NameResult.EMPTY, StudentValidator.checkName(null));
    }

    @Test public void validName() {
        assertEquals(NameResult.OK, StudentValidator.checkName("Mutinta Banda"));
        assertEquals(NameResult.OK, StudentValidator.checkName("Mary-Jane O'Brien"));
    }

    @Test public void nameNeedsTwoWords() {
        assertEquals(NameResult.NEED_TWO_WORDS, StudentValidator.checkName("Mutinta"));
    }

    @Test public void nameRejectsDigitsAndSymbols() {
        assertEquals(NameResult.HAS_DIGIT, StudentValidator.checkName("Mutinta B4nda"));
        assertEquals(NameResult.INVALID_CHARS, StudentValidator.checkName("Mutinta@Banda"));
    }

    @Test public void nameLengthLimits() {
        assertEquals(NameResult.TOO_SHORT, StudentValidator.checkName("M"));
        String tooLong = "A very long name that keeps going past one hundred characters just to trip the limit here";
        assertEquals(NameResult.TOO_LONG, StudentValidator.checkName(tooLong + tooLong));
    }

    @Test public void emptyNumber() {
        assertEquals(NumberResult.EMPTY, StudentValidator.checkNumber(""));
    }

    @Test public void validNumber() {
        assertEquals(NumberResult.OK, StudentValidator.checkNumber("202301045"));
    }

    @Test public void numberWrongLengthOrNotDigits() {
        assertEquals(NumberResult.WRONG_LENGTH, StudentValidator.checkNumber("2023"));
        assertEquals(NumberResult.NOT_DIGITS, StudentValidator.checkNumber("20230104x"));
        assertEquals(NumberResult.HAS_SPACE, StudentValidator.checkNumber("2023 1045"));
    }

    @Test public void numberRejectsObviousFakes() {
        assertEquals(NumberResult.NOT_REAL, StudentValidator.checkNumber("111111111"));
        assertEquals(NumberResult.NOT_REAL, StudentValidator.checkNumber("123456789"));
    }

    @Test public void isOkHelpers() {
        assertTrue(StudentValidator.isOk(NameResult.OK));
        assertTrue(StudentValidator.isOk(NumberResult.OK));
    }
}
