# Roll Backer 1.0.1 Engineering Audit and Smoke Check

Developed and published by **Background Gremlin Group**  
**Creating Unique Tools for Unique Individuals.**  
Copyright © 2026 Background Gremlin Group. All rights reserved.

Application ID: `group.backgroundgremlin.rollbacker`  
Release: `1.0.1`  
Version code: `2`  
Minimum Android API: `26`  
Target Android API: `36`

## Audit objective

This audit evaluates the complete Roll Backer source tree, Android manifest, resources, launcher assets, snapshot-vault logic, package-change detection, background scheduling, package-installer workflow, rollback state recovery, retention behavior, concurrency, integrity validation, Android 16 compatibility, build output, signing state, and user-interface presentation.

The audit also verifies that public-facing application text contains no temporary development notes, unfinished markers, sample copy, or temporary product wording, and that public application metadata credits Background Gremlin Group.

## Functional architecture verified

Roll Backer implements a private application-release vault under its own Android application files directory. Each captured package release stores the installed base APK, every installed split APK, release metadata, part byte lengths, and SHA-256 hashes. Releases are grouped by application and kept in independent versioned release directories so uninstalling or replacing the currently installed copy of a target application does not remove its saved Roll Backer snapshots.

The implemented vault model is:

```text
RollbackVault/
  <App label>__<package.name>__<package-hash>/
    icon.png
    v<version-name>_<version-code>_<capture-time>/
      base.apk
      split_<split-name>.apk
      metadata.json
```

The package-name hash prevents path collisions, while metadata-based package resolution keeps later releases in the same application folder if the visible app label changes.

## Findings corrected during the audit

### 1. Modern Android package-broadcast reliability

Manifest registration cannot be relied on for most implicit package-change broadcasts on modern Android. Roll Backer now combines a live context-registered receiver with a persistent `JobScheduler` reconciliation job. Live install and replacement events schedule focused package captures while the process is active. The persisted periodic reconciliation recovers changes that occur while the process is absent.

The manifest receiver is restricted to the exempt full-package-removal event needed for rollback continuation, and it is non-exported. Focused and periodic job services are also non-exported and require `android.permission.BIND_JOB_SERVICE`.

### 2. First-run over-capture prevention

A fresh Roll Backer installation must not interpret every application already present on the device as a newly installed application. Roll Backer now establishes a persisted observation baseline on first launch without automatically archiving the existing inventory. The onboarding flow separately offers a user-controlled baseline snapshot of visible installed applications.

Observation state records package version code, `firstInstallTime`, and `lastUpdateTime`. Reconciliation distinguishes new installs, reinstalls, and updates from unchanged packages.

### 3. Disabled automatic-capture policy behavior

When automatic capture is disabled, the periodic job refreshes the observed package state without writing snapshots. Re-enabling automatic capture therefore does not retroactively archive changes that occurred while automatic protection was deliberately disabled.

When only new-install or update capture is disabled, the relevant current package state is marked observed so a later sweep does not misclassify a previously ignored change.

### 4. Reinstall duplicate-snapshot prevention

Android can assign a new `lastUpdateTime` after reinstalling the exact APK bytes from an existing snapshot. A `versionCode` plus `lastUpdateTime` comparison alone can therefore create a duplicate release after rollback or reinstall.

Roll Backer now stages and hashes the installed base and split APK set, then compares base/split identity, split name, byte length, and SHA-256 digest against verified saved snapshots with the same version code. If the binary set is identical, it is treated as already protected. A same-version package with different APK bytes remains eligible for a distinct snapshot.

### 5. Existing-snapshot corruption handling during capture

The fast path that recognizes an exact saved current release now verifies that saved snapshot before skipping capture. A metadata match is no longer sufficient by itself to suppress a new capture when the saved files have failed integrity validation.

### 6. Completed-write integrity

Snapshot creation writes to a temporary release directory. Each APK part is copied while its SHA-256 digest is calculated. Metadata is written after all APK parts are complete. The release is then committed to its final directory, re-read from disk, and fully verified before it is accepted.

A failed capture removes incomplete temporary and final release directories. Process startup removes orphaned temporary capture directories left by interrupted prior processes before new capture jobs are scheduled.

