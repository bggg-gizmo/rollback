package group.backgroundgremlin.rollbacker;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

final class Notifications {
    private Notifications() {}

    static void captureSaved(Context context, SnapshotInfo info) {
        if (!SnapshotManager.prefs(context).getBoolean(AppConstants.PREF_NOTIFY_CAPTURE, true)) return;
        String text = info.appLabel + " " + info.versionName + " is protected (" + SnapshotManager.humanBytes(info.totalBytes) + ").";
        post(context, AppConstants.CHANNEL_EVENTS, AppConstants.NOTIFICATION_CAPTURE_BASE + Math.abs(info.packageName.hashCode() % 700),
                "Release captured", text);
    }

    static void completeRollback(Context context, String appLabel) {
        String text = "The newer app was removed. Open Roll Backer to install the selected saved release of " + appLabel + ".";
        post(context, AppConstants.CHANNEL_INSTALLS, AppConstants.NOTIFICATION_INSTALL_BASE + 901,
                "Complete rollback", text);
    }

    private static void post(Context context, String channel, int id, String title, String text) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent launch = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(context, id, launch, flags);
        Notification.Builder builder = new Notification.Builder(context, channel);
        builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(content);
        try {
            nm.notify(id, builder.build());
        } catch (SecurityException ignored) {
        }
    }
}
