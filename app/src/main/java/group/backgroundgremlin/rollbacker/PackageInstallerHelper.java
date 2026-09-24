package group.backgroundgremlin.rollbacker;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class PackageInstallerHelper {
    private PackageInstallerHelper() {}

    static void installSnapshot(Context context, SnapshotInfo snapshot) throws Exception {
        if (snapshot == null || snapshot.folder == null) {
            throw new IllegalArgumentException("No snapshot was selected.");
        }
        if (!SnapshotManager.verifySnapshot(snapshot)) {
            throw new IOException("Snapshot integrity check failed. The APK set was not installed.");
        }

        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(snapshot.packageName);
        params.setSize(snapshot.totalBytes);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        }

        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = null;
        try {
            session = installer.openSession(sessionId);
            for (SnapshotInfo.ApkPart part : snapshot.parts) {
                File apk = new File(snapshot.folder, part.fileName);
                writeApk(session, apk, part.fileName, part.bytes);
            }

            Intent callback = new Intent(context, InstallStatusReceiver.class);
            callback.putExtra("packageName", snapshot.packageName);
            callback.putExtra("appLabel", snapshot.appLabel);
            callback.putExtra("versionName", snapshot.versionName);
            callback.putExtra("versionCode", snapshot.versionCode);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context,
                    Math.abs((snapshot.packageName + snapshot.captureTime).hashCode()),
                    callback,
                    flags);
            session.commit(pendingIntent.getIntentSender());
        } catch (Exception e) {
            try {
                installer.abandonSession(sessionId);
            } catch (Exception ignored) {
            }
            throw e;
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    private static void writeApk(PackageInstaller.Session session, File apk, String name, long length)
            throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(apk), 1024 * 1024);
             OutputStream out = session.openWrite(name, 0, length)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            session.fsync(out);
        }
    }
}
