package group.backgroundgremlin.rollbacker;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class SnapshotInfo {
    String appLabel;
    String packageName;
    String versionName;
    long versionCode;
    long captureTime;
    long lastUpdateTime;
    long totalBytes;
    File folder;
    File appFolder;
    File iconFile;
    String baseApkFile;
    final List<ApkPart> parts = new ArrayList<>();

    static final class ApkPart {
        String fileName;
        String originalPath;
        String splitName;
        long bytes;
        String sha256;
        boolean base;
    }
}
