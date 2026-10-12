package zm.ac.mulungushi.registrar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Remote storage: one HTTP call to the backend's POST {BASE_URL}/students/sync.
 * Sends this phone's changed students, gets back every record the server holds plus the
 * records it refused (with the reason). Blocking: call it off the main thread.
 */
final class ApiRemoteStorage {
    private ApiRemoteStorage() {}

    /** What the server answered. */
    static final class Result {
        final JSONArray students;
        /** [{id, error, message}] for records the server refused. They stay on the phone, waiting to upload. */
        final JSONArray rejected;

        Result(JSONArray students, JSONArray rejected) {
            this.students = students;
            this.rejected = rejected;
        }
    }

    static String syncUrl() {
        return ApiConfig.BASE_URL + "/students/sync";
    }

    static Result sync(JSONArray changedStudents) throws Exception {
        URL url = new URL(syncUrl());
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("X-Sync-Key", RemoteConfig.SYNC_KEY);
            c.setDoOutput(true);
            JSONObject body = new JSONObject();
            body.put("students", changedStudents);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code != 200) {
                String detail = "";
                try {
                    JSONObject err = new JSONObject(read(c.getErrorStream()));
                    detail = ": " + err.optString("error", "") + " " + err.optString("message", "");
                } catch (Exception ignored) {
                    // no JSON error body
                }
                throw new IOException("Server replied HTTP " + code + detail);
            }
            JSONObject reply = new JSONObject(read(c.getInputStream()));
            JSONArray rejected = reply.optJSONArray("rejected");
            return new Result(reply.getJSONArray("students"), rejected == null ? new JSONArray() : rejected);
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = s.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
