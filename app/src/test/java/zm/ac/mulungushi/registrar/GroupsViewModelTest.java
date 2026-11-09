package zm.ac.mulungushi.registrar;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** The lecturer's Groups screen: who is listed under a group, and which group is shown. */
public class GroupsViewModelTest {

    private final List<Student> roster = Arrays.asList(
            new Student("1", "natasha Banda", "202301045", "CS", "G02", true),
            new Student("2", "Riley Mwale", "202301046", "IT", "G01", true),
            new Student("3", "Chanda Phiri", "202401007", "CS", StudentRepository.UNASSIGNED, true),
            new Student("4", "Bupe Zulu", "202301050", "DS", "G02", true),
            new Student("5", "Natasha Banda", "202301001", "CS", "G02", true));

    @Test public void aGroupListsOnlyItsMembersAToZ() {
        List<Student> g02 = GroupsViewModel.membersOf(roster, "G02");
        assertEquals(3, g02.size());
        assertEquals("Bupe Zulu", g02.get(0).name);               // A-Z, ignoring capitals
        assertEquals("202301001", g02.get(1).number);             // same name: by number
        assertEquals("202301045", g02.get(2).number);
    }

    @Test public void studentsWithoutAGroupHaveTheirOwnList() {
        List<Student> none = GroupsViewModel.membersOf(roster, StudentRepository.UNASSIGNED);
        assertEquals(1, none.size());
        assertEquals("Chanda Phiri", none.get(0).name);
        assertEquals(0, GroupsViewModel.membersOf(roster, "G09").size());
    }

    @Test public void theTappedGroupIsShownIfItStillExists() {
        String[] groups = {"G01", "G02", "G03"};
        assertEquals("G02", GroupsViewModel.pick("G02", groups, false));
        assertEquals("G01", GroupsViewModel.pick("G07", groups, false));    // closed since: the first group
        assertEquals("G01", GroupsViewModel.pick(null, groups, false));     // "View group members": the first
        assertEquals(StudentRepository.UNASSIGNED, GroupsViewModel.pick(StudentRepository.UNASSIGNED, groups, true));
        assertEquals("G01", GroupsViewModel.pick(StudentRepository.UNASSIGNED, groups, false)); // nobody left without a group
    }
}
