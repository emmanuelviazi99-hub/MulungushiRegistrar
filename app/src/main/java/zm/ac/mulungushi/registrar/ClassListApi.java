package zm.ac.mulungushi.registrar;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The lecturer's class-list actions, straight to the server (they need a connection
 * and are not queued in the outbox: a 200-student import is one request the server
 * applies all at once or not at all).
 *
 *   POST /students/import   { fileName, fileBase64, apply }   preview, then save
 *   GET  /students/export                                     the group lists as .xlsx
 *   POST /groups            { capacity? }                     the next group (G15 after G14)
 *
 * Work runs on a background thread; answers come back on the main thread. After a
 * save or a new group, the roster is reloaded so every screen shows the new students.
 */
final class ClassListApi {

    /** The largest file accepted on the phone (the server's limit is 5 MB of base64). */
    static final int MAX_FILE_BYTES = 3 * 1024 * 1024;

    interface Callback<T> {
        void onResult(RosterApi.Outcome outcome, T value);
    }

    static final class File {
        final byte[] bytes;
        final String fileName;

        File(byte[] bytes, String fileName) {
            this.bytes = bytes;
            this.fileName = fileName;
        }
    }

    private static ClassListApi instance;

    private final Executor background = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    static synchronized ClassListApi get() {
        if (instance == null) instance = new ClassListApi();
        return instance;
    }

    private ClassListApi() {}

    /** apply false: what would happen. apply true: save it (the server checks everything again). */
    void importFile(byte[] bytes, String fileName, boolean apply, Callback<ClassListImport> done) {
        run(done, () -> {
            JSONObject body = new JSONObject()
                    .put("fileName", fileName == null ? "class-list.xlsx" : fileName)
                    .put("fileBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    .put("apply", apply);
            ApiClient.Response res = ApiClient.post("/students/import", body, Session.token());
            if (!res.ok()) return new Answer<>(RosterApi.Outcome.of(res), null);
            return new Answer<>(RosterApi.Outcome.OK, ClassListImport.from(res.body));
        }, apply);
    }

    void download(Callback<File> done) {
        run(done, () -> {
            ApiClient.Download d = ApiClient.download("/students/export", Session.token());
            if (!d.ok()) return new Answer<>(RosterApi.Outcome.of(d.error), null);
            String name = d.fileName != null ? d.fileName : "Mulungushi-Registrar-groups.xlsx";
            return new Answer<>(RosterApi.Outcome.OK, new File(d.bytes, name));
        }, false);
    }

    /** The new group's code, e.g. "G15". */
    void addGroup(Callback<String> done) {
        run(done, () -> {
            ApiClient.Response res = ApiClient.post("/groups", new JSONObject(), Session.token());
            if (!res.ok()) return new Answer<>(RosterApi.Outcome.of(res), null);
            JSONObject g = res.body.optJSONObject("group");
            return new Answer<>(RosterApi.Outcome.OK, g == null ? null : g.optString("groupCode", null));
        }, true);
    }

    // ---------------------------------------------------------------- plumbing

    private static final class Answer<T> {
        final RosterApi.Outcome outcome;
        final T value;

        Answer(RosterApi.Outcome outcome, T value) {
            this.outcome = outcome;
            this.value = value;
        }
    }

    private interface Work<T> {
        Answer<T> run() throws IOException, JSONException;
    }

    private <T> void run(Callback<T> done, Work<T> work, boolean changesRoster) {
        background.execute(() -> {
            Answer<T> a;
            try {
                a = work.run();
            } catch (IOException e) {
                a = new Answer<>(RosterApi.Outcome.OFFLINE, null);
            } catch (JSONException | RuntimeException e) {
                a = new Answer<>(new RosterApi.Outcome(false, RosterApi.Outcome.SERVER, null), null);
            }
            final Answer<T> answer = a;
            main.post(() -> {
                // the new students and groups appear on every screen; the callback doesn't wait for it
                if (answer.outcome.ok && changesRoster) RosterApi.get().loadRoster(null);
                done.onResult(answer.outcome, answer.value);
            });
        });
    }
}
