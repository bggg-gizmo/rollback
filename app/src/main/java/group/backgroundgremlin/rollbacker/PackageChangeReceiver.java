package group.backgroundgremlin.rollbacker;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PersistableBundle;

public final class PackageChangeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getData() == null) return;
        String packageName = intent.getData().getSchemeSpecificPart();
        if (packageName == null || packageName.equals(context.getPackageName())) return;

        String action = intent.getAction();
        boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
        SharedPreferences prefs = SnapshotManager.prefs(context);

        if (Intent.ACTION_PACKAGE_REMOVED.equals(action) ||
                Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action)) {
            if (!replacing) {
                SnapshotManager.forgetPackageObserved(context, packageName);
                String pending = prefs.getString(AppConstants.PREF_PENDING_PACKAGE, null);
                String stage = prefs.getString(AppConstants.PREF_PENDING_STAGE, null);
                long since = prefs.getLong(AppConstants.PREF_PENDING_SINCE, 0L);
                long age = since <= 0L ? Long.MAX_VALUE : System.currentTimeMillis() - since;
                boolean fresh = age >= 0L && age <= AppConstants.PENDING_OPERATION_TIMEOUT_MS;
                if (packageName.equals(pending) &&
                        AppConstants.PENDING_STAGE_AWAIT_UNINSTALL.equals(stage) && fresh) {
                    String label = packageName;
                    for (SnapshotInfo s : SnapshotManager.listSnapshotsForPackage(context, packageName)) {
                        label = s.appLabel;
                        break;
                    }
                    Notifications.completeRollback(context, label);
                } else if (packageName.equals(pending) && !fresh) {
                    prefs.edit()
                            .remove(AppConstants.PREF_PENDING_PACKAGE)
                            .remove(AppConstants.PREF_PENDING_SNAPSHOT)
                            .remove(AppConstants.PREF_PENDING_STAGE)
                            .remove(AppConstants.PREF_PENDING_SINCE)
                            .apply();
                }
            }
            return;
        }

        if (!prefs.getBoolean(AppConstants.PREF_AUTO_CAPTURE, true)) {
            SnapshotManager.markPackageObserved(context, packageName);
            return;
        }

        if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
            if (replacing) return;
            if (!prefs.getBoolean(AppConstants.PREF_CAPTURE_INSTALLS, true)) {
                SnapshotManager.markPackageObserved(context, packageName);
                return;
            }
        } else if (Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
            if (!prefs.getBoolean(AppConstants.PREF_CAPTURE_UPDATES, true)) {
                SnapshotManager.markPackageObserved(context, packageName);
                return;
            }
        } else {
            return;
        }

        scheduleSnapshot(context, packageName);
    }

    private static void scheduleSnapshot(Context context, String packageName) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        PersistableBundle extras = new PersistableBundle();
        extras.putString("packageName", packageName);
        int jobId = 0x40000000 | (packageName.hashCode() & 0x3fffffff);
        JobInfo job = new JobInfo.Builder(jobId, new ComponentName(context, SnapshotJobService.class))
                .setMinimumLatency(1200L)
                .setOverrideDeadline(12000L)
                .setExtras(extras)
                .build();
        scheduler.schedule(job);
    }
}
