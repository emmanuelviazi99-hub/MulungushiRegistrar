package zm.ac.mulungushi.registrar;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * The app's database on the phone (Room, file databases/registrar.db).
 *
 *   cached_students     the roster as the server last sent it (lecturer)
 *   cache_entries       other saved server answers: group totals, open and answered
 *                       requests, a student's own record, requests and group members
 *   pending_operations  the outbox: changes made on this phone, waiting to upload or refused
 *
 * Every row belongs to one account (accountId), so two people who share a phone never
 * see each other's data, and signing out deletes that account's rows. Excluded from
 * Android backups (res/xml/*_rules.xml). Used through RoomLocalStore.
 */
@Database(entities = {
        CachedStudentEntity.class,
        CacheEntryEntity.class,
        PendingOpEntity.class
}, version = 1, exportSchema = false)
public abstract class RegistrarDatabase extends RoomDatabase {

    public static final String FILE = "registrar.db";

    public abstract RegistrarDao dao();

    private static volatile RegistrarDatabase instance;

    public static RegistrarDatabase get(Context context) {
        if (instance == null) {
            synchronized (RegistrarDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(context.getApplicationContext(), RegistrarDatabase.class, FILE)
                            .build();
                }
            }
        }
        return instance;
    }
}
