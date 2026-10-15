package zm.ac.mulungushi.registrar;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** A change waiting to upload, or refused by the server (see PendingOp). */
@Entity(tableName = "pending_operations", indices = {@Index("accountId")})
public class PendingOpEntity {
    @PrimaryKey @NonNull public String opId = "";
    public String accountId;
    public long seq;
    public String type;
    public String targetId;
    public String kind;
    public String body;
    public int baseVersion;
    public String label;
    public long createdAt;
    public String state;
    public String error;
    public String message;
    public int tries;
}
