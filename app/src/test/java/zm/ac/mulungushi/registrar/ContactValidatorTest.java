package zm.ac.mulungushi.registrar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ContactValidatorTest {

    @Test
    public void email() {
        assertEquals(ContactValidator.EmailResult.EMPTY, ContactValidator.checkEmail("  "));
        assertEquals(ContactValidator.EmailResult.INVALID, ContactValidator.checkEmail("chanda"));
        assertEquals(ContactValidator.EmailResult.INVALID, ContactValidator.checkEmail("chanda@"));
        assertEquals(ContactValidator.EmailResult.INVALID, ContactValidator.checkEmail("chanda@example"));
        assertEquals(ContactValidator.EmailResult.INVALID, ContactValidator.checkEmail("a..b@example.com"));
        assertEquals(ContactValidator.EmailResult.OK, ContactValidator.checkEmail("chanda.mwansa@example.com"));
        assertEquals(ContactValidator.EmailResult.OK, ContactValidator.checkEmail(" b.nyirenda@mulungushi.edu.zm "));
    }

    @Test
    public void maskEmail() {
        assertEquals("c***@example.com", ContactValidator.maskEmail("chanda.mwansa@example.com"));
        assertEquals("", ContactValidator.maskEmail(null));
    }

    @Test
    public void phone() {
        assertEquals(ContactValidator.PhoneResult.EMPTY, ContactValidator.checkPhone(""));
        assertEquals(ContactValidator.PhoneResult.OK, ContactValidator.checkPhone("+260977123456"));
        assertEquals(ContactValidator.PhoneResult.OK, ContactValidator.checkPhone("+260 977 123 456"));
        assertEquals(ContactValidator.PhoneResult.OK, ContactValidator.checkPhone("0977123456"));
        assertEquals(ContactValidator.PhoneResult.OK, ContactValidator.checkPhone("260977123456"));
        assertEquals(ContactValidator.PhoneResult.OK, ContactValidator.checkPhone("00260977123456"));
        assertEquals(ContactValidator.PhoneResult.INVALID, ContactValidator.checkPhone("+26097712345"));
        assertEquals(ContactValidator.PhoneResult.INVALID, ContactValidator.checkPhone("+2609771234567"));
        assertEquals(ContactValidator.PhoneResult.INVALID, ContactValidator.checkPhone("+260abc123456"));
        assertEquals("+260977123456", ContactValidator.normalisePhone("0977 123 456"));
    }

    @Test
    public void nrc() {
        assertEquals(ContactValidator.NrcResult.EMPTY, ContactValidator.checkNrc(""));
        assertEquals(ContactValidator.NrcResult.OK, ContactValidator.checkNrc("123456/10/1"));
        assertEquals(ContactValidator.NrcResult.INVALID, ContactValidator.checkNrc("123456/10"));
        assertEquals(ContactValidator.NrcResult.INVALID, ContactValidator.checkNrc("12345/10/1"));
        assertEquals(ContactValidator.NrcResult.INVALID, ContactValidator.checkNrc("123456101"));
    }

    @Test
    public void formatNrcPutsSlashesIn() {
        assertEquals("123456", ContactValidator.formatNrc("123456"));
        assertEquals("123456/7", ContactValidator.formatNrc("1234567"));
        assertEquals("123456/78", ContactValidator.formatNrc("12345678"));
        assertEquals("123456/78/9", ContactValidator.formatNrc("123456789"));
        assertEquals("123456/78/9", ContactValidator.formatNrc("123456/78/9999"));
        assertEquals("123456/10/1", ContactValidator.formatNrc("123456/10/1"));
        assertEquals("", ContactValidator.formatNrc("abc"));
    }

    @Test
    public void code() {
        assertEquals("MU-7K4Q", ContactValidator.normaliseCode(" mu-7k4q "));
        assertEquals(ContactValidator.CodeResult.EMPTY, ContactValidator.checkCode(""));
        assertEquals(ContactValidator.CodeResult.OK, ContactValidator.checkCode("mu-7k4q"));
        assertEquals(ContactValidator.CodeResult.OK, ContactValidator.checkCode("482915"));
        assertEquals(ContactValidator.CodeResult.INVALID, ContactValidator.checkCode("123"));
        assertEquals(ContactValidator.CodeResult.INVALID, ContactValidator.checkCode("MU 7K4Q!"));
    }

    @Test
    public void password() {
        assertEquals(ContactValidator.PasswordResult.EMPTY, ContactValidator.checkPassword(""));
        assertEquals(ContactValidator.PasswordResult.TOO_SHORT, ContactValidator.checkPassword("short"));
        assertEquals(ContactValidator.PasswordResult.OK, ContactValidator.checkPassword("longenough"));
        assertTrue(ContactValidator.passwordsMatch("abc12345", "abc12345"));
        assertFalse(ContactValidator.passwordsMatch("abc12345", "abc12346"));
        assertFalse(ContactValidator.passwordsMatch(null, null));
    }

    @Test
    public void employeeId() {
        assertEquals(ContactValidator.EmployeeIdResult.EMPTY, ContactValidator.checkEmployeeId("  "));
        assertEquals(ContactValidator.EmployeeIdResult.OK, ContactValidator.checkEmployeeId("EMP-1001"));
        assertEquals(ContactValidator.EmployeeIdResult.OK, ContactValidator.checkEmployeeId(" emp-1001 "));
        assertEquals("EMP-1001", ContactValidator.normaliseEmployeeId(" emp-1001 "));
        assertEquals(ContactValidator.EmployeeIdResult.INVALID, ContactValidator.checkEmployeeId("E"));
        assertEquals(ContactValidator.EmployeeIdResult.INVALID, ContactValidator.checkEmployeeId("-EMP1001"));
        assertEquals(ContactValidator.EmployeeIdResult.INVALID, ContactValidator.checkEmployeeId("EMP 1001"));
    }

    @Test
    public void dateOfBirth() {
        java.util.Calendar today = new java.util.GregorianCalendar(2026, java.util.Calendar.OCTOBER, 11);
        assertEquals(ContactValidator.DobResult.EMPTY, ContactValidator.checkDateOfBirth("", today));
        assertEquals(ContactValidator.DobResult.OK, ContactValidator.checkDateOfBirth("2003-04-12", today));
        assertEquals(ContactValidator.DobResult.OK, ContactValidator.checkDateOfBirth("2026-10-10", today));
        assertEquals(ContactValidator.DobResult.INVALID, ContactValidator.checkDateOfBirth("2026-10-11", today)); // today
        assertEquals(ContactValidator.DobResult.INVALID, ContactValidator.checkDateOfBirth("2030-01-01", today)); // future
        assertEquals(ContactValidator.DobResult.INVALID, ContactValidator.checkDateOfBirth("2003-02-30", today)); // no such day
        assertEquals(ContactValidator.DobResult.INVALID, ContactValidator.checkDateOfBirth("12/04/2003", today)); // wrong format
        assertEquals(ContactValidator.DobResult.INVALID, ContactValidator.checkDateOfBirth("1900-01-01", today)); // too long ago
        assertEquals("2003-04-12", ContactValidator.isoDate(2003, 4, 12));
    }
}
