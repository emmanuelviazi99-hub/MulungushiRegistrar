package zm.ac.mulungushi.registrar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** "Welcome, Riley!" vs "Welcome back, Riley!" (Greeting, used by AuthViewModel). */
public class GreetingTest {

    @Test public void firstSignInDuringThisRunIsAFirstWelcome() {
        Greeting g = Greeting.decide(Boolean.TRUE, "Riley Banda");
        assertTrue(g.firstTime);
        assertEquals("Riley", g.name);
    }

    @Test public void aLaterSignInIsAReturn() {
        assertFalse(Greeting.decide(Boolean.FALSE, "Riley Banda").firstTime);
    }

    @Test public void reopeningTheAppSignedInIsAReturnNotAFirstVisit() {
        // no sign-in during this run: Session.firstSignInThisRun() is null
        assertFalse(Greeting.decide(null, "Riley Banda").firstTime);
    }

    @Test public void theSameAnswerTwiceIsEqualSoTheScreenIsNotRedrawnForNothing() {
        assertEquals(Greeting.decide(true, "Riley"), Greeting.decide(true, "  Riley  "));
    }

    @Test public void firstNameOnly() {
        assertEquals("Mwaka", Greeting.firstName("  Mwaka   Hamilemba "));
        assertEquals("", Greeting.firstName(null));
        assertEquals("", Greeting.firstName("   "));
    }

    @Test public void sessionKeepsTheFlagForThisRunOnlyAndSignOutClearsIt() {
        Session.clear();
        assertEquals(null, Session.firstSignInThisRun());
        Session.recordSignIn(true);
        assertEquals(Boolean.TRUE, Session.firstSignInThisRun());
        Session.clear(); // sign-out: the next sign-in decides again (on the server)
        assertEquals(null, Session.firstSignInThisRun());
    }
}
