# Roll Backer 1.0.1

**Current public release: 1.0.1**  
[Download the audited APK](release/v1.0.1/RollBacker-1.0.1-audited-final.apk) · [Release notes](RELEASE_NOTES_1.0.1.md) · [Audit report](AUDIT_REPORT.md)

Developed and published by **Background Gremlin Group**  
**Creating Unique Tools for Unique Individuals.**  
Copyright © 2026 Background Gremlin Group. All rights reserved.

Roll Backer is an Android application-release snapshot vault. It preserves the exact installed APK package set for application releases so saved versions can be verified, reinstalled, or selected for rollback later.

Application ID: `group.backgroundgremlin.rollbacker`  
Version name: `1.0.1`  
Version code: `2`  
Minimum Android API: `26`  
Target Android API: `36`

## Snapshot vault

A modern Android application can consist of one base APK and multiple split APKs. Roll Backer saves the complete installed package set exposed by Android for each captured release. It does not modify, patch, or re-sign third-party APKs.

Every APK part is copied into Roll Backer's private application storage while a SHA-256 digest is calculated. The completed release metadata records the package identity, application label, version name, version code, capture time, Android update time, APK-part names, original installed paths, split names, byte lengths, and SHA-256 hashes.

The vault layout is:

```text
RollbackVault/
  <App label>__<package.name>__<package-hash>/
    icon.png
    v<version-name>_<version-code>_<capture-time>/
      base.apk
      split_<split-name>.apk
      metadata.json
```

Each application has an independent vault folder. Each saved release has an independent release directory. A package-name hash is included in the application-folder name to prevent safe-filename truncation or label collisions. Existing release metadata is used to keep later snapshots in the same application folder even if the visible application label changes.

## Capture behavior

Roll Backer provides three capture paths.

First, the Installed Apps screen provides `Snapshot now` for an individual application and the Home screen provides `Snapshot all installed apps now` for a user-controlled baseline.

Second, while Roll Backer's process is active, a context-registered package receiver observes Android package-added, package-replaced, and package-removed events. New installations and updates are scheduled into a dedicated `JobService` so APK copying is not performed inside the broadcast receiver execution window.

Third, Roll Backer schedules a persisted Android `JobScheduler` reconciliation job. The reconciliation records package version code, first-install time, and last-update time, compares the installed inventory with the last observed state, and captures eligible changes that were not observed while Roll Backer's process was active. Android controls the exact execution time of background jobs and can defer them because of battery, standby, thermal, or application-restriction policy.

A fresh Roll Backer installation records the current package inventory as its initial observation baseline rather than treating every already-installed application as a new install. The onboarding workflow separately offers a full manual baseline snapshot.

Automatic capture can be disabled globally. New-install and update capture can also be controlled independently. When a capture category is disabled, Roll Backer updates its observed package state without creating a snapshot so deliberately ignored changes are not captured retroactively later.

System applications are excluded by default. They can be included from Settings. Successful rollback of system or updated-system applications remains subject to device firmware, package-manager, signature, and OEM restrictions.

## Duplicate release handling

Roll Backer first checks whether a verified saved snapshot already has the same version code and Android last-update time. When that fast check does not match, Roll Backer stages and hashes the currently installed base and split APKs and compares the resulting complete APK set with verified saved snapshots having the same version code.

This prevents duplicate snapshots after reinstalling a previously saved APK set even when Android assigns a different update timestamp. A same-version package whose actual APK bytes differ remains eligible for a separate snapshot.

## Transactional snapshot creation

A new snapshot is created in a temporary release directory. All APK parts are copied and hashed there. Metadata is written only after all parts complete. The temporary directory is committed to its final release path only after the copy phase succeeds. The committed metadata is then read back and the complete saved APK set is verified before the release is accepted.

If capture fails, incomplete temporary and final directories are removed. Application startup also removes orphaned temporary capture directories left by an interrupted process.

## Integrity verification

Snapshot verification requires all of the following:

- A valid snapshot directory.
- A non-empty package name.
- Between 1 and 512 APK parts.
- Exactly one base APK.
- A positive byte length for every APK part.
- A positive aggregate snapshot byte count.
- Unique direct-child APK filenames with no path traversal.
- A 64-hex-character SHA-256 digest for every APK part.
- Exact saved-file byte lengths.
- Exact SHA-256 matches for every saved APK part.
- Aggregate APK bytes matching the metadata total.