### 7. Vault read/write/delete concurrency

Snapshot capture, snapshot listing, explicit snapshot deletion, retention deletion, and full-vault deletion are synchronized through the vault manager where mutation consistency is required. This prevents a reader or deletion operation from treating an in-progress release as a completed release and prevents destructive operations from racing an active capture.

Background file-copy loops check thread interruption so Android job cancellation and activity teardown can stop long copy operations without silently continuing full copies.

### 8. Stable per-application vault folders

Application folders include a twelve-hex-character SHA-256 package-name fragment in addition to a safe label and safe package name. Existing folders are resolved by the full package name stored in release metadata. This avoids truncated-name collisions and avoids splitting an application history merely because the visible app label changed.

### 9. Pending rollback state model

Rollback and reinstall recovery now distinguishes three persisted stages:

- Waiting for uninstall of the newer installed package.
- Waiting for Android's per-source installation permission.
- PackageInstaller session submitted and awaiting a terminal result.

This prevents same-version and upgrade reinstalls from becoming stuck after returning from the installation-permission settings page. It also prevents an already-submitted session from being submitted repeatedly.

### 10. Pending-operation expiration

Pending operation state stores a start time and expires after one hour. Expired rollback state is cleared without deleting the saved snapshot. Choosing `Not now` at the install-permission prompt also clears the pending operation, preventing an unexpected install from resuming later.

### 11. Pending-snapshot deletion protection

Retention enforcement does not delete a snapshot used by a fresh active rollback or reinstall operation. The vault manager independently protects that release from explicit deletion. Stale pending state beyond the one-hour recovery window is cleared before it can indefinitely prevent retention cleanup. Clearing the entire vault cancels pending rollback state before vault deletion.

### 12. Rollback downgrade sequence

If the selected saved release has a lower version code than the installed package, Roll Backer verifies the saved snapshot before opening Android's uninstall confirmation. The selected snapshot path and package are persisted before destructive action. After removal, Roll Backer resumes installation of the saved APK set.

The removed installed package and the archived snapshot are separate objects. Uninstalling the currently installed newer version does not delete that newer version's previously captured Roll Backer snapshot.

Android normally removes the target application's private runtime data during uninstall. Roll Backer preserves APK packages and their snapshot metadata, not another application's protected private data directory.

### 13. PackageInstaller session completeness

The reinstall path creates a `MODE_FULL_INSTALL` session, declares the target package and aggregate APK byte size, streams the saved base APK and all saved split APKs into the same session, flushes session writes, and commits through an explicit status `PendingIntent`.

The snapshot is verified again immediately before installation. A failed verification prevents session submission.

### 14. PackageInstaller user-action recovery

`STATUS_PENDING_USER_ACTION` is handled explicitly. Roll Backer opens Android's returned confirmation intent directly when allowed. If Android blocks the direct background activity start, Roll Backer posts a high-importance notification whose content intent opens the exact Android confirmation activity supplied by PackageInstaller.

Terminal success and failure results clear persisted pending rollback state.

### 15. Focused capture job concurrency

Focused background captures no longer share one mutable worker-thread reference. `SnapshotJobService` tracks workers by JobScheduler job ID so stopping one job does not interrupt a different package capture.

### 16. Periodic reconciliation job concurrency

`SnapshotSweepJobService` independently tracks its workers by job ID and handles stop requests without confusing one running job with another invocation.

### 17. Activity lifecycle safety

Background UI work is serialized on a dedicated executor. Main-thread callbacks pass through a lifecycle guard before updating views or dialogs. Activity destruction removes delayed handler work, unregisters the predictive-back callback, and interrupts outstanding executor work. This prevents stale background results from trying to update a destroyed activity after rotation, recreation, or finish.

### 18. Android 16 predictive back migration

Roll Backer does not use the Android 16 predictive-back opt-out. Release history registers an `OnBackInvokedCallback` on Android 13 and newer and returns to the screen that opened the release history. Top-level screens remain available to Android's normal back-to-home behavior. Android 8 through Android 12 retain the legacy activity callback for the detail-navigation case.

