package zm.ac.mulungushi.registrar;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * For a student who turned alerts on: every 15 minutes (the shortest interval
 * Android allows for background work), when the phone is online, uploads anything
 * waiting and checks whether a lecturer has answered their requests. A new answer
 * becomes an Android notification, even with the app closed. Each answer is
 * announced once (Notices).
 */
public class AnswerCheckWorker extends Worker {

    static final String UNIQUE_NAME = "registrar-answer-check";

    public AnswerCheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (!Session.isStudent() || !Notifier.ON.equals(Notifier.pref)) return Result.success();
        RosterApi.SyncReport report = RosterApi.get().syncNowBlocking();
        if (report.offline) return Result.success(); // try again at the next interval
        String studentId = Session.studentId();
        for (StudentRepository.RequestRecord r : RosterApi.get().savedLatestRequests()) {
            if (r == null || StudentRepository.RequestRecord.PENDING.equals(r.status)) continue;
            if (!Notices.isNew(studentId, r)) continue;
            Notices.markSeen(studentId, r.kind, r.requestId);
            Notifier.alert(getApplicationContext(), Notifier.noticeText(getApplicationContext(), r.status, r.kind));
        }
        return Result.success();
    }

    static void schedule(Context context) {
        Constraints online = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(AnswerCheckWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(online)
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, work);
    }

    static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME);
    }
}