Snapshot metadata reads are capped at 4 MiB. Roll Backer refuses to submit a snapshot to Android's package installer when integrity verification fails.

The Vault provides per-release verification. Settings provides full-vault verification.

## Retention

The default retention setting is unlimited. Settings can retain the newest 3, 5, or 10 regular releases per application.

Retention runs only after a new snapshot has been committed and verified. A saved release that is currently involved in a fresh rollback or reinstall operation is protected from retention deletion. Explicit deletion also refuses to delete an active pending release. Pending operation state expires after one hour so stale recovery state cannot indefinitely prevent retention cleanup.

## Rollback and reinstall

A saved release is selected from Vault, then from that application's Release History.

When the selected release is the same version or a newer version than the installed package, Roll Backer verifies the saved APK set and submits it through Android `PackageInstaller`.

When the selected release has a lower version code than the currently installed package, Android normally prevents a regular third-party application from directly installing the lower version over the newer version. Roll Backer therefore performs this sequence:

1. Verify the selected saved snapshot.
2. Persist the selected package, saved release path, recovery stage, and operation start time.
3. Open Android's user-confirmed uninstall flow for the currently installed newer release.
4. Wait for removal to complete.
5. Preserve every Roll Backer snapshot, including snapshots of the newer release.
6. Request Android's per-source install permission when it has not already been granted.
7. Verify the selected saved snapshot again.
8. Create a full Android `PackageInstaller` session.
9. Stream the saved base APK and every saved split APK into the same installation session.
10. Commit the session and handle Android's installation confirmation when required.
11. Clear pending recovery state after a terminal success or failure.

The installed application and Roll Backer's archived snapshot are separate objects. Uninstalling the installed newer application does not delete the newer release's Roll Backer snapshot.

Android normally deletes the target application's private runtime data during uninstall. Roll Backer preserves application package files and snapshot metadata. It does not copy another application's protected private data directory.

## Pending operation recovery

Rollback and reinstall state uses three persisted stages:

- Waiting for removal of the currently installed newer package.
- Waiting for Android's per-source installation permission.
- Installation session submitted and waiting for Android's terminal PackageInstaller result.

Returning from Android's install-permission settings resumes the selected saved release when permission has been granted. Choosing `Not now` cancels pending continuation while leaving the snapshot untouched. A submitted installation session is not submitted again by activity resume logic.

If Android returns `STATUS_PENDING_USER_ACTION`, Roll Backer opens the returned Android confirmation intent. If direct activity launch is blocked, Roll Backer posts a high-importance notification whose content action opens that exact confirmation intent.

## User interface

Roll Backer uses native Android framework UI without third-party runtime libraries. The interface uses a dark electric-blue and purple visual system based on the Roll Backer artwork.

The Home screen shows protected application count, saved release count, vault storage use, automatic-capture state, retention state, recent releases, full baseline capture, and the rollback workflow.

The Vault groups releases by application and opens complete release history.

Release History shows saved version name, version code, capture time, APK-part count, saved size, installed/rollback state, integrity verification, installation or rollback action, and explicit snapshot deletion.

Installed Apps supports application-label and package-name search, current version information, saved snapshot count, manual capture, and history access.

Settings provides automatic-capture policy, new-install policy, update policy, system-application inclusion, snapshot notifications, background-reconciliation information, retention, full-vault verification, complete vault deletion, Android install-source permission, version information, and Background Gremlin Group attribution.

The interface preserves release-history navigation origin, restores selected navigation state after activity recreation, handles Android system-bar insets, and registers Android 13 and newer predictive-back behavior for release-history navigation.

## Launcher icon

The canonical Roll Backer icon source is the exact artwork supplied for this project. Its SHA-256 is `06d8d5fee7f9cdafe6ac297e41ec69101f76f23f1460284bb93db45a3f0d40c9`.

The canonical source artwork is stored as a checksum-verified build payload at `binary-assets/branding/rollbacker-icon-source.jpg.b64`. `tools/materialize-assets.sh` reconstructs and verifies the exact source image before builds. The application launcher artwork payload is stored at `binary-assets/app/drawable/rollbacker_art.png.b64`, reconstructed as `app/src/main/res/drawable/rollbacker_art.png`, and verified against SHA-256 `efe8cae52cfd282c208781ab84779fb009f5ae30878752a50d6c6531dad9c557`.

