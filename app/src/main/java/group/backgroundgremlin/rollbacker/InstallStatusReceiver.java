package group.backgroundgremlin.rollbacker;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

public final class InstallStatusReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String packageName = intent.getStringExtra("packageName");
        String appLabel = intent.getStringExtra("appLabel");
        String versionName = intent.getStringExtra("versionName");
        long versionCode = intent.getLongExtra("versionCode", -1L);
        if (appLabel == null || appLabel.trim().isEmpty()) {
            appLabel = packageName == null ? "App" : packageName;
        }
        if (versionName == null || versionName.trim().isEmpty()) {
            versionName = "saved release";
        }

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = getConfirmationIntent(intent);
            if (confirmation == null) {
                clearPending(context);
                postNotification(context, "Rollback install failed",
                        appLabel + ": Android did not provide an installation confirmation intent.",
                        null);
                return;
            }
            confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(confirmation);
                return;
            } catch (RuntimeException ignored) {
                PendingIntent approval = buildApprovalPendingIntent(context, confirmation, packageName, versionCode);
                postNotification(context, "Complete rollback install",
                        "Tap to approve installation of " + appLabel + " " + versionName + ".",
                        approval);
                return;
            }
        }

        clearPending(context);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            postNotification(context, "Rollback installed",
                    appLabel + " " + versionName + " (" + versionCode + ") is installed.",
                    null);
        } else {
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (message == null || message.trim().isEmpty()) {
                message = "Android reported install status " + status + ".";
            }
            postNotification(context, "Rollback install failed", appLabel + ": " + message, null);
        }
    }

    @SuppressWarnings("deprecation")
    private static Intent getConfirmationIntent(Intent callback) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return callback.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        }
        return callback.getParcelableExtra(Intent.EXTRA_INTENT);
    }

    private static PendingIntent buildApprovalPendingIntent(
            Context context, Intent confirmation, String packageName, long versionCode) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        String identity = (packageName == null ? "rollback" : packageName) + ":" + versionCode;
        return PendingIntent.getActivity(context, Math.abs(identity.hashCode()), confirmation, flags);
    }

    private static void clearPending(Context context) {
        SnapshotManager.prefs(context).edit()
                .remove(AppConstants.PREF_PENDING_PACKAGE)
                .remove(AppConstants.PREF_PENDING_SNAPSHOT)
                .remove(AppConstants.PREF_PENDING_STAGE)
                .remove(AppConstants.PREF_PENDING_SINCE)
                .apply();
    }

    private static void postNotification(
            Context context, String title, String text, PendingIntent overrideContent) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        PendingIntent content = overrideContent;
        if (content == null) {
            Intent launch = new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            content = PendingIntent.getActivity(context, title.hashCode(), launch, flags);
        }

        Notification.Builder builder = new Notification.Builder(context, AppConstants.CHANNEL_INSTALLS);
        builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(content);
        try {
            nm.notify(AppConstants.NOTIFICATION_INSTALL_BASE + Math.abs(title.hashCode() % 800), builder.build());
        } catch (SecurityException ignored) {
        }
    }
}
