package zm.ac.mulungushi.registrar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/** "Request for Group" vs "Request for Group Change", and what may be sent (GroupRequestRules). */
public class GroupRequestRulesTest {

    private static final String[] GROUPS = {"G01", "G02", "G03", "G04"};

    private static Student student(String group) {
        return new Student("id", "Riley Mwale", "202301046", "IT", group, true);
    }

    @Test public void noGroupMeansRequestForGroup() {
        assertEquals(GroupRequestRules.Mode.FIRST_GROUP, GroupRequestRules.modeFor(student(StudentRepository.UNASSIGNED)));
        assertEquals(GroupRequestRules.Mode.FIRST_GROUP, GroupRequestRules.modeFor(student(null)));
    }

    @Test public void aGroupMeansRequestForGroupChange() {
        assertEquals(GroupRequestRules.Mode.GROUP_CHANGE, GroupRequestRules.modeFor(student("G02")));
    }

    @Test public void theWordingFollowsTheStoredGroupWhenItChanges() {
        Student s = student(StudentRepository.UNASSIGNED);
        assertEquals(GroupRequestRules.Mode.FIRST_GROUP, GroupRequestRules.modeFor(s));
        s.group = "G03"; // the lecturer approved: the next record from the server says G03
        assertEquals(GroupRequestRules.Mode.GROUP_CHANGE, GroupRequestRules.modeFor(s));
    }

    @Test public void choicesAreRealGroupsOtherThanYourOwn() {
        assertEquals(Arrays.asList("G01", "G03", "G04"), GroupRequestRules.choices(student("G02"), GROUPS));
        assertEquals(Arrays.asList(GROUPS), GroupRequestRules.choices(student(StudentRepository.UNASSIGNED), GROUPS));
    }

    @Test public void checkedAgainstTheLatestRecordBeforeSending() {
        Student s = student("G02");
        assertEquals(GroupRequestRules.Refusal.NONE, GroupRequestRules.check(s, "G03", GROUPS));
        assertEquals(GroupRequestRules.Refusal.NO_CHOICE, GroupRequestRules.check(s, null, GROUPS));
        assertEquals(GroupRequestRules.Refusal.SAME_GROUP, GroupRequestRules.check(s, "G02", GROUPS));
        assertEquals(GroupRequestRules.Refusal.NOT_A_GROUP, GroupRequestRules.check(s, StudentRepository.UNASSIGNED, GROUPS));
        assertEquals(GroupRequestRules.Refusal.NOT_A_GROUP, GroupRequestRules.check(s, "G09", GROUPS));
    }

    @Test public void oneOpenRequestAtATime() {
        Student s = student(StudentRepository.UNASSIGNED);
        s.pendingGroup = "G01";
        assertTrue(GroupRequestRules.hasOpenRequest(s));
        assertEquals(GroupRequestRules.Refusal.OPEN_REQUEST, GroupRequestRules.check(s, "G03", GROUPS));
        s.pendingGroup = null;
        assertFalse(GroupRequestRules.hasOpenRequest(s));
    }
}
