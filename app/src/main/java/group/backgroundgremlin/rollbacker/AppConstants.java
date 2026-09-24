package group.backgroundgremlin.rollbacker;

final class AppConstants {
    static final String PREFS = "rollbacker_settings";
    static final String OBSERVED_PACKAGES_PREFS = "rollbacker_observed_packages";
    static final String PREF_AUTO_CAPTURE = "auto_capture";
    static final String PREF_CAPTURE_INSTALLS = "capture_installs";
    static final String PREF_CAPTURE_UPDATES = "capture_updates";
    static final String PREF_INCLUDE_SYSTEM = "include_system";
    static final String PREF_NOTIFY_CAPTURE = "notify_capture";
    static final String PREF_RETENTION = "retention";
    static final String PREF_ONBOARDED = "onboarded";
    static final String PREF_PENDING_PACKAGE = "pending_package";
    static final String PREF_PENDING_SNAPSHOT = "pending_snapshot";
    static final String PREF_PENDING_STAGE = "pending_stage";
    static final String PREF_PENDING_SINCE = "pending_since";

    static final String PENDING_STAGE_AWAIT_UNINSTALL = "await_uninstall";
    static final String PENDING_STAGE_AWAIT_PERMISSION = "await_permission";
    static final String PENDING_STAGE_INSTALL_SUBMITTED = "install_submitted";

    static final String CHANNEL_EVENTS = "rollbacker_events";
    static final String CHANNEL_INSTALLS = "rollbacker_installs";
    static final int DEFAULT_RETENTION = 0;
    static final int NOTIFICATION_CAPTURE_BASE = 4100;
    static final int NOTIFICATION_INSTALL_BASE = 5100;
    static final int JOB_PERIODIC_SWEEP = 0x20000001;
    static final long PERIODIC_SWEEP_MS = 15L * 60L * 1000L;
    static final long PERIODIC_SWEEP_FLEX_MS = 5L * 60L * 1000L;
    static final long PENDING_OPERATION_TIMEOUT_MS = 60L * 60L * 1000L;

    static final String DEVELOPER = "Background Gremlin Group";
    static final String TAGLINE = "Creating Unique Tools for Unique Individuals.";
    static final String APP_NAME = "Roll Backer";
    static final String APP_VERSION = "1.0.1";

    private AppConstants() {}
}
