package zm.ac.mulungushi.registrar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** The class-list preview the lecturer confirms (ClassListImport), and the download's file name. */
public class ClassListImportTest {

    /** The shape POST /students/import answers with (made-up students). */
    private static final String PREVIEW = "{\"success\":true,\"applied\":false,\"studentsInFile\":9,"
            + "\"toAdd\":7,\"added\":0,\"alreadyOnRoster\":1,\"alreadyOnRosterRows\":[],\"unassigned\":1,"
            + "\"groups\":["
            + "{\"groupCode\":\"G01\",\"exists\":true,\"membersNow\":14,\"adding\":2,\"willHave\":16,\"capacity\":16,"
            + "\"overUsualSize\":true,\"reopened\":false},"
            + "{\"groupCode\":\"G09\",\"exists\":false,\"membersNow\":0,\"adding\":4,\"willHave\":4,\"capacity\":15,"
            + "\"overUsualSize\":false,\"reopened\":false}],"
            + "\"programmes\":{\"IT\":2,\"CS\":5},"
            + "\"problems\":["
            + "{\"row\":\"row 12\",\"studentNumber\":\"241000003\",\"kind\":\"DUPLICATE_IN_FILE\",\"message\":\"listed twice\"},"
            + "{\"row\":\"row 30\",\"kind\":\"UNKNOWN_PROGRAMME\",\"message\":\"Programme \\\"Law\\\" is not one the app knows\"}],"
            + "\"blankRowsSkipped\":3,\"usualGroupSize\":15}";

    private static ClassListImport preview() throws Exception {
        return ClassListImport.from(new JSONObject(PREVIEW));
    }

    @Test public void readsTheCountsTheServerSent() throws Exception {
        ClassListImport p = preview();
        assertFalse(p.applied);
        assertEquals(9, p.studentsInFile);
        assertEquals(7, p.toAdd);
        assertEquals(1, p.alreadyOnRoster);
        assertEquals(2, p.groups.size());
        assertEquals(16, p.groups.get(0).capacity);
        assertTrue(p.groups.get(0).overUsualSize);
        assertFalse(p.groups.get(1).overUsualSize);
        assertEquals(1, p.count("DUPLICATE_IN_FILE"));
        assertEquals(1, p.rowsWithMistakes());
        assertEquals(1, p.newGroups());
    }

    @Test public void programmesAreListedInOrder() throws Exception {
        assertTrue(preview().previewText().contains("Programmes: CS 5, IT 2."));
    }

    @Test public void aGroupKeptWholeSaysItIsFull() throws Exception {
        ClassListImport p = preview();
        assertEquals("G01: +2 (16 of 16), more than 15: all kept, full until below 15", p.groupLine(p.groups.get(0)));
        assertEquals("G09: new group, 4 of 15 places", p.groupLine(p.groups.get(1)));
    }

    @Test public void thePreviewSaysWhatHappensToRepeatsAndBadRows() throws Exception {
        String text = preview().previewText();
        assertTrue(text, text.startsWith("9 students found in the file.\n7 students will be added; 1 is already on the roster"));
        assertTrue(text, text.contains("1 student is listed twice: the first entry is kept."));
        assertTrue(text, text.contains("1 row can't be imported and will be skipped:\n• Row 30: Programme \"Law\""));
        assertTrue(text, text.contains("• 1 student with no group"));
    }

    @Test public void theButtonSaysHowManyWillBeAdded() throws Exception {
        assertEquals("Import 7 students", preview().confirmLabel());
    }

    @Test public void theResultAfterSaving() throws Exception {
        JSONObject saved = new JSONObject(PREVIEW).put("applied", true).put("added", 7);
        ClassListImport r = ClassListImport.from(saved);
        assertTrue(r.applied);
        assertEquals("7 students were added, 1 new group. 1 already on the roster were left as they were. 1 row was skipped.",
                r.resultText());
    }

    @Test public void reImportingTheSameFileAddsNobody() throws Exception {
        JSONObject again = new JSONObject("{\"applied\":false,\"studentsInFile\":3,\"toAdd\":0,\"alreadyOnRoster\":3,"
                + "\"groups\":[],\"programmes\":{},\"problems\":[]}");
        ClassListImport p = ClassListImport.from(again);
        assertTrue(p.previewText(), p.previewText().startsWith("3 students found in the file.\nNobody new to add; 3 are already on the roster"));
        assertFalse(p.previewText().contains("Groups:"));
    }

    @Test public void onlyTheFirstFiveBadRowsAreListed() throws Exception {
        StringBuilder problems = new StringBuilder("[");
        for (int i = 1; i <= 8; i++) {
            if (i > 1) problems.append(',');
            problems.append("{\"row\":\"row ").append(i).append("\",\"kind\":\"BAD_NAME\",\"message\":\"bad\"}");
        }
        problems.append(']');
        ClassListImport p = ClassListImport.from(new JSONObject("{\"studentsInFile\":8,\"toAdd\":0,\"problems\":" + problems + "}"));
        String text = p.previewText();
        assertTrue(text, text.contains("• Row 5: bad\n• and 3 more"));
        assertFalse(text.contains("Row 6"));
    }

    @Test public void theDownloadKeepsTheServersFileName() {
        assertEquals("Mulungushi-Registrar-groups-2026-11-07.xlsx",
                ApiClient.fileNameFrom("attachment; filename=\"Mulungushi-Registrar-groups-2026-11-07.xlsx\""));
        assertEquals("a_b.xlsx", ApiClient.fileNameFrom("attachment; filename=a/b.xlsx"));
        assertNull(ApiClient.fileNameFrom(null));
        assertNull(ApiClient.fileNameFrom("attachment"));
    }
}
