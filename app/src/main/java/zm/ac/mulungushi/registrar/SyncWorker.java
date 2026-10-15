package zm.ac.mulungushi.registrar;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * Uploads the phone's waiting changes in the background (WorkManager).
 *
 * Scheduled whenever a change could not be uploaded straight away. WorkManager
 * runs it once the phone has a network connection, even if the app has been
 * closed or the phone restarted in between, and tries again later (30 s, then
 * longer each time) while the server cannot be reached. Every change carries its
 * own Idempotency-Key, so a run that is cut off half-way and repeated never
 * applies anything twice.
 */
public class SyncWorker extends Worker {

    static final String UNIQUE_NAME = "registrar-upload";

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        RosterApi.SyncReport report = RosterApi.get().syncNowBlocking();
        if (report.offline && report.waiting > 0) return Result.retry();
        return Result.success(); // done, or nothing more can be done until the person signs in again
    }

    /** Asks WorkManager to upload when there is a network connection. Does nothing if already asked. */
    static void schedule(Context context) {
        Constraints online = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, work);
    }

    static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME);
    }
}
