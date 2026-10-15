package zm.ac.mulungushi.registrar;

import androidx.annotation.NonNull;
import androidx.room.Entity;

/** Any other saved server answer, by name. */
@Entity(tableName = "cache_entries", primaryKeys = {"accountId", "name"})
public class CacheEntryEntity {
    @NonNull public String accountId = "";
    @NonNull public String name = "";
    public String json;
    public long savedAt;
}
