package group.backgroundgremlin.rollbacker;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

final class BackgroundCaptureScheduler {
    private BackgroundCaptureScheduler() {}

    static void ensureScheduled(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;

        ComponentName service = new ComponentName(context, SnapshotSweepJobService.class);
        JobInfo existing = scheduler.getPendingJob(AppConstants.JOB_PERIODIC_SWEEP);
        if (existing != null && service.equals(existing.getService()) &&
                existing.getIntervalMillis() == AppConstants.PERIODIC_SWEEP_MS &&
                existing.getFlexMillis() == AppConstants.PERIODIC_SWEEP_FLEX_MS &&
                existing.isPersisted()) {
            return;
        }

        JobInfo job = new JobInfo.Builder(
                AppConstants.JOB_PERIODIC_SWEEP,
                service)
                .setPeriodic(AppConstants.PERIODIC_SWEEP_MS, AppConstants.PERIODIC_SWEEP_FLEX_MS)
                .setPersisted(true)
                .build();
        scheduler.schedule(job);
    }
}