### 19. Android 15 and Android 16 edge-to-edge behavior

On API 30 through API 34, Roll Backer explicitly enables edge-to-edge drawing and applies system-bar plus display-cutout insets to the root layout. On API 35 and newer, it uses platform-enforced edge-to-edge behavior and applies the same inset protection. API 26 through API 29 use the normal decor-fitted system-bar layout.

### 20. Notification onboarding order

The first-run onboarding is shown before Android's notification-permission request. Selecting the primary onboarding action marks onboarding complete and then requests notification permission when required by the platform, avoiding overlapping first-run system and application dialogs.

### 21. Receiver and service exposure

Manifest job services, PackageInstaller callback receiver, and full-removal receiver are non-exported. The PackageInstaller callback uses an explicit intent addressed directly to Roll Backer's receiver. Cleartext network traffic is disabled, and Roll Backer's application backup is disabled.

### 22. Metadata hardening

Snapshot metadata input is capped at 4 MiB. A snapshot may contain no more than 512 APK parts. Verification requires one and only one base APK, positive byte lengths, a positive aggregate byte count, safe direct-child filenames, unique part filenames, valid 64-hex-character SHA-256 digests, exact file sizes, matching file hashes, and matching aggregate bytes.

### 23. Launcher icon border audit

The canonical source artwork is `branding/roll-backer-icon.jpg`, SHA-256 `06d8d5fee7f9cdafe6ac297e41ec69101f76f23f1460284bb93db45a3f0d40c9`. It is the exact supplied Roll Backer icon artwork. Android launcher resources are derived from that file by resizing only; no crop, padding, recoloring, redrawing, or substitution is applied.

Legacy launcher icons were checked at mdpi, hdpi, xhdpi, xxhdpi, and xxxhdpi. Every density has zero nearly-white pixels on the complete outer border. Every corner pixel is opaque black. White pixels present inside higher-resolution artwork belong to internal lettering and highlights rather than an outer cell or padding.

The Android 8 and newer adaptive launcher icon uses the Roll Backer artwork as the adaptive background with a transparent foreground. No white outer cell is introduced by the adaptive-icon resource.

### 24. Public-facing text and attribution

The manifest, Android resources, raw application metadata, root application metadata, About interface, onboarding interface, README, build signing identity, and audit documentation credit Background Gremlin Group.

A source/public-text scan found no `TODO`, `FIXME`, unfinished sample copy, lorem text, temporary development-session commentary, Unicode ellipsis characters, or three-dot omission sequences in the public text/source set scanned for release.

## User-interface smoke render

Six deterministic headless visual renders were produced from the current Roll Backer interface specification, palette, icon, labels, controls, navigation, release cards, and public-facing copy:

- Onboarding.
- Home.
- Vault.
- Installed apps.
- Release history.
- Settings.

The renders verify dark blue and purple presentation, card spacing, primary and secondary action hierarchy, bottom navigation, app-history grouping, settings organization, Background Gremlin Group branding, and the absence of a white outer icon cell.

These are headless Chromium visual-QA renders of the implemented Android interface design. The audit environment does not contain an Android emulator binary, ADB runtime, or Android system image, so the files are not represented as emulator or physical-device screenshots.

## Build and package verification

The release was rebuilt from the complete source tree using the Android API 36 platform archive and Android API 36 build-tools archive.

The following checks passed after the final source changes:

- Android resources compiled with `aapt2`.
- Manifest and resources linked for minimum API 26 and target API 36.
- All Java sources compiled successfully.
- Additional `javac` deprecation and unchecked lint pass completed with zero diagnostic lines.
- D8 produced `classes.dex` containing the Roll Backer application classes.
- `aapt2 dump badging` reports package `group.backgroundgremlin.rollbacker`, version code `2`, version name `1.0.1`, compile SDK `36`, minimum SDK `26`, and target SDK `36`.
- `aapt2 dump permissions` reports the expected package visibility, package installation, package deletion, notification, and reboot permissions.
- APK raw resource metadata is present.
- `zipalign -c -v 4` reports successful verification.
- `apksigner verify --verbose --print-certs` reports a single signer and successful APK Signature Scheme v2 and v3 verification.
- The signer certificate distinguished name is `CN=Background Gremlin Group, OU=Roll Backer, O=Background Gremlin Group`.
- Final launcher-icon border pixel checks pass at every legacy icon density.
- Public-source and public-document scans pass the unfinished/meta-text rules listed above.

