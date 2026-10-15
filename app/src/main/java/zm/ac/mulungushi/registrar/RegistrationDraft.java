package zm.ac.mulungushi.registrar;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The registration details saved on the phone while offline (page 1 of Register,
 * student). Kept in the app's private storage and left out of backups
 * (res/xml/*_rules.xml). Never stores a password or a code. Cleared when
 * registration finishes.
 */
final class RegistrationDraft {

    private static final String FILE = "registration_draft";

    static final class Draft {
        final String name, number, phone, nrc, email, dateOfBirth;

        Draft(String name, String number, String phone, String nrc, String email, String dateOfBirth) {
            this.name = name;
            this.number = number;
            this.phone = phone;
            this.nrc = nrc;
            this.email = email;
            this.dateOfBirth = dateOfBirth;
        }
    }

    private RegistrationDraft() {}

    /** Saves what was typed. Does nothing if every field is empty. */
    static void save(Context c, String name, String number, String phone, String nrc, String email, String dateOfBirth) {
        if (name.isEmpty() && number.isEmpty() && phone.isEmpty() && nrc.isEmpty() && email.isEmpty()
                && dateOfBirth.isEmpty()) return;
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("name", name)
                .putString("number", number)
                .putString("phone", phone)
                .putString("nrc", nrc)
                .putString("email", email)
                .putString("dob", dateOfBirth)
                .apply();
    }

    /** Returns the saved draft, or null if there is none. */
    static Draft load(Context c) {
        SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        if (!p.contains("name")) return null;
        return new Draft(p.getString("name", ""), p.getString("number", ""), p.getString("phone", ""),
                p.getString("nrc", ""), p.getString("email", ""), p.getString("dob", ""));
    }

    static void clear(Context c) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
