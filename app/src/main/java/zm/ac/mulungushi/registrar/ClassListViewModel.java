package zm.ac.mulungushi.registrar;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

/**
 * The lecturer's "Class list" card on the dashboard: import a spreadsheet, download
 * the group lists, add a group.
 *
 *   View        LecturerDashboardActivity: picks and saves files (the system file
 *               picker), shows the preview dialog, passes on the lecturer's choices
 *   ViewModel   this class: one action at a time, the chosen file kept between the
 *               preview and "Import", one-time results as Events
 *   Repository  ClassListApi (POST /students/import, GET /students/export, POST /groups)
 *
 * The file stays here, not in the Activity, so turning the phone while the preview
 * is open doesn't lose it.
 */
public class ClassListViewModel extends ViewModel {

    /** What finished; shown once. */
    static final class Result {
        enum Kind { PREVIEW, IMPORTED, DOWNLOADED, GROUP_ADDED, FAILED }

        final Kind kind;
        final ClassListImport summary;   // PREVIEW, IMPORTED
        final ClassListApi.File file;    // DOWNLOADED
        final String groupCode;          // GROUP_ADDED
        final RosterApi.Outcome outcome; // FAILED

        Result(Kind kind, ClassListImport summary, ClassListApi.File file, String groupCode, RosterApi.Outcome outcome) {
            this.kind = kind;
            this.summary = summary;
            this.file = file;
            this.groupCode = groupCode;
            this.outcome = outcome;
        }
    }

    private final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    private final MutableLiveData<Event<Result>> results = new MutableLiveData<>();

    private byte[] pendingBytes;
    private String pendingName;
    private ClassListImport pendingPreview;
    /** The downloaded file, kept until the lecturer has chosen where to save it. */
    private ClassListApi.File downloaded;
    private boolean cleared;

    LiveData<Boolean> isBusy() { return busy; }

    LiveData<Event<Result>> getResults() { return results; }

    /** The preview still waiting for "Import" (after the phone was turned), or null. */
    ClassListImport pendingPreview() { return pendingPreview; }

    /** Step 1: the server reads the file and says what would happen. Nothing is saved. */
    void preview(byte[] bytes, String fileName) {
        if (isWorking()) return;
        pendingBytes = bytes;
        pendingName = fileName;
        pendingPreview = null;
        busy.setValue(true);
        ClassListApi.get().importFile(bytes, fileName, false, (outcome, summary) -> {
            if (cleared) return;
            busy.setValue(false);
            if (!outcome.ok || summary == null) {
                forget();
                fail(outcome);
                return;
            }
            pendingPreview = summary;
            results.setValue(new Event<>(new Result(Result.Kind.PREVIEW, summary, null, null, null)));
        });
    }

    /** Step 2: the lecturer pressed "Import N students". */
    void confirmImport() {
        if (isWorking() || pendingBytes == null) return;
        byte[] bytes = pendingBytes;
        String name = pendingName;
        pendingPreview = null; // answered: not shown again if the phone is turned now
        busy.setValue(true);
        ClassListApi.get().importFile(bytes, name, true, (outcome, summary) -> {
            if (cleared) return;
            busy.setValue(false);
            forget();
            if (!outcome.ok || summary == null) fail(outcome);
            else results.setValue(new Event<>(new Result(Result.Kind.IMPORTED, summary, null, null, null)));
        });
    }

    /** The lecturer closed the preview without importing. */
    void cancelImport() {
        if (!isWorking()) forget();
    }

    void download() {
        if (isWorking()) return;
        busy.setValue(true);
        ClassListApi.get().download((outcome, file) -> {
            if (cleared) return;
            busy.setValue(false);
            if (!outcome.ok || file == null) {
                fail(outcome);
                return;
            }
            downloaded = file;
            results.setValue(new Event<>(new Result(Result.Kind.DOWNLOADED, null, file, null, null)));
        });
    }

    /** The file waiting to be written where the lecturer chose; taken once. */
    ClassListApi.File takeDownloaded() {
        ClassListApi.File f = downloaded;
        downloaded = null;
        return f;
    }

    void addGroup() {
        if (isWorking()) return;
        busy.setValue(true);
        ClassListApi.get().addGroup((outcome, code) -> {
            if (cleared) return;
            busy.setValue(false);
            if (!outcome.ok) fail(outcome);
            else results.setValue(new Event<>(new Result(Result.Kind.GROUP_ADDED, null, null, code, null)));
        });
    }

    private boolean isWorking() {
        return Boolean.TRUE.equals(busy.getValue());
    }

    private void fail(RosterApi.Outcome outcome) {
        results.setValue(new Event<>(new Result(Result.Kind.FAILED, null, null, null, outcome)));
    }

    private void forget() {
        pendingBytes = null;
        pendingName = null;
        pendingPreview = null;
    }

    @Override
    protected void onCleared() {
        cleared = true;
        forget();
        downloaded = null;
    }
}
