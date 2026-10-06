package zm.ac.mulungushi.registrar;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Temporary debug helper. Saves the stack trace of any crash, and the next time the app
 * opens it shows the trace on screen so it can be photographed. Remove once the crashes are fixed.
 */
final class CrashLog {

    private static final String FILE = "last_crash.txt";

    private CrashLog() {}

    static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                try (FileOutputStream out = new FileOutputStream(new File(app.getFilesDir(), FILE))) {
                    out.write(sw.toString().getBytes("UTF-8"));
                }
            } catch (Throwable ignored) {
                // nothing more we can do
            }
            if (previous != null) previous.uncaughtException(t, e);
        });
    }

    /** Shows the saved crash, if any, then runs {@code next}. */
    static void showIfAny(Activity a, Runnable next) {
        String trace = read(a);
        if (trace == null) { next.run(); return; }
        new File(a.getFilesDir(), FILE).delete();

        String shown = trace.length() > 3500 ? trace.substring(0, 3500) : trace;
        TextView tv = new TextView(a);
        tv.setText(shown);
        tv.setTextSize(10f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        int pad = RosterFormat.dp(a, 16);
        tv.setPadding(pad, pad, pad, pad);
        ScrollView sv = new ScrollView(a);
        sv.addView(tv);

        new AlertDialog.Builder(a)
                .setTitle("Last crash")
                .setView(sv)
                .setPositiveButton("Continue", (d, w) -> next.run())
                .setNeutralButton("Copy", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("crash", trace));
                    Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show();
                    next.run();
                })
                .setOnCancelListener(d -> next.run())
                .show();
    }

    private static String read(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = in.read(b);
            return new String(b, 0, Math.max(n, 0), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }
}
