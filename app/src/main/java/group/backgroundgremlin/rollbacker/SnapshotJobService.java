package group.backgroundgremlin.rollbacker;

import android.app.job.JobParameters;
import android.app.job.JobService;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SnapshotJobService extends JobService {
    private final ConcurrentHashMap<Integer, Thread> workers = new ConcurrentHashMap<>();
    private final Set<Integer> stoppedJobs = Collections.newSetFromMap(
            new ConcurrentHashMap<Integer, Boolean>());

    @Override
    public boolean onStartJob(final JobParameters params) {
        final String packageName = params.getExtras().getString("packageName", null);
        if (packageName == null || packageName.trim().isEmpty()) {
            return false;
        }

        final int jobId = params.getJobId();
        stoppedJobs.remove(jobId);
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SnapshotManager.SnapshotResult result =
                            SnapshotManager.capturePackage(SnapshotJobService.this, packageName);
                    if (result.success && !result.skipped && result.snapshot != null) {
                        Notifications.captureSaved(SnapshotJobService.this, result.snapshot);
                    }
                } catch (RuntimeException ignored) {
                } finally {
                    Thread current = Thread.currentThread();
                    if (workers.remove(jobId, current) && !stoppedJobs.remove(jobId)) {
                        jobFinished(params, false);
                    }
                }
            }
        }, "Rollbacker-SnapshotJob-" + jobId);
        workers.put(jobId, thread);
        thread.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        int jobId = params.getJobId();
        stoppedJobs.add(jobId);
        Thread thread = workers.remove(jobId);
        if (thread != null) {
            thread.interrupt();
        }
        return true;
    }
}
