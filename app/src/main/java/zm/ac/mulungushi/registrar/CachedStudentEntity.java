package zm.ac.mulungushi.registrar;

import androidx.annotation.NonNull;
import androidx.room.Entity;

/** One student of the saved roster. The readable columns are there for the Database Inspector. */
@Entity(tableName = "cached_students", primaryKeys = {"accountId", "studentId"})
public class CachedStudentEntity {
    @NonNull public String accountId = "";
    @NonNull public String studentId = "";
    public int position;          // the server's order
    public String studentNumber;
    public String fullName;
    public String groupCode;
    public String json;           // the record exactly as the server sent it
}
