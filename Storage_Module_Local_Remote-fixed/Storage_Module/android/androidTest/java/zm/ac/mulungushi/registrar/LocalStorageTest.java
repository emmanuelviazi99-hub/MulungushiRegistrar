package zm.ac.mulungushi.registrar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Round-trip checks: what LocalStorage saves is what it loads back. Runs on a phone or emulator. */
@RunWith(AndroidJUnit4.class)
public class LocalStorageTest {

    @Test
    public void registeredStudentSurvivesSaveAndLoad() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        LocalStorage storage = new LocalStorage(ctx);
        Student s = new Student("t1", "Chanda", "Mwansa", "002301045", "CS", "Unassigned", true);
        s.email = "chanda@example.com";
        s.registeredAt = 1_700_000_000_000L;
        s.pendingGroup = "G02";
        s.noticeKind = "approved";
        s.updatedAt = 12345L;
        List<Student> in = new ArrayList<>();
        in.add(s);
        storage.saveAll(in, new ArrayList<>(), new HashMap<>());

        // a NEW LocalStorage reads the same saved data, like the app after a restart
        List<Student> out = new LocalStorage(ctx).loadStudents();
        assertEquals(1, out.size());
        Student r = out.get(0);
        assertEquals("Chanda", r.firstName);
        assertEquals("Mwansa", r.lastName);
        assertEquals("002301045", r.number); // text: the leading zeros survive
        assertEquals("CS", r.programme);
        assertEquals("chanda@example.com", r.email);
        assertEquals(1_700_000_000_000L, r.registeredAt);
        assertEquals("G02", r.pendingGroup);
        assertEquals("approved", r.noticeKind);
        assertEquals(12345L, r.updatedAt);
        assertNull(r.pendingNumber);
    }

    @Test
    public void oldSingleNameDataIsSplitIntoFirstAndLast() throws Exception {
        JSONObject old = new JSONObject();
        old.put("id", "s9");
        old.put("name", "Mutinta Banda");
        old.put("number", "202301198");
        old.put("programme", "DS");
        old.put("group", "G02");
        Student s = LocalStorage.fromJson(old);
        assertEquals("Mutinta", s.firstName);
        assertEquals("Banda", s.lastName);
    }
}
