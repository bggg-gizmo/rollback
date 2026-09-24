# Roll Backer 1.0.1

Developed and published by **Background Gremlin Group**

Creating Unique Tools for Unique Individuals.

Roll Backer 1.0.1 is the audited Android 36 release of the versioned APK snapshot vault and rollback utility.

The release implements automatic capture of new installs and updates, base and split APK preservation, transactional snapshot commits, SHA-256 integrity verification, installed-app inventory, release history, configurable retention, explicit snapshot management, downgrade/reinstall handling through Android PackageInstaller, pending-operation recovery, persistent background reconciliation, predictive-back support, and edge-to-edge system-bar handling.

The source tree on `main` is directly buildable with `build.sh` and Android API 36 platform/build-tools archives.

Canonical supplied icon artwork SHA-256: `06d8d5fee7f9cdafe6ac297e41ec69101f76f23f1460284bb93db45a3f0d40c9`. The build reconstructs and verifies the exact canonical branding payload before compiling Android resources.

The audited APK checksum is:

`b7942541168f9cf47ec812c3286c4a64f5c18757e9cab55685444af969857fa1`

The audited build signing certificate SHA-256 is:

`c8891ad2f3da53915d5a4c640c04fa93bad0ea83dc9b949040655d620e65c064`

Android package updates require signing-key continuity. Builds intended to update an installed Roll Backer package must be signed with the authorized key for that installation or use an Android-supported signing-key rotation arrangement.

Copyright © 2026 Background Gremlin Group. All rights reserved.
