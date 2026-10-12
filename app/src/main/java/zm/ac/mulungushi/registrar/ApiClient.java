package zm.ac.mulungushi.registrar;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Sends JSON to the backend and reads the JSON answer. Plain Java (no Android
 * classes apart from org.json, which Android includes), so it works on a
 * background thread and can be tested without a device.
 *
 * Every backend answer has the same shape:
 *   success:  { "success": true, ... }
 *   failure:  { "success": false, "error": "BAD_CODE", "message": "...", ...extra fields }
 * so callers look at {@link Response#error()} rather than guessing from the HTTP status.
 *
 * Network problems (server off, wrong address, no Wi-Fi) throw IOException;
 * they are never treated as success.
 */
final class ApiClient {

    /** Where the backend is. Comes from ApiConfig; tests point it at a local server. */
    static String baseUrl = ApiConfig.BASE_URL;

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    // Asking for a code waits until the email has been handed to the mail server.
    private static final int READ_TIMEOUT_MS = 30_000;

    private ApiClient() {}

    static final class Response {
        final int status;
        final JSONObject body;

        Response(int status, JSONObject body) {
            this.status = status;
            this.body = body;
        }

        boolean ok() {
            return status >= 200 && status < 300;
        }

        /** The backend's error code, e.g. "BAD_CODE", or "" on success. */
        String error() {
            return body.optString("error", "");
        }

        /** The backend's human-readable message, or null. */
        String message() {
            String m = body.optString("message", "");
            return m.isEmpty() ? null : m;
        }
    }

    static Response post(String path, JSONObject json, String token) throws IOException, JSONException {
        return send("POST", path, json, token);
    }

    static Response get(String path, String token) throws IOException, JSONException {
        return send("GET", path, null, token);
    }

    static Response put(String path, JSONObject json, String token) throws IOException, JSONException {
        return send("PUT", path, json == null ? new JSONObject() : json, token);
    }

    static Response delete(String path, String token) throws IOException, JSONException {
        return send("DELETE", path, null, token);
    }

    private static Response send(String method, String path, JSONObject json, String token)
            throws IOException, JSONException {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            if (token != null && !token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
            if (json != null) {
                byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                // Not streamed on purpose: some HttpURLConnection versions drop the body of a
                // 401 answer to a streamed request, and that body says why sign-in failed.
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = in == null ? "" : readAll(in).trim();
            // Anything that is not a JSON object (an HTML error page from a wrong address,
            // an empty body) becomes an empty object, so error() is "" and the caller
            // reports a server problem instead of crashing.
            JSONObject body = text.startsWith("{") ? new JSONObject(text) : new JSONObject();
            return new Response(status, body);
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = input.read(buf)) != -1) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