Roll Backer has a minimum Android API level of 26, so its launcher uses the Android adaptive-icon resource directly. The supplied artwork is used full-bleed as the adaptive background with a transparent foreground. No white launcher cell or white padding is introduced by Roll Backer.

## Permissions

`android.permission.QUERY_ALL_PACKAGES` is required for Roll Backer's installed-application inventory and snapshot workflow. Google Play restricts broad package visibility and requires eligible core functionality plus the applicable Play Console declaration for Play-distributed builds.

`android.permission.REQUEST_INSTALL_PACKAGES` allows Roll Backer to request installation of saved APK sets. Android retains control of the per-source permission and installation confirmation.

`android.permission.REQUEST_DELETE_PACKAGES` supports the user-confirmed uninstall stage required for older-version rollback.

`android.permission.POST_NOTIFICATIONS` supports automatic-capture messages, rollback continuation, installation confirmation fallback, and installation results on Android versions that require notification permission.

`android.permission.RECEIVE_BOOT_COMPLETED` is required for Roll Backer's persisted `JobScheduler` reconciliation job.

## Application metadata and attribution

Public application metadata identifies:

- Developer: Background Gremlin Group.
- Publisher: Background Gremlin Group.
- Tagline: Creating Unique Tools for Unique Individuals.
- Copyright: Copyright © 2026 Background Gremlin Group. All rights reserved.

The same attribution is included in the Android manifest metadata, Android string resources, embedded raw application metadata, root project metadata, snapshot metadata written by version 1.0.1, About interface, onboarding interface, release documentation, and default development signing certificate identity.

## Build

The project builds directly from an Android API 36 platform archive and Android API 36 build-tools archive. Gradle, Maven, and network dependency resolution are not required.

The build command is:

```bash
./build.sh "$ANDROID_PLATFORM_ZIP" "$ANDROID_BUILD_TOOLS_ZIP"
```

`ANDROID_PLATFORM_ZIP` must contain Android API 36 `android.jar`. `ANDROID_BUILD_TOOLS_ZIP` must contain `aapt2`, `d8`, `zipalign`, and `apksigner`.

The script first materializes and SHA-256-verifies the canonical Roll Backer branding assets, then performs resource compilation, resource linking, `R.java` generation, Java compilation, DEX conversion, APK assembly, ZIP alignment, signing, signature verification, and SHA-256 output.

The completed APK is written to `dist/RollBacker.apk`.

For release signing, set `ROLLBACKER_KEYSTORE`, `ROLLBACKER_ALIAS`, `ROLLBACKER_STOREPASS`, and `ROLLBACKER_KEYPASS` before invoking the build. When those variables are not set, the build creates a local development keystore under `.signing/`. `.signing/`, `*.jks`, and `*.keystore` are excluded from source archives and version control.

Android requires signing-key continuity for ordinary updates to an installed package. A release intended to update an existing Roll Backer installation must be signed with the authorized key associated with that installed application, or use an Android-supported signing-key rotation arrangement.

## Security reporting

Please do not report suspected security vulnerabilities in a public issue. See [SECURITY.md](SECURITY.md).

## Source tree

```text
app/src/main/AndroidManifest.xml
app/src/main/java/group/backgroundgremlin/rollbacker/
  AppConstants.java
  BackgroundCaptureScheduler.java
  InstallStatusReceiver.java
  MainActivity.java
  Notifications.java
  PackageChangeReceiver.java
  PackageInstallerHelper.java
  RollbackerApp.java
  SnapshotInfo.java
  SnapshotJobService.java
  SnapshotManager.java
  SnapshotSweepJobService.java
app/src/main/res/
  mipmap-anydpi-v26/
  raw/
  values/
binary-assets/
  app/drawable/rollbacker_art.png.b64
  branding/rollbacker-icon-source.jpg.b64
tools/
  materialize-assets.sh
build.sh
APP_METADATA.json
AUDIT_REPORT.md
CHECKSUMS.txt
RELEASE_NOTES_1.0.1.md
README.md
```

## Storage lifetime

The vault is stored in Roll Backer's private Android application storage. Ordinary third-party applications cannot browse that directory through normal Android sandbox permissions. If Roll Backer itself is uninstalled, Android removes Roll Backer's private application storage, including its vault. Replacing an installed Roll Backer build with a differently signed build also requires uninstalling the existing Roll Backer package first unless Android signing continuity is maintained.
