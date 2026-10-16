package zm.ac.mulungushi.registrar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Lecturer search and filters: RosterQuery's rules and StudentRepository.search(). */
public class RosterQueryTest {

    private final Student natasha = new Student("1", "Natasha Banda", "202301045", "CS", "G02", true);
    private final Student riley = new Student("2", "Riley Mwale", "202301046", "IT", "G01", true);
    private final Student chanda = new Student("3", "Chanda Phiri", "202401007", "CS", StudentRepository.UNASSIGNED, true);
    private final Student mulenga = new Student("4", "Mulenga Tembo", "202401008", "DS", "G02", true);

    private List<Student> roster;

    private void setUp() {
        roster = Arrays.asList(natasha, riley, chanda, mulenga);
        StudentRepository.reset();
        StudentRepository.getInstance().applyRoster(new ArrayList<>(roster), new ArrayList<>(), new ArrayList<>());
    }

    private List<Student> find(RosterQuery q) {
        if (roster == null) setUp();
        return StudentRepository.getInstance().search(q);
    }

    @Test public void searchByNameAnyCaseAnyWord() {
        assertEquals(Arrays.asList(natasha), find(RosterQuery.NONE.withText("banda")));
        assertEquals(Arrays.asList(natasha), find(RosterQuery.NONE.withText("  NATASHA ")));
        assertEquals(Arrays.asList(mulenga), find(RosterQuery.NONE.withText("tem")));
    }

    @Test public void searchByStudentNumberIgnoringSpaces() {
        assertEquals(Arrays.asList(riley), find(RosterQuery.NONE.withText("202301046")));
        assertEquals(Arrays.asList(chanda, mulenga), find(RosterQuery.NONE.withText("2024 01")));
        assertEquals(Arrays.asList(natasha, riley), find(RosterQuery.NONE.withText("2023-0104")));
    }

    @Test public void aFilterWithoutSearchText() {
        assertEquals(Arrays.asList(natasha, chanda), find(RosterQuery.NONE.withProgramme("CS")));
        assertEquals(Arrays.asList(natasha, mulenga), find(RosterQuery.NONE.withGroup("G02")));
    }

    @Test public void unassignedStudentsCanBeFiltered() {
        assertEquals(Arrays.asList(chanda), find(RosterQuery.NONE.withGroup(StudentRepository.UNASSIGNED)));
    }

    @Test public void searchAndFiltersWorkTogether() {
        RosterQuery q = RosterQuery.NONE.withText("2023").withGroup("G02");
        assertEquals(Arrays.asList(natasha), find(q));
        assertEquals(Arrays.asList(), find(q.withProgramme("IT")));
    }

    @Test public void someoneWhoIsNotThere() {
        assertTrue(find(RosterQuery.NONE.withText("Zebedee")).isEmpty());
        assertTrue(find(RosterQuery.NONE.withText("999999999")).isEmpty());
    }

    @Test public void clearFiltersKeepsTheTextAndClearAllResetsEverything() {
        setUp();
        RosterQuery q = RosterQuery.NONE.withText("ba").withProgramme("CS").withGroup("G02");
        assertTrue(q.hasFilters());
        assertEquals(2, q.filterCount());
        RosterQuery noFilters = q.withoutFilters();
        assertFalse(noFilters.hasFilters());
        assertEquals("ba", noFilters.text);
        assertEquals(roster, find(RosterQuery.NONE));
    }

    @Test public void countsForTheFilterSheet() {
        setUp();
        assertEquals(2, StudentRepository.getInstance().countMatching(RosterQuery.NONE.withProgramme("CS")));
        assertEquals(4, StudentRepository.getInstance().countMatching(RosterQuery.NONE));
    }

    @Test public void equalQueriesAreEqualSoNothingIsRecomputedForNothing() {
        assertEquals(RosterQuery.NONE.withText("x"), new RosterQuery("x", null, ""));
    }
}
