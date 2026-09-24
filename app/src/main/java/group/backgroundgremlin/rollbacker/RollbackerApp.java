package group.backgroundgremlin.rollbacker;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

public final class RollbackerApp extends Application {
    private PackageChangeReceiver livePackageReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        SnapshotManager.ensureVault(this);
        SnapshotManager.cleanupOrphanedCaptureFolders(this);
        SnapshotManager.initializeObservedPackages(this);
        createNotificationChannels();
        BackgroundCaptureScheduler.ensureScheduled(this);
        registerLivePackageReceiver();
    }

    private void registerLivePackageReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        filter.addDataScheme("package");

        livePackageReceiver = new PackageChangeReceiver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(livePackageReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(livePackageReceiver, filter);
        }
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel events = new NotificationChannel(
                AppConstants.CHANNEL_EVENTS,
                "Snapshot activity",
                NotificationManager.IMPORTANCE_LOW);
        events.setDescription("Notifications when Roll Backer captures an app release.");
        nm.createNotificationChannel(events);

        NotificationChannel installs = new NotificationChannel(
                AppConstants.CHANNEL_INSTALLS,
                "Rollback installs",
                NotificationManager.IMPORTANCE_HIGH);
        installs.setDescription("Prompts and results for app reinstall and rollback operations.");
        nm.createNotificationChannel(installs);
    }
}
