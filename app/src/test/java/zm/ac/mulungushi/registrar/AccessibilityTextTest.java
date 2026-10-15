package zm.ac.mulungushi.registrar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;

/** Text that TalkBack reads, and the "saved at" time on the offline strip. */
public class AccessibilityTextTest {

    @Test public void studentNumbersAreReadDigitByDigit() {
        assertEquals("2 0 2 3 0 1 0 4 5", Accessibility.spokenDigits("202301045"));
        assertEquals("0 0 1 2", Accessibility.spokenDigits("0012")); // leading zeros are read too
        assertEquals("", Accessibility.spokenDigits(null));
    }

    @Test public void savedAtSaysTheTimeTodayAndTheDayOtherwise() {
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 9);
        today.set(Calendar.MINUTE, 5);
        assertEquals("09:05", RosterUi.savedAtText(today.getTimeInMillis()));

        Calendar yesterday = (Calendar) today.clone();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (yesterday.get(Calendar.YEAR) == today.get(Calendar.YEAR)) {
            assertEquals("Yesterday 09:05", RosterUi.savedAtText(yesterday.getTimeInMillis()));
        }

        Calendar older = (Calendar) today.clone();
        older.add(Calendar.DAY_OF_YEAR, -40);
        assertTrue(RosterUi.savedAtText(older.getTimeInMillis()).endsWith(" 09:05"));
        assertEquals("", RosterUi.savedAtText(0));
    }
}
