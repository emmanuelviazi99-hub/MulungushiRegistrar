package zm.ac.mulungushi.registrar;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/** Every query the app runs on its database (see RegistrarDatabase). Call off the main thread. */
@Dao
public interface RegistrarDao {

    @Query("DELETE FROM cached_students WHERE accountId = :accountId")
    void deleteStudents(String accountId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertStudents(List<CachedStudentEntity> rows);

    @Query("SELECT * FROM cached_students WHERE accountId = :accountId ORDER BY position")
    List<CachedStudentEntity> students(String accountId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putEntry(CacheEntryEntity entry);

    @Query("SELECT * FROM cache_entries WHERE accountId = :accountId AND name = :name")
    CacheEntryEntity entry(String accountId, String name);

    @Query("DELETE FROM cache_entries WHERE accountId = :accountId")
    void deleteEntries(String accountId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putOp(PendingOpEntity op);

    @Query("SELECT COUNT(*) FROM pending_operations WHERE opId = :opId")
    int countOp(String opId);

    @Query("SELECT * FROM pending_operations WHERE accountId = :accountId ORDER BY seq")
    List<PendingOpEntity> ops(String accountId);

    @Query("SELECT MAX(seq) FROM pending_operations WHERE accountId = :accountId")
    Long lastSeq(String accountId);

    @Query("DELETE FROM pending_operations WHERE opId = :opId")
    void removeOp(String opId);

    @Query("DELETE FROM pending_operations WHERE accountId = :accountId")
    void deleteOps(String accountId);
}
