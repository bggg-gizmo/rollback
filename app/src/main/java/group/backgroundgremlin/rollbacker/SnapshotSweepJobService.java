package group.backgroundgremlin.rollbacker;

import android.app.job.JobParameters;
import android.app.job.JobService;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SnapshotSweepJobService extends JobService {
    private final ConcurrentHashMap<Integer, Thread> workers = new ConcurrentHashMap<>();
    private final Set<Integer> stoppedJobs = Collections.newSetFromMap(
            new ConcurrentHashMap<Integer, Boolean>());

    @Override
    public boolean onStartJob(final JobParameters params) {
        final boolean autoCapture = SnapshotManager.prefs(this)
                .getBoolean(AppConstants.PREF_AUTO_CAPTURE, true);
        final int jobId = params.getJobId();
        stoppedJobs.remove(jobId);
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (autoCapture) {
                        SnapshotManager.SweepResult result = SnapshotManager.reconcileInstalledPackages(
                                SnapshotSweepJobService.this);
                        for (SnapshotInfo snapshot : result.captured) {
                            if (Thread.currentThread().isInterrupted()) break;
                            Notifications.captureSaved(SnapshotSweepJobService.this, snapshot);
                        }
                    } else {
                        SnapshotManager.refreshObservedPackages(SnapshotSweepJobService.this);
                    }
                } catch (RuntimeException ignored) {
                } finally {
                    Thread current = Thread.currentThread();
                    if (workers.remove(jobId, current) && !stoppedJobs.remove(jobId)) {
                        jobFinished(params, false);
                    }
                }
            }
        }, "Rollbacker-PeriodicSweep-" + jobId);
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
