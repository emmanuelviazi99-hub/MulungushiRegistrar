package zm.ac.mulungushi.registrar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import zm.ac.mulungushi.registrar.LoginValidator.Result;

public class LoginValidatorTest {

    @Test public void emptyIsEmpty() {
        assertEquals(Result.EMPTY, LoginValidator.checkIdentity(""));
        assertEquals(Result.EMPTY, LoginValidator.checkIdentity("   "));
        assertEquals(Result.EMPTY, LoginValidator.checkIdentity(null));
    }

    @Test public void validStudentNumber() {
        assertEquals(Result.OK_STUDENT, LoginValidator.checkIdentity("202301045"));
        assertEquals(Result.OK_STUDENT, LoginValidator.checkIdentity("  202301045  "));
    }

    @Test public void leadingZeroesAreKept() {
        assertEquals(Result.OK_STUDENT, LoginValidator.checkIdentity("001234567"));
    }

    @Test public void lettersOrInnerSpacesAreNotDigits() {
        assertEquals(Result.NOT_DIGITS, LoginValidator.checkIdentity("20230104x"));
        assertEquals(Result.NOT_DIGITS, LoginValidator.checkIdentity("2023 01045"));
    }

    @Test public void wrongLength() {
        assertEquals(Result.WRONG_LENGTH, LoginValidator.checkIdentity("2023"));
        assertEquals(Result.WRONG_LENGTH, LoginValidator.checkIdentity("2023010455"));
    }

    @Test public void obviousFakesAreRejected() {
        assertEquals(Result.NOT_REAL, LoginValidator.checkIdentity("111111111"));
        assertEquals(Result.NOT_REAL, LoginValidator.checkIdentity("123456789"));
        assertEquals(Result.NOT_REAL, LoginValidator.checkIdentity("987654321"));
    }

    @Test public void staffEmail() {
        assertEquals(Result.OK_STAFF, LoginValidator.checkIdentity("b.nyirenda@mulungushi.edu.zm"));
        assertEquals(Result.EMAIL_INCOMPLETE, LoginValidator.checkIdentity("b.nyirenda@mulungushi"));
        assertEquals(Result.EMAIL_INCOMPLETE, LoginValidator.checkIdentity("@x.zm"));
    }

    @Test public void isOkOnlyForTheTwoGoodResults() {
        assertTrue(LoginValidator.isOk(Result.OK_STUDENT));
        assertTrue(LoginValidator.isOk(Result.OK_STAFF));
        assertFalse(LoginValidator.isOk(Result.EMPTY));
        assertFalse(LoginValidator.isOk(Result.WRONG_LENGTH));
    }
}