Final audited APK SHA-256 at the time of this report:

`b7942541168f9cf47ec812c3286c4a64f5c18757e9cab55685444af969857fa1`

Audited local release certificate SHA-256:

`c8891ad2f3da53915d5a4c640c04fa93bad0ea83dc9b949040655d620e65c064`

## Signing information

The audited 1.0.1 APK is signed with certificate SHA-256:

`c8891ad2f3da53915d5a4c640c04fa93bad0ea83dc9b949040655d620e65c064`

Android package updates require signing-key continuity unless a supported signing-key rotation arrangement has been established. Builds intended to update an installed Roll Backer package must therefore be signed with the authorized key for that installation or use an Android-supported signing-key rotation arrangement.

The local audit keystore and its credentials are excluded from distributable source and bundle artifacts.

## Remaining platform limitations

### Android background scheduling

`JobScheduler` is system-controlled. Battery policy, standby policy, restricted-app state, thermal policy, and other platform decisions can defer periodic work. Roll Backer's live process receiver reduces latency while the process is active, and periodic reconciliation recovers missed final states, but an unprivileged Android application cannot guarantee that it will copy every transient application version before another replacement occurs.

### Target-application private data

Roll Backer cannot copy another ordinary application's sandboxed private data without privileges that a normal Android application does not possess. APK rollback restores archived application packages. It does not reconstruct private databases, credentials, preferences, account state, or save data that Android deleted during an uninstall.

### System-application rollback

System and updated-system application rollback behavior is controlled by device firmware, package-manager policy, signatures, partition layout, and OEM restrictions. Roll Backer exposes system packages only when the user enables the setting, but successful rollback cannot be guaranteed for protected system packages.

### Google Play distribution policy

Roll Backer's installed-app inventory is core to its snapshot workflow and uses `android.permission.QUERY_ALL_PACKAGES`. Google Play restricts broad package visibility and requires eligible core functionality plus the relevant Play Console permission declaration for Play-distributed applications.

### Device-runtime test coverage

The available audit environment contains the Android API 36 platform and build tools but no Android emulator executable, Android system image, ADB-connected device, or physical Android device. Compile-time, package-level, signature, DEX, source, metadata, icon, and deterministic headless UI checks were completed. Device-specific runtime behavior such as OEM uninstall UI, package-installer confirmation presentation, power-management deferral, and manufacturer-specific system-app policy requires validation on representative Android devices before treating a release as device-qualified production software.

## Android documentation references

Android implicit broadcast exceptions:  
`https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions`

Android 16 changes for target SDK 36, including edge-to-edge and predictive back:  
`https://developer.android.com/about/versions/16/behavior-changes-16`

Predictive back migration guidance:  
`https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture`

Broadcast receiver guidance:  
`https://developer.android.com/develop/background-work/background-tasks/broadcasts`

PackageInstaller reference:  
`https://developer.android.com/reference/android/content/pm/PackageInstaller`

PackageInstaller SessionParams reference:  
`https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams`

Google Play broad package visibility policy:  
`https://support.google.com/googleplay/android-developer/answer/10158779`

## Release state

The audited source contains a complete buildable Android application with implemented snapshot capture, versioned vault storage, split-APK preservation, metadata and SHA-256 integrity records, installed-application inventory, automatic package-change capture, persistent reconciliation, manual baseline capture, per-app history, configurable retention, vault verification, explicit snapshot deletion, full-vault deletion, downgrade handling, reinstall handling, pending-operation recovery, Android PackageInstaller integration, Android 16 predictive-back migration, edge-to-edge inset handling, and Background Gremlin Group public attribution.

The package passes the static, build, packaging, signing, DEX, metadata, launcher-asset, and headless visual smoke checks available in the audit environment. Device-runtime qualification remains an explicit separate test stage because no Android runtime target is present in the audit environment.