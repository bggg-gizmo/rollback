package group.backgroundgremlin.rollbacker;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class SnapshotManager {
    static final String VAULT_DIR = "RollbackVault";
    static final String METADATA_FILE = "metadata.json";
    private static final long MAX_METADATA_BYTES = 4L * 1024L * 1024L;
    private static final int MAX_APK_PARTS = 512;

    private SnapshotManager() {}

    static final class SnapshotResult {
        final boolean success;
        final boolean skipped;
        final String message;
        final SnapshotInfo snapshot;

        SnapshotResult(boolean success, boolean skipped, String message, SnapshotInfo snapshot) {
            this.success = success;
            this.skipped = skipped;
            this.message = message;
            this.snapshot = snapshot;
        }
    }

    static final class InstalledApp {
        String label;
        String packageName;
        String versionName;
        long versionCode;
        long firstInstallTime;
        long lastUpdateTime;
        boolean systemApp;
        Drawable icon;
        long installedBytes;
        int snapshotCount;
    }

    static final class VaultStats {
        int appCount;
        int releaseCount;
        long bytes;
    }

    static final class SweepResult {
        final List<SnapshotInfo> captured = new ArrayList<>();
        int alreadyCurrent;
        int policySkipped;
        int failed;
    }

    private static final class ObservedPackageState {
        long versionCode;
        long firstInstallTime;
        long lastUpdateTime;

        boolean matches(InstalledApp app) {
            return app != null && versionCode == app.versionCode &&
                    firstInstallTime == app.firstInstallTime &&
                    lastUpdateTime == app.lastUpdateTime;
        }
    }

    private static final String OBSERVED_INITIALIZED = "__initialized__";

    static File ensureVault(Context context) {
        File root = new File(context.getFilesDir(), VAULT_DIR);
        if (!root.exists() && !root.mkdirs()) {
            throw new IllegalStateException("Unable to create Roll Backer vault: " + root.getAbsolutePath());
        }
        return root;
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(AppConstants.PREFS, Context.MODE_PRIVATE);
    }

    static synchronized void initializeObservedPackages(Context context) {
        SharedPreferences observed = context.getSharedPreferences(
                AppConstants.OBSERVED_PACKAGES_PREFS, Context.MODE_PRIVATE);
        if (observed.getBoolean(OBSERVED_INITIALIZED, false)) {
            return;
        }
        refreshObservedPackages(context);
    }

    static synchronized void refreshObservedPackages(Context context) {
        SharedPreferences observed = context.getSharedPreferences(
                AppConstants.OBSERVED_PACKAGES_PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = observed.edit().clear();
        PackageManager pm = context.getPackageManager();
        for (PackageInfo pi : getInstalledPackages(pm)) {
            if (pi == null || pi.packageName == null || pi.packageName.equals(context.getPackageName())) {
                continue;
            }
            editor.putString(pi.packageName, observationValue(
                    getVersionCode(pi), pi.firstInstallTime, pi.lastUpdateTime));
        }
        editor.putBoolean(OBSERVED_INITIALIZED, true).apply();
    }

    static void markPackageObserved(Context context, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return;
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(packageName, 0);
            markPackageObserved(context, pi);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
    }

    static void forgetPackageObserved(Context context, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return;
        context.getSharedPreferences(AppConstants.OBSERVED_PACKAGES_PREFS, Context.MODE_PRIVATE)
                .edit().remove(packageName).apply();
    }

    private static void markPackageObserved(Context context, PackageInfo pi) {
        if (pi == null || pi.packageName == null || pi.packageName.equals(context.getPackageName())) return;
        context.getSharedPreferences(AppConstants.OBSERVED_PACKAGES_PREFS, Context.MODE_PRIVATE)
                .edit().putString(pi.packageName, observationValue(
                        getVersionCode(pi), pi.firstInstallTime, pi.lastUpdateTime)).apply();
    }

    private static void markPackageObserved(Context context, InstalledApp app) {
        if (app == null || app.packageName == null) return;
        context.getSharedPreferences(AppConstants.OBSERVED_PACKAGES_PREFS, Context.MODE_PRIVATE)
                .edit().putString(app.packageName, observationValue(
                        app.versionCode, app.firstInstallTime, app.lastUpdateTime)).apply();
    }

    private static ObservedPackageState observedPackageState(Context context, String packageName) {
        String value = context.getSharedPreferences(
                AppConstants.OBSERVED_PACKAGES_PREFS, Context.MODE_PRIVATE)
                .getString(packageName, null);
        if (value == null) return null;
        String[] fields = value.split("\\|", -1);
        if (fields.length != 3) return null;
        try {
            ObservedPackageState state = new ObservedPackageState();
            state.versionCode = Long.parseLong(fields[0]);
            state.firstInstallTime = Long.parseLong(fields[1]);
            state.lastUpdateTime = Long.parseLong(fields[2]);
            return state;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String observationValue(long versionCode, long firstInstallTime, long lastUpdateTime) {
        return versionCode + "|" + firstInstallTime + "|" + lastUpdateTime;
    }

    private static void pruneMissingObservedPackages(Context context) {
        Map<String, Boolean> installed = new HashMap<>();
        for (PackageInfo pi : getInstalledPackages(context.getPackageManager())) {
            if (pi != null && pi.packageName != null) {
                installed.put(pi.packageName, Boolean.TRUE);
            }
        }
        SharedPreferences observed = context.getSharedPreferences(
                AppConstants.OBSERVED_PACKAGES_PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = null;
        for (String key : observed.getAll().keySet()) {
            if (OBSERVED_INITIALIZED.equals(key)) continue;
            if (!installed.containsKey(key)) {
                if (editor == null) editor = observed.edit();
                editor.remove(key);
            }
        }
        if (editor != null) editor.apply();
    }

    private static List<PackageInfo> getInstalledPackages(PackageManager pm) {
        try {
            return pm.getInstalledPackages(PackageManager.MATCH_DISABLED_COMPONENTS);
        } catch (RuntimeException e) {
            return pm.getInstalledPackages(0);
        }
    }

    static synchronized SnapshotResult capturePackage(Context context, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return new SnapshotResult(false, false, "No package name was provided.", null);
        }
        if (packageName.equals(context.getPackageName())) {
            return new SnapshotResult(false, true, "Roll Backer does not archive itself.", null);
        }

        PackageManager pm = context.getPackageManager();
        PackageInfo pi;
        try {
            pi = pm.getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return new SnapshotResult(false, false, "The app is no longer installed.", null);
        }
        ApplicationInfo ai = pi.applicationInfo;
        if (ai == null || ai.sourceDir == null) {
            return new SnapshotResult(false, false, "Android did not expose an installed APK path for this app.", null);
        }

        boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        boolean updatedSystem = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        if ((system || updatedSystem) && !prefs(context).getBoolean(AppConstants.PREF_INCLUDE_SYSTEM, false)) {
            markPackageObserved(context, pi);
            return new SnapshotResult(false, true, "System apps are excluded by the current settings.", null);
        }

        String label = String.valueOf(pm.getApplicationLabel(ai));
        String versionName = pi.versionName == null ? "unnamed" : pi.versionName;
        long versionCode = getVersionCode(pi);
        long captureTime = System.currentTimeMillis();
        long lastUpdateTime = pi.lastUpdateTime;

        List<SnapshotInfo> existing = listSnapshotsForPackage(context, packageName);
        for (SnapshotInfo s : existing) {
            if (s.versionCode == versionCode && s.lastUpdateTime == lastUpdateTime && verifySnapshot(s)) {
                markPackageObserved(context, pi);
                return new SnapshotResult(true, true, "This exact installed release is already in the vault.", s);
            }
        }

        File root = ensureVault(context);
        File appFolder = resolveAppFolder(root, label, packageName);
        if (!appFolder.exists() && !appFolder.mkdirs()) {
            return new SnapshotResult(false, false, "Unable to create the app vault folder.", null);
        }
        saveIcon(pm, ai, appFolder);

        String date = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(captureTime));
        String releaseName = "v" + safeFileName(versionName) + "_" + versionCode + "_" + date;
        File tempFolder = new File(appFolder, ".tmp_" + releaseName + "_" + captureTime);
        File finalFolder = new File(appFolder, releaseName);
        int suffix = 2;
        while (finalFolder.exists()) {
            finalFolder = new File(appFolder, releaseName + "_" + suffix++);
        }
        if (!tempFolder.mkdirs()) {
            return new SnapshotResult(false, false, "Unable to create a release folder in the vault.", null);
        }

        SnapshotInfo info = new SnapshotInfo();
        info.appLabel = label;
        info.packageName = packageName;
        info.versionName = versionName;
        info.versionCode = versionCode;
        info.captureTime = captureTime;
        info.lastUpdateTime = lastUpdateTime;
        info.appFolder = appFolder;
        info.iconFile = new File(appFolder, "icon.png");

        try {
            SnapshotInfo.ApkPart base = copyApkPart(new File(ai.sourceDir), new File(tempFolder, "base.apk"), true, null);
            info.parts.add(base);
            info.baseApkFile = base.fileName;
            info.totalBytes += base.bytes;

            String[] splitPaths = ai.splitSourceDirs;
            String[] splitNames = ai.splitNames;
            if (splitPaths != null) {
                for (int i = 0; i < splitPaths.length; i++) {
                    String splitName = splitNames != null && i < splitNames.length && splitNames[i] != null
                            ? splitNames[i]
                            : "split_" + (i + 1);
                    String destName = "split_" + safeFileName(splitName) + ".apk";
                    File destination = uniqueFile(tempFolder, destName);
                    SnapshotInfo.ApkPart part = copyApkPart(new File(splitPaths[i]), destination, false, splitName);
                    info.parts.add(part);
                    info.totalBytes += part.bytes;
                }
            }

            SnapshotInfo identical = findIdenticalVerifiedSnapshot(existing, info);
            if (identical != null) {
                deleteRecursively(tempFolder);
                markPackageObserved(context, pi);
                return new SnapshotResult(true, true,
                        "This installed APK set is already protected in the vault.", identical);
            }

            info.folder = tempFolder;
            writeMetadata(info, tempFolder);

            if (!tempFolder.renameTo(finalFolder)) {
                try {
                    copyDirectory(tempFolder, finalFolder);
                    deleteRecursively(tempFolder);
                } catch (Exception copyFailure) {
                    deleteRecursively(finalFolder);
                    throw copyFailure;
                }
            }
            info.folder = finalFolder;
            SnapshotInfo persisted = readMetadata(finalFolder);
            if (persisted == null) {
                deleteRecursively(finalFolder);
                throw new IOException("The completed snapshot metadata could not be read back.");
            }
            persisted.appFolder = appFolder;
            persisted.iconFile = new File(appFolder, "icon.png");
            if (!verifySnapshot(persisted)) {
                deleteRecursively(finalFolder);
                throw new IOException("The completed snapshot failed its post-write integrity check.");
            }
            enforceRetention(context, packageName);
            markPackageObserved(context, pi);
            return new SnapshotResult(true, false,
                    "Saved " + label + " " + versionName + " (" + versionCode + ") to the vault.", persisted);
        } catch (Exception e) {
            deleteRecursively(tempFolder);
            deleteRecursively(finalFolder);
            return new SnapshotResult(false, false, "Snapshot failed: " + safeMessage(e), null);
        }
    }

    static SweepResult reconcileInstalledPackages(Context context) {
        SweepResult result = new SweepResult();
        if (!prefs(context).getBoolean(AppConstants.PREF_AUTO_CAPTURE, true)) {
            return result;
        }

        initializeObservedPackages(context);
        pruneMissingObservedPackages(context);
        boolean captureInstalls = prefs(context).getBoolean(AppConstants.PREF_CAPTURE_INSTALLS, true);
        boolean captureUpdates = prefs(context).getBoolean(AppConstants.PREF_CAPTURE_UPDATES, true);
        List<SnapshotInfo> snapshots = listAllSnapshots(context);
        Map<String, List<SnapshotInfo>> byPackage = new HashMap<>();
        for (SnapshotInfo snapshot : snapshots) {
            List<SnapshotInfo> packageSnapshots = byPackage.get(snapshot.packageName);
            if (packageSnapshots == null) {
                packageSnapshots = new ArrayList<>();
                byPackage.put(snapshot.packageName, packageSnapshots);
            }
            packageSnapshots.add(snapshot);
        }

        for (InstalledApp app : listInstalledApps(context)) {
            if (Thread.currentThread().isInterrupted()) break;
            List<SnapshotInfo> packageSnapshots = byPackage.get(app.packageName);
            boolean exactSnapshot = false;
            if (packageSnapshots != null) {
                for (SnapshotInfo snapshot : packageSnapshots) {
                    if (snapshot.versionCode == app.versionCode &&
                            snapshot.lastUpdateTime == app.lastUpdateTime) {
                        exactSnapshot = true;
                        break;
                    }
                }
            }
            if (exactSnapshot) {
                markPackageObserved(context, app);
                result.alreadyCurrent++;
                continue;
            }

            ObservedPackageState observed = observedPackageState(context, app.packageName);
            if (observed != null && observed.matches(app)) {
                result.alreadyCurrent++;
                continue;
            }

            boolean detectedInstall = observed == null ||
                    observed.firstInstallTime != app.firstInstallTime;
            boolean allowed = detectedInstall ? captureInstalls : captureUpdates;
            if (!allowed) {
                markPackageObserved(context, app);
                result.policySkipped++;
                continue;
            }

            SnapshotResult capture = capturePackage(context, app.packageName);
            if (capture.success && !capture.skipped && capture.snapshot != null) {
                result.captured.add(capture.snapshot);
                List<SnapshotInfo> packageList = byPackage.get(app.packageName);
                if (packageList == null) {
                    packageList = new ArrayList<>();
                    byPackage.put(app.packageName, packageList);
                }
                packageList.add(capture.snapshot);
            } else if (capture.skipped) {
                markPackageObserved(context, app);
                result.alreadyCurrent++;
            } else {
                result.failed++;
            }
        }
        return result;
    }

    static List<InstalledApp> listInstalledApps(Context context) {
        PackageManager pm = context.getPackageManager();
        List<PackageInfo> packages = getInstalledPackages(pm);
        Map<String, Integer> snapshotCounts = countSnapshotsByPackage(context);
        List<InstalledApp> result = new ArrayList<>();
        boolean includeSystem = prefs(context).getBoolean(AppConstants.PREF_INCLUDE_SYSTEM, false);
        for (PackageInfo pi : packages) {
            ApplicationInfo ai = pi.applicationInfo;
            if (ai == null || pi.packageName.equals(context.getPackageName())) {
                continue;
            }
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            if (system && !includeSystem) {
                continue;
            }
            InstalledApp app = new InstalledApp();
            app.packageName = pi.packageName;
            app.label = String.valueOf(pm.getApplicationLabel(ai));
            app.versionName = pi.versionName == null ? "unnamed" : pi.versionName;
            app.versionCode = getVersionCode(pi);
            app.firstInstallTime = pi.firstInstallTime;
            app.lastUpdateTime = pi.lastUpdateTime;
            app.systemApp = system;
            try {
                app.icon = pm.getApplicationIcon(ai);
            } catch (RuntimeException ignored) {
                app.icon = pm.getDefaultActivityIcon();
            }
            app.installedBytes = fileLength(ai.sourceDir) + sumLengths(ai.splitSourceDirs);
            Integer count = snapshotCounts.get(app.packageName);
            app.snapshotCount = count == null ? 0 : count;
            result.add(app);
        }
        Collections.sort(result, new Comparator<InstalledApp>() {
            @Override
            public int compare(InstalledApp a, InstalledApp b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return result;
    }

    static synchronized List<SnapshotInfo> listAllSnapshots(Context context) {
        File root = ensureVault(context);
        List<SnapshotInfo> result = new ArrayList<>();
        File[] apps = root.listFiles();
        if (apps == null) {
            return result;
        }
        for (File appFolder : apps) {
            if (!appFolder.isDirectory()) {
                continue;
            }
            File[] releases = appFolder.listFiles();
            if (releases == null) {
                continue;
            }
            for (File release : releases) {
                if (!release.isDirectory() || release.getName().startsWith(".tmp_")) {
                    continue;
                }
                SnapshotInfo info = readMetadata(release);
                if (info != null) {
                    info.appFolder = appFolder;
                    info.iconFile = new File(appFolder, "icon.png");
                    result.add(info);
                }
            }
        }
        Collections.sort(result, new Comparator<SnapshotInfo>() {
            @Override
            public int compare(SnapshotInfo a, SnapshotInfo b) {
                return Long.compare(b.captureTime, a.captureTime);
            }
        });
        return result;
    }

    static List<SnapshotInfo> listSnapshotsForPackage(Context context, String packageName) {
        List<SnapshotInfo> all = listAllSnapshots(context);
        List<SnapshotInfo> result = new ArrayList<>();
        for (SnapshotInfo info : all) {
            if (packageName.equals(info.packageName)) {
                result.add(info);
            }
        }
        return result;
    }

    static VaultStats getStats(Context context) {
        List<SnapshotInfo> snapshots = listAllSnapshots(context);
        VaultStats stats = new VaultStats();
        stats.releaseCount = snapshots.size();
        Map<String, Boolean> packages = new HashMap<>();
        for (SnapshotInfo s : snapshots) {
            packages.put(s.packageName, Boolean.TRUE);
            stats.bytes += s.totalBytes;
        }
        stats.appCount = packages.size();
        return stats;
    }

    static boolean verifySnapshot(SnapshotInfo info) {
        if (info == null || info.folder == null || !info.folder.isDirectory() ||
                info.packageName == null || info.packageName.trim().isEmpty() ||
                !isSafeChildName(info.baseApkFile) || info.totalBytes <= 0L ||
                info.parts.isEmpty() || info.parts.size() > MAX_APK_PARTS) {
            return false;
        }
        try {
            int baseCount = 0;
            long total = 0L;
            Map<String, Boolean> names = new HashMap<>();
            for (SnapshotInfo.ApkPart part : info.parts) {
                if (part == null || !isSafeChildName(part.fileName) || part.bytes <= 0L ||
                        part.sha256 == null || !part.sha256.matches("(?i)[0-9a-f]{64}")) {
                    return false;
                }
                if (names.put(part.fileName, Boolean.TRUE) != null) {
                    return false;
                }
                if (part.base) {
                    baseCount++;
                    if (!info.baseApkFile.equals(part.fileName)) {
                        return false;
                    }
                }
                File apk = new File(info.folder, part.fileName);
                if (!isDirectChild(info.folder, apk) || !apk.isFile() || apk.length() != part.bytes) {
                    return false;
                }
                if (!sha256(apk).equalsIgnoreCase(part.sha256)) {
                    return false;
                }
                total += part.bytes;
            }
            return baseCount == 1 && total == info.totalBytes;
        } catch (Exception e) {
            return false;
        }
    }

    static int verifyAll(Context context) {
        int failures = 0;
        for (SnapshotInfo info : listAllSnapshots(context)) {
            if (!verifySnapshot(info)) {
                failures++;
            }
        }
        return failures;
    }

    static synchronized boolean deleteSnapshot(Context context, SnapshotInfo info) {
        if (info == null || info.folder == null) return false;
        if (isActivePendingSnapshot(context, info.folder)) return false;
        boolean deleted = deleteRecursively(info.folder);
        if (!deleted) return false;
        cleanupEmptyAppFolder(info.appFolder);
        return true;
    }

    static synchronized void clearVault(Context context) {
        clearPendingOperationState(context);
        File root = ensureVault(context);
        File[] children = root.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
    }

    static synchronized void cleanupOrphanedCaptureFolders(Context context) {
        File root = ensureVault(context);
        File[] appFolders = root.listFiles();
        if (appFolders == null) return;
        for (File appFolder : appFolders) {
            if (!appFolder.isDirectory()) continue;
            File[] children = appFolder.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child.isDirectory() && child.getName().startsWith(".tmp_")) {
                        deleteRecursively(child);
                    }
                }
            }
            cleanupEmptyAppFolder(appFolder);
        }
    }

    static boolean isPackageInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static long installedVersionCode(Context context, String packageName) {
        try {
            return getVersionCode(context.getPackageManager().getPackageInfo(packageName, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return -1L;
        }
    }

    static Drawable snapshotIcon(Context context, SnapshotInfo info) {
        if (info != null && info.iconFile != null && info.iconFile.isFile()) {
            return Drawable.createFromPath(info.iconFile.getAbsolutePath());
        }
        try {
            return context.getPackageManager().getApplicationIcon(info.packageName);
        } catch (Exception e) {
            return context.getPackageManager().getDefaultActivityIcon();
        }
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024.0 && unit < units.length - 1);
        return String.format(Locale.US, value >= 100 ? "%.0f %s" : value >= 10 ? "%.1f %s" : "%.2f %s", value, units[unit]);
    }

    static String formatDate(long millis) {
        return new SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()).format(new Date(millis));
    }

    private static SnapshotInfo findIdenticalVerifiedSnapshot(
            List<SnapshotInfo> existing, SnapshotInfo candidate) {
        if (existing == null || candidate == null || candidate.parts.isEmpty()) return null;
        for (SnapshotInfo snapshot : existing) {
            if (snapshot == null || snapshot.versionCode != candidate.versionCode ||
                    snapshot.parts.size() != candidate.parts.size() || !verifySnapshot(snapshot)) {
                continue;
            }
            if (sameApkSet(snapshot.parts, candidate.parts)) {
                return snapshot;
            }
        }
        return null;
    }

    private static boolean sameApkSet(
            List<SnapshotInfo.ApkPart> left, List<SnapshotInfo.ApkPart> right) {
        if (left == null || right == null || left.size() != right.size()) return false;
        boolean[] matched = new boolean[right.size()];
        for (SnapshotInfo.ApkPart a : left) {
            boolean found = false;
            for (int i = 0; i < right.size(); i++) {
                if (matched[i]) continue;
                SnapshotInfo.ApkPart b = right.get(i);
                if (a != null && b != null && a.base == b.base &&
                        sameNullableString(a.splitName, b.splitName) &&
                        a.bytes == b.bytes && a.sha256 != null && b.sha256 != null &&
                        a.sha256.equalsIgnoreCase(b.sha256)) {
                    matched[i] = true;
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static boolean sameNullableString(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static boolean isActivePendingSnapshot(Context context, File folder) {
        if (context == null || folder == null) return false;
        SharedPreferences settings = prefs(context);
        String pendingPath = settings.getString(AppConstants.PREF_PENDING_SNAPSHOT, null);
        if (pendingPath == null || !pendingPath.equals(folder.getAbsolutePath())) return false;
        long since = settings.getLong(AppConstants.PREF_PENDING_SINCE, 0L);
        long age = since <= 0L ? Long.MAX_VALUE : System.currentTimeMillis() - since;
        if (age < 0L || age > AppConstants.PENDING_OPERATION_TIMEOUT_MS) {
            clearPendingOperationState(context);
            return false;
        }
        return true;
    }

    private static void clearPendingOperationState(Context context) {
        prefs(context).edit()
                .remove(AppConstants.PREF_PENDING_PACKAGE)
                .remove(AppConstants.PREF_PENDING_SNAPSHOT)
                .remove(AppConstants.PREF_PENDING_STAGE)
                .remove(AppConstants.PREF_PENDING_SINCE)
                .apply();
    }

    private static void enforceRetention(Context context, String packageName) {
        int limit = prefs(context).getInt(AppConstants.PREF_RETENTION, AppConstants.DEFAULT_RETENTION);
        if (limit <= 0) {
            return;
        }
        List<SnapshotInfo> snapshots = listSnapshotsForPackage(context, packageName);
        int retainedRegular = 0;
        for (SnapshotInfo snapshot : snapshots) {
            if (snapshot.folder != null && isActivePendingSnapshot(context, snapshot.folder)) {
                continue;
            }
            if (retainedRegular < limit) {
                retainedRegular++;
                continue;
            }
            deleteSnapshot(context, snapshot);
        }
    }

    private static Map<String, Integer> countSnapshotsByPackage(Context context) {
        Map<String, Integer> result = new HashMap<>();
        for (SnapshotInfo s : listAllSnapshots(context)) {
            Integer current = result.get(s.packageName);
            result.put(s.packageName, current == null ? 1 : current + 1);
        }
        return result;
    }

    private static SnapshotInfo.ApkPart copyApkPart(File source, File destination, boolean base, String splitName)
            throws IOException, NoSuchAlgorithmException {
        if (!source.isFile()) {
            throw new IOException("Installed APK not readable: " + source.getAbsolutePath());
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (InputStream in = new BufferedInputStream(new FileInputStream(source), 1024 * 1024);
             DigestInputStream digestIn = new DigestInputStream(in, digest);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(destination), 1024 * 1024)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = digestIn.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Snapshot capture was interrupted.");
                }
                out.write(buffer, 0, read);
                total += read;
            }
        }
        SnapshotInfo.ApkPart part = new SnapshotInfo.ApkPart();
        part.fileName = destination.getName();
        part.originalPath = source.getAbsolutePath();
        part.splitName = splitName;
        part.bytes = total;
        part.sha256 = toHex(digest.digest());
        part.base = base;
        return part;
    }

    private static void writeMetadata(SnapshotInfo info, File folder) throws IOException, JSONException {
        JSONObject json = new JSONObject();
        json.put("schema", 2);
        json.put("tool", AppConstants.APP_NAME);
        json.put("toolVersion", AppConstants.APP_VERSION);
        json.put("developer", AppConstants.DEVELOPER);
        json.put("publisher", AppConstants.DEVELOPER);
        json.put("tagline", AppConstants.TAGLINE);
        json.put("copyright", "Copyright © 2026 Background Gremlin Group. All rights reserved.");
        json.put("appLabel", info.appLabel);
        json.put("packageName", info.packageName);
        json.put("versionName", info.versionName);
        json.put("versionCode", info.versionCode);
        json.put("captureTime", info.captureTime);
        json.put("lastUpdateTime", info.lastUpdateTime);
        json.put("totalBytes", info.totalBytes);
        json.put("baseApkFile", info.baseApkFile);
        JSONArray parts = new JSONArray();
        for (SnapshotInfo.ApkPart part : info.parts) {
            JSONObject p = new JSONObject();
            p.put("fileName", part.fileName);
            p.put("originalPath", part.originalPath);
            p.put("splitName", part.splitName == null ? JSONObject.NULL : part.splitName);
            p.put("bytes", part.bytes);
            p.put("sha256", part.sha256);
            p.put("base", part.base);
            parts.put(p);
        }
        json.put("apkParts", parts);
        File metadata = new File(folder, METADATA_FILE);
        try (FileOutputStream out = new FileOutputStream(metadata)) {
            out.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static SnapshotInfo readMetadata(File folder) {
        File metadata = new File(folder, METADATA_FILE);
        if (!metadata.isFile()) {
            return null;
        }
        try {
            byte[] data = readAllBytes(metadata);
            JSONObject json = new JSONObject(new String(data, StandardCharsets.UTF_8));
            SnapshotInfo info = new SnapshotInfo();
            info.appLabel = json.getString("appLabel");
            info.packageName = json.getString("packageName");
            info.versionName = json.optString("versionName", "unnamed");
            info.versionCode = json.getLong("versionCode");
            info.captureTime = json.getLong("captureTime");
            info.lastUpdateTime = json.optLong("lastUpdateTime", 0L);
            info.totalBytes = json.optLong("totalBytes", 0L);
            info.baseApkFile = json.optString("baseApkFile", "base.apk");
            info.folder = folder;
            JSONArray parts = json.getJSONArray("apkParts");
            if (parts.length() <= 0 || parts.length() > MAX_APK_PARTS) {
                return null;
            }
            long sum = 0;
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.getJSONObject(i);
                SnapshotInfo.ApkPart part = new SnapshotInfo.ApkPart();
                part.fileName = p.getString("fileName");
                part.originalPath = p.optString("originalPath", "");
                part.splitName = p.isNull("splitName") ? null : p.optString("splitName", null);
                part.bytes = p.getLong("bytes");
                part.sha256 = p.getString("sha256");
                part.base = p.optBoolean("base", false);
                info.parts.add(part);
                sum += part.bytes;
            }
            if (info.totalBytes <= 0L) {
                info.totalBytes = sum;
            }
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveIcon(PackageManager pm, ApplicationInfo ai, File appFolder) {
        File iconFile = new File(appFolder, "icon.png");
        try {
            Drawable drawable = pm.getApplicationIcon(ai);
            int size = 256;
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);
            try (FileOutputStream out = new FileOutputStream(iconFile)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            bitmap.recycle();
        } catch (Exception ignored) {
        }
    }

    private static File resolveAppFolder(File root, String label, String packageName) {
        File[] appFolders = root.listFiles();
        if (appFolders != null) {
            for (File candidate : appFolders) {
                if (!candidate.isDirectory()) continue;
                File[] releases = candidate.listFiles();
                if (releases == null) continue;
                for (File release : releases) {
                    if (!release.isDirectory() || release.getName().startsWith(".tmp_")) continue;
                    SnapshotInfo existing = readMetadata(release);
                    if (existing != null && packageName.equals(existing.packageName)) {
                        return candidate;
                    }
                }
            }
        }
        String name = safeFileName(label) + "__" + safeFileName(packageName) + "__" + shortHash(packageName);
        return new File(root, name);
    }

    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String full = toHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
            return full.substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static void cleanupEmptyAppFolder(File appFolder) {
        if (appFolder == null || !appFolder.isDirectory()) return;
        File[] children = appFolder.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory() && !child.getName().startsWith(".tmp_")) {
                return;
            }
        }
        deleteRecursively(appFolder);
    }

    private static String safeFileName(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "unnamed";
        }
        String safe = value.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replaceAll("\\s+", " ")
                .replaceAll("\\.+$", "")
                .trim();
        if (safe.isEmpty()) {
            safe = "unnamed";
        }
        if (safe.length() > 80) {
            safe = safe.substring(0, 80);
        }
        return safe;
    }

    private static File uniqueFile(File folder, String name) {
        File file = new File(folder, name);
        if (!file.exists()) {
            return file;
        }
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        String ext = dot >= 0 ? name.substring(dot) : "";
        int i = 2;
        do {
            file = new File(folder, base + "_" + i++ + ext);
        } while (file.exists());
        return file;
    }


    private static boolean isSafeChildName(String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..") &&
                name.indexOf('/') < 0 && name.indexOf('\\') < 0;
    }

    private static boolean isDirectChild(File parent, File child) throws IOException {
        File canonicalParent = parent.getCanonicalFile();
        File canonicalChild = child.getCanonicalFile();
        File actualParent = canonicalChild.getParentFile();
        return actualParent != null && canonicalParent.equals(actualParent);
    }

    private static byte[] readAllBytes(File file) throws IOException {
        if (file.length() > MAX_METADATA_BYTES) {
            throw new IOException("Metadata file is unexpectedly large.");
        }
        byte[] data = new byte[(int) file.length()];
        int offset = 0;
        try (InputStream in = new FileInputStream(file)) {
            while (offset < data.length) {
                int read = in.read(data, offset, data.length - offset);
                if (read < 0) break;
                offset += read;
            }
        }
        if (offset != data.length) {
            throw new IOException("Unable to read complete metadata file.");
        }
        return data;
    }

    private static String sha256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 1024 * 1024);
             DigestInputStream digestIn = new DigestInputStream(in, digest)) {
            byte[] buffer = new byte[1024 * 1024];
            while (digestIn.read(buffer) != -1) {
            }
        }
        return toHex(digest.digest());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static void copyDirectory(File source, File destination) throws IOException {
        if (!destination.exists() && !destination.mkdirs()) {
            throw new IOException("Unable to create destination directory.");
        }
        File[] files = source.listFiles();
        if (files == null) return;
        for (File file : files) {
            File target = new File(destination, file.getName());
            if (file.isDirectory()) {
                copyDirectory(file, target);
            } else {
                try (InputStream in = new BufferedInputStream(new FileInputStream(file));
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                    byte[] buffer = new byte[1024 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new IOException("Snapshot file copy was interrupted.");
                        }
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) return false;
                }
            }
        }
        return file.delete();
    }

    @SuppressWarnings("deprecation")
    private static long getVersionCode(PackageInfo pi) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return pi.getLongVersionCode();
        }
        return pi.versionCode;
    }

    private static long fileLength(String path) {
        return path == null ? 0L : new File(path).length();
    }

    private static long sumLengths(String[] paths) {
        if (paths == null) return 0L;
        long total = 0L;
        for (String path : paths) total += fileLength(path);
        return total;
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
