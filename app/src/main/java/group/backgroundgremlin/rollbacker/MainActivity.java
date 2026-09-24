package group.backgroundgremlin.rollbacker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQ_UNINSTALL = 7101;
    private static final int REQ_NOTIFICATIONS = 7102;
    private static final int REQ_UNKNOWN_SOURCES = 7103;

    private static final int C_BG = Color.rgb(7, 9, 20);
    private static final int C_CARD = Color.rgb(20, 24, 42);
    private static final int C_CARD_ALT = Color.rgb(15, 18, 33);
    private static final int C_BLUE = Color.rgb(45, 140, 255);
    private static final int C_PURPLE = Color.rgb(158, 56, 255);
    private static final int C_TEXT = Color.rgb(247, 249, 255);
    private static final int C_MUTED = Color.rgb(174, 185, 209);
    private static final int C_BORDER = Color.rgb(47, 55, 82);
    private static final int C_GREEN = Color.rgb(86, 214, 140);
    private static final int C_RED = Color.rgb(255, 104, 120);

    private FrameLayout contentHost;
    private LinearLayout navBar;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int currentTab = 0;
    private String detailPackage = null;
    private int detailReturnTab = 1;
    private boolean pendingInstallInFlight = false;
    private Object detailBackCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureSystemBars();
        buildShell();

        boolean onboarded = SnapshotManager.prefs(this).getBoolean(AppConstants.PREF_ONBOARDED, false);
        if (savedInstanceState != null) {
            currentTab = savedInstanceState.getInt("currentTab", 0);
            detailPackage = savedInstanceState.getString("detailPackage");
            detailReturnTab = savedInstanceState.getInt("detailReturnTab", 1);
        }
        if (detailPackage != null) {
            showAppDetail(detailPackage, detailReturnTab);
        } else if (currentTab == 1) {
            showVault();
        } else if (currentTab == 2) {
            showApps();
        } else if (currentTab == 3) {
            showSettings();
        } else {
            showHome();
        }

        if (!onboarded) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    showOnboarding();
                }
            }, 300L);
        } else {
            requestNotificationPermissionIfNeeded();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                maybeContinuePendingOperation();
            }
        }, 500L);
    }

    @Override
    protected void onDestroy() {
        unregisterDetailBackCallback();
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("currentTab", currentTab);
        outState.putString("detailPackage", detailPackage);
        outState.putInt("detailReturnTab", detailReturnTab);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (detailPackage != null) {
            returnFromDetail();
            return;
        }
        super.onBackPressed();
    }

    private void syncDetailBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (detailPackage != null && detailBackCallback == null) {
            detailBackCallback = Api33Back.register(this);
        } else if (detailPackage == null) {
            unregisterDetailBackCallback();
        }
    }

    private void unregisterDetailBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || detailBackCallback == null) return;
        Api33Back.unregister(this, detailBackCallback);
        detailBackCallback = null;
    }

    @SuppressWarnings("deprecation")
    private void configureSystemBars() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            Window window = getWindow();
            window.setStatusBarColor(C_BG);
            window.setNavigationBarColor(Color.rgb(5, 6, 13));
        }
    }

    @SuppressWarnings("deprecation")
    private void enableLegacyEdgeToEdge() {
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
    }

    private void buildShell() {
        final LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackground(backgroundGradient());
        shell.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                enableLegacyEdgeToEdge();
            }
            shell.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    shell.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                    return insets;
                }
            });
        }

        contentHost = new FrameLayout(this);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        shell.addView(contentHost, contentParams);

        navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setGravity(Gravity.CENTER);
        navBar.setPadding(dp(8), dp(7), dp(8), dp(8));
        navBar.setBackground(solid(Color.rgb(8, 10, 20), 0, C_BORDER, 1));
        shell.addView(navBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)));
        setContentView(shell);
        updateNav();
    }

    private void updateNav() {
        syncDetailBackCallback();
        navBar.removeAllViews();
        addNavItem("Home", 0);
        addNavItem("Vault", 1);
        addNavItem("Apps", 2);
        addNavItem("Settings", 3);
    }

    private void addNavItem(String label, final int tab) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setClickable(true);
        item.setFocusable(true);
        item.setPadding(dp(5), dp(6), dp(5), dp(4));
        if (currentTab == tab) {
            item.setBackground(solid(Color.argb(55, 80, 100, 255), dp(15), C_BLUE, 1));
        }
        TextView dot = text(currentTab == tab ? "●" : "•", currentTab == tab ? 13 : 10, currentTab == tab ? C_BLUE : C_MUTED, Typeface.BOLD);
        dot.setGravity(Gravity.CENTER);
        item.addView(dot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));
        TextView name = text(label, 12, currentTab == tab ? C_TEXT : C_MUTED, currentTab == tab ? Typeface.BOLD : Typeface.NORMAL);
        name.setGravity(Gravity.CENTER);
        item.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                detailPackage = null;
                currentTab = tab;
                updateNav();
                if (tab == 0) showHome();
                else if (tab == 1) showVault();
                else if (tab == 2) showApps();
                else showSettings();
            }
        });
        navBar.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    private void showHome() {
        detailPackage = null;
        currentTab = 0;
        updateNav();
        final LinearLayout body = pageBody();
        addBrandHeader(body, "Roll Backer", "App release snapshots and rollback vault");
        final LinearLayout dynamic = new LinearLayout(this);
        dynamic.setOrientation(LinearLayout.VERTICAL);
        body.addView(dynamic, fullWidth());
        showLoading(dynamic, "Reading protected releases.");
        setPage(body);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                final SnapshotManager.VaultStats stats = SnapshotManager.getStats(MainActivity.this);
                final List<SnapshotInfo> snapshots = SnapshotManager.listAllSnapshots(MainActivity.this);
                postToUi(new Runnable() {
                    @Override
                    public void run() {
                        if (currentTab != 0) return;
                        dynamic.removeAllViews();
                        dynamic.addView(heroStats(stats));
                        addGap(dynamic, 14);
                        TextView protect = primaryButton("Snapshot all installed apps now");
                        protect.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                confirmSnapshotAll();
                            }
                        });
                        dynamic.addView(protect, fullWidth());
                        addGap(dynamic, 22);
                        addSectionTitle(dynamic, "Recent releases", snapshots.isEmpty() ? "Your first captured app release will appear here." : "Newest protected APK sets");
                        if (snapshots.isEmpty()) {
                            dynamic.addView(infoCard("Vault is ready", "Install or update an app and Roll Backer will automatically save its installed APK set. You can also create a baseline from the Apps tab.", C_BLUE));
                        } else {
                            int count = Math.min(5, snapshots.size());
                            for (int i = 0; i < count; i++) {
                                final SnapshotInfo s = snapshots.get(i);
                                View card = releaseCard(s, false);
                                card.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        showAppDetail(s.packageName);
                                    }
                                });
                                dynamic.addView(card);
                                addGap(dynamic, 10);
                            }
                        }
                        addGap(dynamic, 16);
                        addSectionTitle(dynamic, "Rollback workflow", "Designed around Android's install security model");
                        dynamic.addView(stepCard("1", "Capture", "Roll Backer copies the installed base APK and every split APK into its private vault, then records SHA-256 integrity hashes."));
                        addGap(dynamic, 10);
                        dynamic.addView(stepCard("2", "Choose a release", "Open an app in the Vault and select the saved version you want. Every release shows its version code, capture time, size and APK part count."));
                        addGap(dynamic, 10);
                        dynamic.addView(stepCard("3", "Reinstall or roll back", "Same-version reinstalls can be submitted directly. Older versions require Android to uninstall the newer app first; that uninstall removes the target app's private data."));
                        addGap(dynamic, 30);
                    }
                });
            }
        });
    }

    private View heroStats(SnapshotManager.VaultStats stats) {
        LinearLayout card = card();
        TextView eyebrow = text("PROTECTED LIBRARY", 11, C_BLUE, Typeface.BOLD);
        eyebrow.setLetterSpacing(0.12f);
        card.addView(eyebrow);
        addGap(card, 7);
        TextView title = text(stats.releaseCount + " saved release" + (stats.releaseCount == 1 ? "" : "s"), 28, C_TEXT, Typeface.BOLD);
        card.addView(title);
        addGap(card, 4);
        card.addView(text(stats.appCount + " apps · " + SnapshotManager.humanBytes(stats.bytes) + " in private storage", 14, C_MUTED, Typeface.NORMAL));
        addGap(card, 16);
        LinearLayout meter = new LinearLayout(this);
        meter.setOrientation(LinearLayout.HORIZONTAL);
        meter.setBackground(solid(Color.rgb(11, 14, 28), dp(10), C_BORDER, 1));
        meter.setPadding(dp(12), dp(10), dp(12), dp(10));
        TextView status = text("Auto-capture " + (SnapshotManager.prefs(this).getBoolean(AppConstants.PREF_AUTO_CAPTURE, true) ? "ON" : "OFF"), 12,
                SnapshotManager.prefs(this).getBoolean(AppConstants.PREF_AUTO_CAPTURE, true) ? C_GREEN : C_RED, Typeface.BOLD);
        meter.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView retention = text(retentionLabel(), 12, C_MUTED, Typeface.NORMAL);
        retention.setGravity(Gravity.RIGHT);
        meter.addView(retention, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(meter, fullWidth());
        return card;
    }

    private void showVault() {
        detailPackage = null;
        currentTab = 1;
        updateNav();
        final LinearLayout body = pageBody();
        addBrandHeader(body, "Vault", "Saved app releases grouped by application");
        final LinearLayout dynamic = new LinearLayout(this);
        dynamic.setOrientation(LinearLayout.VERTICAL);
        body.addView(dynamic, fullWidth());
        showLoading(dynamic, "Reading the vault.");
        setPage(body);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                final List<SnapshotInfo> all = SnapshotManager.listAllSnapshots(MainActivity.this);
                final Map<String, List<SnapshotInfo>> groups = new LinkedHashMap<>();
                for (SnapshotInfo s : all) {
                    List<SnapshotInfo> list = groups.get(s.packageName);
                    if (list == null) {
                        list = new ArrayList<>();
                        groups.put(s.packageName, list);
                    }
                    list.add(s);
                }
                postToUi(new Runnable() {
                    @Override
                    public void run() {
                        if (currentTab != 1) return;
                        dynamic.removeAllViews();
                        if (groups.isEmpty()) {
                            dynamic.addView(infoCard("No releases saved yet", "Use the Apps tab to snapshot installed apps now, or leave auto-capture enabled and Roll Backer will protect future installs and updates.", C_PURPLE));
                            return;
                        }
                        for (Map.Entry<String, List<SnapshotInfo>> entry : groups.entrySet()) {
                            final String pkg = entry.getKey();
                            List<SnapshotInfo> list = entry.getValue();
                            final SnapshotInfo newest = list.get(0);
                            LinearLayout card = card();
                            LinearLayout row = new LinearLayout(MainActivity.this);
                            row.setOrientation(LinearLayout.HORIZONTAL);
                            row.setGravity(Gravity.CENTER_VERTICAL);
                            ImageView icon = appIcon(SnapshotManager.snapshotIcon(MainActivity.this, newest), 58);
                            row.addView(icon);
                            LinearLayout info = new LinearLayout(MainActivity.this);
                            info.setOrientation(LinearLayout.VERTICAL);
                            info.setPadding(dp(12), 0, 0, 0);
                            info.addView(text(newest.appLabel, 18, C_TEXT, Typeface.BOLD));
                            info.addView(text(pkg, 11, C_MUTED, Typeface.NORMAL));
                            row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                            TextView count = chip(list.size() + " release" + (list.size() == 1 ? "" : "s"), C_PURPLE);
                            row.addView(count);
                            card.addView(row);
                            addGap(card, 14);
                            card.addView(text("Newest: " + newest.versionName + " (" + newest.versionCode + ") · " + SnapshotManager.humanBytes(totalBytes(list)), 13, C_MUTED, Typeface.NORMAL));
                            addGap(card, 12);
                            TextView open = secondaryButton("View release history");
                            open.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    showAppDetail(pkg);
                                }
                            });
                            card.addView(open, fullWidth());
                            dynamic.addView(card);
                            addGap(dynamic, 11);
                        }
                        addGap(dynamic, 24);
                    }
                });
            }
        });
    }

    private void showAppDetail(final String packageName) {
        int origin = currentTab;
        if (origin < 0 || origin > 3) origin = 1;
        showAppDetail(packageName, origin);
    }

    private void showAppDetail(final String packageName, final int returnTab) {
        detailPackage = packageName;
        detailReturnTab = returnTab < 0 || returnTab > 3 ? 1 : returnTab;
        currentTab = 1;
        updateNav();
        final LinearLayout body = pageBody();
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = secondaryButton("‹ " + tabLabel(detailReturnTab));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                returnFromDetail();
            }
        });
        top.addView(back, new LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(top);
        addGap(body, 16);
        final LinearLayout dynamic = new LinearLayout(this);
        dynamic.setOrientation(LinearLayout.VERTICAL);
        body.addView(dynamic, fullWidth());
        showLoading(dynamic, "Loading release history.");
        setPage(body);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                final List<SnapshotInfo> snapshots = SnapshotManager.listSnapshotsForPackage(MainActivity.this, packageName);
                final long installedCode = SnapshotManager.installedVersionCode(MainActivity.this, packageName);
                postToUi(new Runnable() {
                    @Override
                    public void run() {
                        dynamic.removeAllViews();
                        if (snapshots.isEmpty()) {
                            dynamic.addView(infoCard("Release history is empty", "This app no longer has snapshots in the vault.", C_RED));
                            return;
                        }
                        SnapshotInfo first = snapshots.get(0);
                        LinearLayout header = card();
                        LinearLayout row = new LinearLayout(MainActivity.this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        row.addView(appIcon(SnapshotManager.snapshotIcon(MainActivity.this, first), 72));
                        LinearLayout labels = new LinearLayout(MainActivity.this);
                        labels.setOrientation(LinearLayout.VERTICAL);
                        labels.setPadding(dp(14), 0, 0, 0);
                        labels.addView(text(first.appLabel, 24, C_TEXT, Typeface.BOLD));
                        labels.addView(text(first.packageName, 12, C_MUTED, Typeface.NORMAL));
                        labels.addView(text(snapshots.size() + " protected release" + (snapshots.size() == 1 ? "" : "s"), 13, C_BLUE, Typeface.BOLD));
                        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                        header.addView(row);
                        dynamic.addView(header);
                        addGap(dynamic, 18);
                        addSectionTitle(dynamic, "Release history", installedCode >= 0 ? "Installed version code: " + installedCode : "App is currently not installed");
                        for (final SnapshotInfo s : snapshots) {
                            LinearLayout card = card();
                            LinearLayout titleRow = new LinearLayout(MainActivity.this);
                            titleRow.setOrientation(LinearLayout.HORIZONTAL);
                            titleRow.setGravity(Gravity.CENTER_VERTICAL);
                            LinearLayout titles = new LinearLayout(MainActivity.this);
                            titles.setOrientation(LinearLayout.VERTICAL);
                            titles.addView(text("Version " + s.versionName, 18, C_TEXT, Typeface.BOLD));
                            titles.addView(text("Version code " + s.versionCode, 12, C_MUTED, Typeface.NORMAL));
                            titleRow.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                            if (installedCode == s.versionCode) titleRow.addView(chip("INSTALLED", C_GREEN));
                            else if (installedCode > s.versionCode) titleRow.addView(chip("ROLLBACK", C_PURPLE));
                            else titleRow.addView(chip("SAVED", C_BLUE));
                            card.addView(titleRow);
                            addGap(card, 12);
                            card.addView(text(SnapshotManager.formatDate(s.captureTime) + " · " + SnapshotManager.humanBytes(s.totalBytes) + " · " + s.parts.size() + " APK part" + (s.parts.size() == 1 ? "" : "s"), 12, C_MUTED, Typeface.NORMAL));
                            addGap(card, 12);
                            LinearLayout actions = new LinearLayout(MainActivity.this);
                            actions.setOrientation(LinearLayout.HORIZONTAL);
                            TextView install = primaryButton(installedCode > s.versionCode ? "Roll back to this release" : "Install this release");
                            install.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    requestInstall(s);
                                }
                            });
                            actions.addView(install, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                            addHorizontalGap(actions, 8);
                            TextView verify = secondaryButton("Verify");
                            verify.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    verifyOne(s);
                                }
                            });
                            actions.addView(verify, new LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT));
                            card.addView(actions);
                            addGap(card, 8);
                            TextView delete = dangerLink("Delete snapshot");
                            delete.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    confirmDeleteSnapshot(s);
                                }
                            });
                            card.addView(delete, fullWidth());
                            dynamic.addView(card);
                            addGap(dynamic, 10);
                        }
                        addGap(dynamic, 24);
                    }
                });
            }
        });
    }

    private void returnFromDetail() {
        int target = detailReturnTab;
        detailPackage = null;
        if (target == 0) showHome();
        else if (target == 2) showApps();
        else if (target == 3) showSettings();
        else showVault();
    }

    private String tabLabel(int tab) {
        if (tab == 0) return "Home";
        if (tab == 2) return "Apps";
        if (tab == 3) return "Settings";
        return "Vault";
    }

    private void showApps() {
        detailPackage = null;
        currentTab = 2;
        updateNav();
        final LinearLayout body = pageBody();
        addBrandHeader(body, "Installed apps", "Create a baseline snapshot at any time");
        final EditText search = new EditText(this);
        search.setHint("Search apps or package names");
        search.setHintTextColor(Color.rgb(112, 124, 151));
        search.setTextColor(C_TEXT);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setPadding(dp(14), dp(11), dp(14), dp(11));
        search.setBackground(solid(C_CARD_ALT, dp(14), C_BORDER, 1));
        body.addView(search, fullWidth());
        addGap(body, 14);
        final LinearLayout dynamic = new LinearLayout(this);
        dynamic.setOrientation(LinearLayout.VERTICAL);
        body.addView(dynamic, fullWidth());
        showLoading(dynamic, "Reading installed apps.");
        setPage(body);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                final List<SnapshotManager.InstalledApp> apps = SnapshotManager.listInstalledApps(MainActivity.this);
                postToUi(new Runnable() {
                    @Override
                    public void run() {
                        if (currentTab != 2) return;
                        renderInstalledApps(dynamic, apps, "");
                        search.addTextChangedListener(new TextWatcher() {
                            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                                renderInstalledApps(dynamic, apps, s.toString());
                            }
                            @Override public void afterTextChanged(Editable s) {}
                        });
                    }
                });
            }
        });
    }

    private void renderInstalledApps(LinearLayout host, List<SnapshotManager.InstalledApp> apps, String query) {
        host.removeAllViews();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.getDefault());
        int shown = 0;
        for (final SnapshotManager.InstalledApp app : apps) {
            if (!q.isEmpty() && !app.label.toLowerCase(Locale.getDefault()).contains(q) && !app.packageName.toLowerCase(Locale.getDefault()).contains(q)) {
                continue;
            }
            shown++;
            LinearLayout card = card();
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(appIcon(app.icon, 52));
            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setPadding(dp(12), 0, dp(8), 0);
            textCol.addView(text(app.label, 16, C_TEXT, Typeface.BOLD));
            textCol.addView(text(app.versionName + " (" + app.versionCode + ")", 12, C_MUTED, Typeface.NORMAL));
            textCol.addView(text(app.packageName, 10, Color.rgb(126, 138, 165), Typeface.NORMAL));
            row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(chip(app.snapshotCount + " saved", app.snapshotCount > 0 ? C_GREEN : C_BLUE));
            card.addView(row);
            addGap(card, 12);
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            TextView snap = primaryButton("Snapshot now");
            snap.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    snapshotOne(app);
                }
            });
            actions.addView(snap, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (app.snapshotCount > 0) {
                addHorizontalGap(actions, 8);
                TextView history = secondaryButton("History");
                history.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showAppDetail(app.packageName);
                    }
                });
                actions.addView(history, new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            card.addView(actions);
            host.addView(card);
            addGap(host, 9);
        }
        if (shown == 0) host.addView(infoCard("No matching apps", "Try a different app name or package name.", C_BLUE));
        addGap(host, 24);
    }

    private void showSettings() {
        detailPackage = null;
        currentTab = 3;
        updateNav();
        LinearLayout body = pageBody();
        addBrandHeader(body, "Settings", "Capture policy, retention and vault maintenance");
        final SharedPreferences prefs = SnapshotManager.prefs(this);

        addSectionTitle(body, "Automatic protection", "Choose which package changes are captured");
        body.addView(switchCard("Auto-capture package changes", "Live package events while Roll Backer is active plus persistent Android-scheduled reconciliation.", AppConstants.PREF_AUTO_CAPTURE, true));
        addGap(body, 9);
        body.addView(switchCard("Capture new installs", "Save the first installed release when Android reports a new package.", AppConstants.PREF_CAPTURE_INSTALLS, true));
        addGap(body, 9);
        body.addView(switchCard("Capture updates", "Save the newly installed release after an app update completes.", AppConstants.PREF_CAPTURE_UPDATES, true));
        addGap(body, 9);
        body.addView(switchCard("Include system apps", "Show and capture system packages. Rollback behavior for system apps is device-dependent.", AppConstants.PREF_INCLUDE_SYSTEM, false));
        addGap(body, 9);
        body.addView(switchCard("Snapshot notifications", "Show a low-priority notification after an automatic capture succeeds.", AppConstants.PREF_NOTIFY_CAPTURE, true));
        addGap(body, 9);
        body.addView(infoCard("Background reconciliation", "Android schedules a persistent vault reconciliation about every 15 minutes. Live package events are captured sooner while Roll Backer is running. Android may defer background jobs under device power or app-restriction policies.", C_BLUE));

        addGap(body, 20);
        addSectionTitle(body, "Retention", "Control how many releases are kept for each app");
        LinearLayout retentionCard = card();
        retentionCard.addView(text("Per-app release limit", 16, C_TEXT, Typeface.BOLD));
        retentionCard.addView(text(retentionLabel() + ". Older releases are removed only after a newer snapshot has been safely written.", 13, C_MUTED, Typeface.NORMAL));
        addGap(retentionCard, 12);
        TextView changeRetention = secondaryButton("Change retention");
        changeRetention.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseRetention();
            }
        });
        retentionCard.addView(changeRetention, fullWidth());
        body.addView(retentionCard);

        addGap(body, 20);
        addSectionTitle(body, "Vault maintenance", "Integrity and storage controls");
        LinearLayout maintenance = card();
        TextView verify = secondaryButton("Verify every saved APK");
        verify.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                verifyAll();
            }
        });
        maintenance.addView(verify, fullWidth());
        addGap(maintenance, 9);
        TextView clear = dangerButton("Delete all snapshots");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClearVault();
            }
        });
        maintenance.addView(clear, fullWidth());
        body.addView(maintenance);

        addGap(body, 20);
        addSectionTitle(body, "Installation permission", "Required only when you install a saved release");
        LinearLayout install = card();
        boolean allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls();
        install.addView(text(allowed ? "Install permission is enabled" : "Install permission is not enabled", 16, allowed ? C_GREEN : C_TEXT, Typeface.BOLD));
        install.addView(text("Android requires explicit permission for Roll Backer to submit saved APK sets to the system package installer.", 13, C_MUTED, Typeface.NORMAL));
        addGap(install, 12);
        TextView permission = secondaryButton(allowed ? "Open install permission settings" : "Enable install permission");
        permission.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUnknownSourcesSettings();
            }
        });
        install.addView(permission, fullWidth());
        body.addView(install);

        addGap(body, 20);
        LinearLayout about = card();
        about.addView(text("Roll Backer " + AppConstants.APP_VERSION, 17, C_TEXT, Typeface.BOLD));
        about.addView(text("Developed by " + AppConstants.DEVELOPER, 14, C_PURPLE, Typeface.BOLD));
        about.addView(text(AppConstants.TAGLINE, 12, C_MUTED, Typeface.NORMAL));
        addGap(about, 10);
        about.addView(text("Roll Backer stores installed APK sets in app-private storage under " + getFilesDir().getAbsolutePath() + "/" + SnapshotManager.VAULT_DIR + ". Each app receives its own folder, and every captured release receives a separate versioned subfolder with metadata and SHA-256 hashes.", 12, C_MUTED, Typeface.NORMAL));
        addGap(about, 8);
        about.addView(text("Important: Roll Backer snapshots application packages, not another app's private data. Android normally requires uninstalling a newer version before installing an older one, and that uninstall removes the target app's private data unless the target app restores it through its own backup or account sync.", 12, C_MUTED, Typeface.NORMAL));
        addGap(about, 8);
        about.addView(text("Copyright © 2026 " + AppConstants.DEVELOPER + ". All rights reserved.", 11, Color.rgb(126, 138, 165), Typeface.NORMAL));
        body.addView(about);
        addGap(body, 30);
        setPage(body);
    }

    private View switchCard(String title, String subtitle, final String key, boolean defaultValue) {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 15, C_TEXT, Typeface.BOLD));
        labels.addView(text(subtitle, 12, C_MUTED, Typeface.NORMAL));
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final Switch toggle = new Switch(this);
        toggle.setChecked(SnapshotManager.prefs(this).getBoolean(key, defaultValue));
        toggle.setContentDescription(title);
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> SnapshotManager.prefs(MainActivity.this).edit().putBoolean(key, isChecked).apply());
        row.addView(toggle);
        card.addView(row);
        return card;
    }

    private void addBrandHeader(LinearLayout body, String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView brand = new ImageView(this);
        brand.setImageResource(group.backgroundgremlin.rollbacker.R.mipmap.ic_launcher);
        brand.setScaleType(ImageView.ScaleType.CENTER_CROP);
        row.addView(brand, new LinearLayout.LayoutParams(dp(62), dp(62)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(13), 0, 0, 0);
        titles.addView(text(title, 27, C_TEXT, Typeface.BOLD));
        titles.addView(text(subtitle, 13, C_MUTED, Typeface.NORMAL));
        row.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(row, fullWidth());
        addGap(body, 20);
    }

    private void showOnboarding() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(4), dp(4), dp(4), 0);
        content.addView(text("Roll Backer tracks package changes with live events while active and a persistent Android-scheduled background reconciliation job, then copies each detected installed base APK and split APK set into a private versioned vault.", 14, C_TEXT, Typeface.NORMAL));
        addGap(content, 10);
        content.addView(text("Folder model", 13, C_BLUE, Typeface.BOLD));
        content.addView(text("RollbackVault → App name + package → Version release folder → base.apk + split APKs + metadata.json", 13, C_MUTED, Typeface.NORMAL));
        addGap(content, 10);
        content.addView(text("When you choose an older release, Android requires the newer app to be uninstalled before the older signed APK set can be installed. That uninstall removes the target app's private data.", 13, C_MUTED, Typeface.NORMAL));
        addGap(content, 12);
        content.addView(text("Developed by " + AppConstants.DEVELOPER, 12, C_PURPLE, Typeface.BOLD));
        content.addView(text(AppConstants.TAGLINE, 11, C_MUTED, Typeface.NORMAL));
        new AlertDialog.Builder(this)
                .setTitle("Welcome to Roll Backer")
                .setView(content)
                .setPositiveButton("Start protecting apps", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        SnapshotManager.prefs(MainActivity.this).edit().putBoolean(AppConstants.PREF_ONBOARDED, true).apply();
                        requestNotificationPermissionIfNeeded();
                    }
                })
                .setNeutralButton("Snapshot installed apps", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        SnapshotManager.prefs(MainActivity.this).edit().putBoolean(AppConstants.PREF_ONBOARDED, true).apply();
                        confirmSnapshotAll();
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void confirmSnapshotAll() {
        new AlertDialog.Builder(this)
                .setTitle("Snapshot all installed apps?")
                .setMessage("Roll Backer will copy the currently installed APK set for every visible non-system app that is not already captured. This can use substantial storage depending on your installed apps.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Snapshot all", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        snapshotAll();
                    }
                }).show();
    }

    private void snapshotAll() {
        final ProgressUi progress = showProgress("Protecting installed apps", "Reading packages.", true);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final List<SnapshotManager.InstalledApp> apps = SnapshotManager.listInstalledApps(MainActivity.this);
                postToUi(new Runnable() {
                    @Override public void run() { progress.bar.setMax(Math.max(1, apps.size())); }
                });
                int saved = 0;
                int skipped = 0;
                int failed = 0;
                for (int i = 0; i < apps.size(); i++) {
                    final int current = i + 1;
                    final SnapshotManager.InstalledApp app = apps.get(i);
                    postToUi(new Runnable() {
                        @Override
                        public void run() {
                            progress.message.setText("Protecting " + app.label);
                            progress.bar.setProgress(current);
                        }
                    });
                    SnapshotManager.SnapshotResult result = SnapshotManager.capturePackage(MainActivity.this, app.packageName);
                    if (result.success && !result.skipped) saved++;
                    else if (result.skipped) skipped++;
                    else failed++;
                }
                final int fSaved = saved;
                final int fSkipped = skipped;
                final int fFailed = failed;
                postToUi(new Runnable() {
                    @Override
                    public void run() {
                        dismissProgress(progress);
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("Snapshot pass complete")
                                .setMessage(fSaved + " new releases saved. " + fSkipped + " already protected or excluded. " + fFailed + " failed to capture.")
                                .setPositiveButton("Done", null)
                                .show();
                        if (currentTab == 0) showHome();
                        else if (currentTab == 2) showApps();
                    }
                });
            }
        });
    }

    private void snapshotOne(final SnapshotManager.InstalledApp app) {
        final ProgressUi progress = showProgress("Creating snapshot", "Copying " + app.label + " APK set and hashing files.", false);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final SnapshotManager.SnapshotResult result = SnapshotManager.capturePackage(MainActivity.this, app.packageName);
                postToUi(new Runnable() {
                    @Override
                    public void run() {
                        dismissProgress(progress);
                        Toast.makeText(MainActivity.this, result.message, result.success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                        if (result.success && !result.skipped && result.snapshot != null) Notifications.captureSaved(MainActivity.this, result.snapshot);
                        if (currentTab == 2) showApps();
                    }
                });
            }
        });
    }

    private void requestInstall(final SnapshotInfo snapshot) {
        if (snapshot == null) return;
        if (!SnapshotManager.verifySnapshot(snapshot)) {
            new AlertDialog.Builder(this)
                    .setTitle("Snapshot integrity check failed")
                    .setMessage("One or more saved APK files no longer match the SHA-256 hashes recorded when the release was captured. Roll Backer will not install a corrupted snapshot.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        long installed = SnapshotManager.installedVersionCode(this, snapshot.packageName);
        if (installed > snapshot.versionCode) {
            new AlertDialog.Builder(this)
                    .setTitle("Roll back " + snapshot.appLabel + "?")
                    .setMessage("Android does not allow a normal app to install an older version over a newer one. Roll Backer will first open Android's uninstall confirmation for the current version, then install the selected saved release.\n\nUninstalling removes " + snapshot.appLabel + "'s private app data. Roll Backer snapshots APK files, not another app's private data.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Continue", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            beginDowngrade(snapshot);
                        }
                    }).show();
        } else {
            ensureInstallPermissionThenInstall(snapshot);
        }
    }

    private void beginDowngrade(SnapshotInfo snapshot) {
        savePendingOperation(snapshot, AppConstants.PENDING_STAGE_AWAIT_UNINSTALL);
        Intent uninstall = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + snapshot.packageName));
        uninstall.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        try {
            startActivityForResult(uninstall, REQ_UNINSTALL);
        } catch (RuntimeException e) {
            clearPendingOperation();
            Toast.makeText(this, "Android could not open the uninstall confirmation: " + safeError(e), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_UNINSTALL) {
            if (resultCode == RESULT_OK) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        maybeContinuePendingOperation();
                    }
                }, 800L);
            } else {
                clearPendingOperation();
                Toast.makeText(this, "Rollback cancelled; the saved release remains in the vault.", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_UNKNOWN_SOURCES) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    maybeContinuePendingOperation();
                }
            }, 350L);
        }
    }

    private void maybeContinuePendingOperation() {
        if (pendingInstallInFlight) return;
        SharedPreferences prefs = SnapshotManager.prefs(this);
        String pkg = prefs.getString(AppConstants.PREF_PENDING_PACKAGE, null);
        String path = prefs.getString(AppConstants.PREF_PENDING_SNAPSHOT, null);
        if (pkg == null || path == null) return;

        long pendingSince = prefs.getLong(AppConstants.PREF_PENDING_SINCE, 0L);
        long pendingAge = pendingSince <= 0L ? Long.MAX_VALUE : System.currentTimeMillis() - pendingSince;
        if (pendingAge < 0L || pendingAge > AppConstants.PENDING_OPERATION_TIMEOUT_MS) {
            clearPendingOperation();
            Toast.makeText(this, "The pending rollback expired. The saved release remains in the vault.", Toast.LENGTH_LONG).show();
            return;
        }

        SnapshotInfo match = null;
        for (SnapshotInfo s : SnapshotManager.listSnapshotsForPackage(this, pkg)) {
            if (s.folder != null && path.equals(s.folder.getAbsolutePath())) {
                match = s;
                break;
            }
        }
        if (match == null) {
            clearPendingOperation();
            Toast.makeText(this, "The pending rollback snapshot no longer exists.", Toast.LENGTH_LONG).show();
            return;
        }

        String stage = prefs.getString(AppConstants.PREF_PENDING_STAGE, null);
        if (stage == null) {
            long installedCode = SnapshotManager.installedVersionCode(this, pkg);
            stage = installedCode > match.versionCode
                    ? AppConstants.PENDING_STAGE_AWAIT_UNINSTALL
                    : AppConstants.PENDING_STAGE_AWAIT_PERMISSION;
            prefs.edit().putString(AppConstants.PREF_PENDING_STAGE, stage).apply();
        }

        if (AppConstants.PENDING_STAGE_AWAIT_UNINSTALL.equals(stage)) {
            if (SnapshotManager.isPackageInstalled(this, pkg)) return;
            ensureInstallPermissionThenInstall(match);
            return;
        }
        if (AppConstants.PENDING_STAGE_AWAIT_PERMISSION.equals(stage)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !getPackageManager().canRequestPackageInstalls()) {
                return;
            }
            installSnapshot(match);
            return;
        }
        if (AppConstants.PENDING_STAGE_INSTALL_SUBMITTED.equals(stage)) {
            return;
        }

        clearPendingOperation();
    }

    private void ensureInstallPermissionThenInstall(final SnapshotInfo snapshot) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            savePendingOperation(snapshot, AppConstants.PENDING_STAGE_AWAIT_PERMISSION);
            new AlertDialog.Builder(this)
                    .setTitle("Allow Roll Backer to install apps")
                    .setMessage("Android requires a one-time per-app permission before Roll Backer can submit a saved APK set to the system installer. The selected snapshot stays safely in the vault if you choose Not now.")
                    .setNegativeButton("Not now", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            clearPendingOperation();
                        }
                    })
                    .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            openUnknownSourcesSettings();
                        }
                    }).show();
            return;
        }
        installSnapshot(snapshot);
    }

    private void savePendingOperation(SnapshotInfo snapshot, String stage) {
        if (snapshot == null || snapshot.folder == null) return;
        SnapshotManager.prefs(this).edit()
                .putString(AppConstants.PREF_PENDING_PACKAGE, snapshot.packageName)
                .putString(AppConstants.PREF_PENDING_SNAPSHOT, snapshot.folder.getAbsolutePath())
                .putString(AppConstants.PREF_PENDING_STAGE, stage)
                .putLong(AppConstants.PREF_PENDING_SINCE, System.currentTimeMillis())
                .apply();
    }

    private void installSnapshot(final SnapshotInfo snapshot) {
        savePendingOperation(snapshot, AppConstants.PENDING_STAGE_INSTALL_SUBMITTED);
        pendingInstallInFlight = true;
        final ProgressUi progress = showProgress("Preparing install", "Verifying and submitting " + snapshot.appLabel + " " + snapshot.versionName + " to Android.", false);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    PackageInstallerHelper.installSnapshot(MainActivity.this, snapshot);
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                final String finalError = error;
                postToUi(new Runnable() {
                    @Override
                    public void run() {
                        pendingInstallInFlight = false;
                        dismissProgress(progress);
                        if (finalError != null) {
                            clearPendingOperation();
                            Toast.makeText(MainActivity.this, "Install submission failed: " + finalError, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(MainActivity.this, "Install submitted. Complete Android's confirmation prompt.", Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
    }

    private void openUnknownSourcesSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
        try {
            startActivityForResult(intent, REQ_UNKNOWN_SOURCES);
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
        }
    }

    private void verifyOne(final SnapshotInfo snapshot) {
        final ProgressUi progress = showProgress("Verifying snapshot", "Hashing every saved APK part.", false);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final boolean ok = SnapshotManager.verifySnapshot(snapshot);
                postToUi(new Runnable() {
                    @Override
                    public void run() {
                        dismissProgress(progress);
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle(ok ? "Snapshot verified" : "Integrity failure")
                                .setMessage(ok ? "Every APK part matches the SHA-256 hash recorded at capture time." : "At least one APK file is missing, has changed size, or no longer matches its recorded SHA-256 hash.")
                                .setPositiveButton("OK", null)
                                .show();
                    }
                });
            }
        });
    }

    private void verifyAll() {
        final ProgressUi progress = showProgress("Verifying vault", "Hashing all saved APK files.", false);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final int failures = SnapshotManager.verifyAll(MainActivity.this);
                final int total = SnapshotManager.listAllSnapshots(MainActivity.this).size();
                postToUi(new Runnable() {
                    @Override
                    public void run() {
                        dismissProgress(progress);
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle(failures == 0 ? "Vault verified" : "Vault integrity warning")
                                .setMessage(failures == 0 ? "All " + total + " saved releases passed SHA-256 verification." : failures + " of " + total + " saved releases failed verification. Roll Backer will refuse to install a snapshot that does not match its recorded hashes.")
                                .setPositiveButton("OK", null)
                                .show();
                    }
                });
            }
        });
    }

    private void confirmDeleteSnapshot(final SnapshotInfo snapshot) {
        String pendingPath = SnapshotManager.prefs(this)
                .getString(AppConstants.PREF_PENDING_SNAPSHOT, null);
        if (snapshot != null && snapshot.folder != null &&
                snapshot.folder.getAbsolutePath().equals(pendingPath)) {
            new AlertDialog.Builder(this)
                    .setTitle("Release is in use")
                    .setMessage("This saved release is part of the active rollback or reinstall operation. Complete or cancel that operation before deleting the snapshot.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete saved release?")
                .setMessage(snapshot.appLabel + " " + snapshot.versionName + " (" + snapshot.versionCode + ") will be permanently removed from the Roll Backer vault.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        executor.execute(new Runnable() {
                            @Override
                            public void run() {
                                final boolean ok = SnapshotManager.deleteSnapshot(MainActivity.this, snapshot);
                                postToUi(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(MainActivity.this, ok ? "Snapshot deleted." : "Snapshot could not be fully deleted.", Toast.LENGTH_SHORT).show();
                                        showAppDetail(snapshot.packageName);
                                    }
                                });
                            }
                        });
                    }
                }).show();
    }

    private void confirmClearVault() {
        new AlertDialog.Builder(this)
                .setTitle("Delete every snapshot?")
                .setMessage("This permanently deletes every protected app release in Roll Backer's private vault. Installed apps are not changed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete vault contents", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        clearPendingOperation();
                        executor.execute(new Runnable() {
                            @Override
                            public void run() {
                                SnapshotManager.clearVault(MainActivity.this);
                                postToUi(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(MainActivity.this, "Vault cleared.", Toast.LENGTH_SHORT).show();
                                        showSettings();
                                    }
                                });
                            }
                        });
                    }
                }).show();
    }

    private void chooseRetention() {
        final String[] labels = {"3 releases per app", "5 releases per app", "10 releases per app", "Unlimited"};
        final int[] values = {3, 5, 10, 0};
        int current = SnapshotManager.prefs(this).getInt(AppConstants.PREF_RETENTION, AppConstants.DEFAULT_RETENTION);
        int selected = 1;
        for (int i = 0; i < values.length; i++) if (values[i] == current) selected = i;
        new AlertDialog.Builder(this)
                .setTitle("Per-app retention")
                .setSingleChoiceItems(labels, selected, null)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        AlertDialog d = (AlertDialog) dialog;
                        int pos = d.getListView().getCheckedItemPosition();
                        SnapshotManager.prefs(MainActivity.this).edit().putInt(AppConstants.PREF_RETENTION, values[pos]).apply();
                        showSettings();
                    }
                }).show();
    }

    private String retentionLabel() {
        int retention = SnapshotManager.prefs(this).getInt(AppConstants.PREF_RETENTION, AppConstants.DEFAULT_RETENTION);
        return retention <= 0 ? "Unlimited retention" : "Keep " + retention + " per app";
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private void clearPendingOperation() {
        SnapshotManager.prefs(this).edit()
                .remove(AppConstants.PREF_PENDING_PACKAGE)
                .remove(AppConstants.PREF_PENDING_SNAPSHOT)
                .remove(AppConstants.PREF_PENDING_STAGE)
                .remove(AppConstants.PREF_PENDING_SINCE)
                .apply();
    }

    private String safeError(Throwable error) {
        if (error == null) return "Unknown error";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static final class ProgressUi {
        final AlertDialog dialog;
        final TextView message;
        final ProgressBar bar;

        ProgressUi(AlertDialog dialog, TextView message, ProgressBar bar) {
            this.dialog = dialog;
            this.message = message;
            this.bar = bar;
        }
    }

    private ProgressUi showProgress(String title, String messageText, boolean determinate) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(4), dp(8), dp(4), dp(2));

        TextView message = text(messageText, 13, C_MUTED, Typeface.NORMAL);
        content.addView(message, fullWidth());
        addGap(content, 14);

        ProgressBar bar;
        if (determinate) {
            bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setIndeterminate(false);
            bar.setMax(1);
            content.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));
        } else {
            bar = new ProgressBar(this);
            bar.setIndeterminate(true);
            LinearLayout spinnerRow = new LinearLayout(this);
            spinnerRow.setGravity(Gravity.CENTER);
            spinnerRow.addView(bar, new LinearLayout.LayoutParams(dp(42), dp(42)));
            content.addView(spinnerRow, fullWidth());
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(content)
                .setCancelable(false)
                .create();
        dialog.show();
        return new ProgressUi(dialog, message, bar);
    }

    private void dismissProgress(ProgressUi progress) {
        if (progress != null && progress.dialog.isShowing()) {
            progress.dialog.dismiss();
        }
    }

    private LinearLayout pageBody() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(18), dp(18), dp(24));
        return body;
    }

    private void setPage(LinearLayout body) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        contentHost.removeAllViews();
        contentHost.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        card.setBackground(solid(C_CARD, dp(18), C_BORDER, 1));
        card.setLayoutParams(fullWidth());
        return card;
    }

    private View infoCard(String title, String bodyText, int accent) {
        LinearLayout card = card();
        TextView titleView = text(title, 17, accent, Typeface.BOLD);
        card.addView(titleView);
        addGap(card, 6);
        card.addView(text(bodyText, 13, C_MUTED, Typeface.NORMAL));
        return card;
    }

    private View stepCard(String number, String title, String bodyText) {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        TextView badge = text(number, 15, C_TEXT, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(solid(C_PURPLE, dp(20), 0, 0));
        row.addView(badge, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(12), 0, 0, 0);
        copy.addView(text(title, 16, C_TEXT, Typeface.BOLD));
        copy.addView(text(bodyText, 12, C_MUTED, Typeface.NORMAL));
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row);
        return card;
    }

    private View releaseCard(SnapshotInfo s, boolean showActions) {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(appIcon(SnapshotManager.snapshotIcon(this, s), 50));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(12), 0, 0, 0);
        copy.addView(text(s.appLabel, 16, C_TEXT, Typeface.BOLD));
        copy.addView(text("v" + s.versionName + " · code " + s.versionCode, 12, C_MUTED, Typeface.NORMAL));
        copy.addView(text(SnapshotManager.formatDate(s.captureTime), 11, Color.rgb(126, 138, 165), Typeface.NORMAL));
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(chip(SnapshotManager.humanBytes(s.totalBytes), C_BLUE));
        card.addView(row);
        return card;
    }

    private ImageView appIcon(Drawable drawable, int sizeDp) {
        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setImageDrawable(drawable);
        icon.setBackground(solid(Color.rgb(9, 11, 23), dp(13), C_BORDER, 1));
        icon.setPadding(dp(2), dp(2), dp(2), dp(2));
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return icon;
    }

    private TextView chip(String label, int accent) {
        TextView chip = text(label, 11, accent, Typeface.BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(9), dp(5), dp(9), dp(5));
        chip.setBackground(solid(Color.argb(42, Color.red(accent), Color.green(accent), Color.blue(accent)), dp(12), accent, 1));
        return chip;
    }

    private TextView primaryButton(String label) {
        TextView button = text(label, 14, Color.WHITE, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), dp(12), dp(14), dp(12));
        button.setMinHeight(dp(48));
        button.setClickable(true);
        button.setFocusable(true);
        button.setBackground(buttonGradient());
        return button;
    }

    private TextView secondaryButton(String label) {
        TextView button = text(label, 13, C_TEXT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        button.setMinHeight(dp(44));
        button.setClickable(true);
        button.setFocusable(true);
        button.setBackground(solid(C_CARD_ALT, dp(13), C_BORDER, 1));
        return button;
    }

    private TextView dangerButton(String label) {
        TextView button = text(label, 13, C_RED, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        button.setMinHeight(dp(44));
        button.setClickable(true);
        button.setFocusable(true);
        button.setBackground(solid(Color.argb(35, 255, 104, 120), dp(13), C_RED, 1));
        return button;
    }

    private TextView dangerLink(String label) {
        TextView button = text(label, 12, C_RED, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(10), dp(8), dp(10), dp(8));
        button.setClickable(true);
        return button;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.create("sans", style));
        tv.setLineSpacing(0, 1.08f);
        tv.setIncludeFontPadding(true);
        return tv;
    }

    private void addSectionTitle(LinearLayout parent, String title, String subtitle) {
        parent.addView(text(title, 19, C_TEXT, Typeface.BOLD));
        parent.addView(text(subtitle, 12, C_MUTED, Typeface.NORMAL));
        addGap(parent, 11);
    }

    private void postToUi(final Runnable action) {
        if (action == null) return;
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                action.run();
            }
        });
    }

    private void showLoading(LinearLayout host, String label) {
        LinearLayout row = card();
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        ProgressBar progress = new ProgressBar(this);
        row.addView(progress, new LinearLayout.LayoutParams(dp(42), dp(42)));
        addGap(row, 8);
        TextView message = text(label, 13, C_MUTED, Typeface.NORMAL);
        message.setGravity(Gravity.CENTER);
        row.addView(message);
        host.addView(row);
    }

    private GradientDrawable backgroundGradient() {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(4, 7, 18), Color.rgb(8, 15, 39), Color.rgb(21, 5, 35)});
        d.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        return d;
    }

    private GradientDrawable buttonGradient() {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{C_BLUE, C_PURPLE});
        d.setCornerRadius(dp(14));
        return d;
    }

    private GradientDrawable solid(int color, int radiusPx, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        if (strokeDp > 0) d.setStroke(dp(strokeDp), strokeColor);
        return d;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void addGap(LinearLayout parent, int dpValue) {
        Space gap = new Space(this);
        parent.addView(gap, new LinearLayout.LayoutParams(1, dp(dpValue)));
    }

    private void addHorizontalGap(LinearLayout parent, int dpValue) {
        Space gap = new Space(this);
        parent.addView(gap, new LinearLayout.LayoutParams(dp(dpValue), 1));
    }

    private long totalBytes(List<SnapshotInfo> list) {
        long total = 0;
        for (SnapshotInfo s : list) total += s.totalBytes;
        return total;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class Api33Back {
        private Api33Back() {}

        static Object register(final MainActivity activity) {
            OnBackInvokedCallback callback = new OnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    activity.returnFromDetail();
                }
            };
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        }

        static void unregister(MainActivity activity, Object callback) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (OnBackInvokedCallback) callback);
        }
    }

}
