package zm.ac.mulungushi.registrar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class SyncMergeTest {
    private static Student st(String id, String first, String number, long at) {
        Student s = new Student(id, first, "Banda", number, "CS", "G01", true);
        s.updatedAt = at;
        return s;
    }

    @Test public void newerRemoteOverwritesInPlace() {
        List<Student> local = new ArrayList<>();
        Student mine = st("a", "Local", "202301001", 100);
        local.add(mine);
        List<Student> remote = new ArrayList<>();
        remote.add(st("a", "Remote", "202301001", 200));
        assertEquals(1, SyncMerge.apply(local, remote));
        assertSame(mine, local.get(0));
        assertEquals("Remote", mine.firstName);
        assertEquals("Banda", mine.lastName);
    }

    @Test public void olderOrEqualRemoteIsIgnored() {
        List<Student> local = new ArrayList<>();
        local.add(st("a", "Local", "202301001", 200));
        List<Student> remote = new ArrayList<>();
        remote.add(st("a", "Stale", "202301001", 150));
        remote.add(st("a", "Same", "202301001", 200));
        assertEquals(0, SyncMerge.apply(local, remote));
        assertEquals("Local", local.get(0).firstName);
    }

    @Test public void unknownRemoteStudentIsAdded() {
        List<Student> local = new ArrayList<>();
        List<Student> remote = new ArrayList<>();
        remote.add(st("b", "New", "202301002", 5));
        assertEquals(1, SyncMerge.apply(local, remote));
        assertEquals(1, local.size());
    }

    @Test public void retriedDownloadNeverDuplicates() {
        List<Student> local = new ArrayList<>();
        List<Student> remote = new ArrayList<>();
        remote.add(st("b", "New", "202301002", 5));
        SyncMerge.apply(local, remote);
        SyncMerge.apply(local, remote);
        SyncMerge.apply(local, remote);
        assertEquals(1, local.size());
    }

    @Test public void sameNumberUnderAnotherIdReplacesASyncedLocalRecord() {
        List<Student> local = new ArrayList<>();
        Student seed = st("s1", "Seed", "202301045", 0);
        local.add(seed);
        List<Student> remote = new ArrayList<>();
        remote.add(st("uuid-1", "Registered", "202301045", 500));
        assertEquals(1, SyncMerge.apply(local, remote, s -> false));
        assertEquals(1, local.size()); // one record per student number
        assertEquals("uuid-1", local.get(0).id);
        assertEquals("Registered", local.get(0).firstName);
    }

    @Test public void sameNumberNeverOverwritesUnsyncedLocalChanges() {
        List<Student> local = new ArrayList<>();
        Student mine = st("mine", "Mine", "202301045", 900);
        local.add(mine);
        List<Student> remote = new ArrayList<>();
        remote.add(st("other", "Other", "202301045", 500));
        assertEquals(0, SyncMerge.apply(local, remote, s -> true));
        assertEquals(1, local.size());
        assertEquals("Mine", local.get(0).firstName);
    }

    @Test public void leadingZerosSurvive() {
        List<Student> local = new ArrayList<>();
        List<Student> remote = new ArrayList<>();
        remote.add(st("z", "Zero", "000000123", 1));
        SyncMerge.apply(local, remote);
        assertEquals("000000123", local.get(0).number);
    }
}
