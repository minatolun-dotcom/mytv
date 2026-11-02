package com.gathin.tvupdater;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The one screen: every tracked app, its state after a check, and OK on a row that has an update
 * ready. The check and the download run on one background thread; Android's own install dialog is
 * the only thing the remote confirms, once per app. No services, no polling, no alarm: the page
 * checks when it is opened, as the add-on rules ask.
 */
public class UpdaterActivity extends Activity {

    static final int ST_CHECKING = 0;
    static final int ST_CURRENT = 1;
    static final int ST_AVAILABLE = 2;
    static final int ST_ERROR = 3;
    static final int ST_INSTALLING = 4;

    static final class Row {
        final Sources.Src src;
        String status = "not checked yet";
        int state = -1;           // -1 = not checked yet, shows the grey dot
        int pct = -1;             // 0..100 while downloading, else -1
        Sources.Remote remote;
        File apk;                 // a downloaded or first-check-staged APK, ready to install

        Row(Sources.Src src) {
            this.src = src;
        }
    }

    final List<Row> rows = new ArrayList<>();
    final ExecutorService io = Executors.newSingleThreadExecutor();
    final Handler main = new Handler(Looper.getMainLooper());
    final Adapter adapter = new Adapter();

    /** The activity the install receiver refreshes; the app is one activity, started on demand. */
    static volatile UpdaterActivity active;

    /** The row whose install is waiting on the "install unknown apps" permission. */
    Row pending;

    static final String CHANNEL = "updates";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        for (Sources.Src src : Sources.ALL) {
            rows.add(new Row(src));
        }
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setSelector(R.drawable.row_selector);   // the selected row, so a screenshot can show it
        list.setOnItemClickListener((parent, view, position, id) -> onRow(rows.get(position)));
        setContentView(list);
        noticeChannel();
        checkAll();
    }

    @Override
    protected void onStart() {
        super.onStart();
        active = this;
    }

    @Override
    protected void onStop() {
        if (active == this) {
            active = null;
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from the "install unknown apps" screen: the permission is what was missing.
        if (pending != null && getPackageManager().canRequestPackageInstalls()) {
            Row row = pending;
            pending = null;
            commit(row);
        }
    }

    private void noticeChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Update results", NotificationManager.IMPORTANCE_LOW));
        }
    }

    void checkAll() {
        for (Row row : rows) {
            row.state = ST_CHECKING;
            row.status = "checking …";
            row.pct = -1;
        }
        adapter.notifyDataSetChanged();
        for (Row row : rows) {
            check(row);
        }
    }

    /** One app's check, on the IO thread: resolve the source, compare with what is installed. */
    void check(Row row) {
        io.execute(() -> {
            try {
                Sources.Remote remote = Sources.resolve(row.src);
                PackageInfo mine = installed(row.src.pkg);
                if (mine == null) {
                    publish(row, ST_ERROR, "not installed: " + label(row.src.pkg), remote);
                    return;
                }
                if (remote.versionCode > 0) {
                    // F-Droid numbers its builds honestly: only a higher versionCode is newer.
                    boolean newer = remote.versionCode > Installer.versionCode(mine);
                    String note = String.format(Locale.US, "installed %s (%d) · source %s (%d)",
                            nullToQ(mine.versionName), Installer.versionCode(mine),
                            nullToQ(remote.versionName), remote.versionCode);
                    if (newer && fromPlay(getPackageManager(), row.src.pkg)) {
                        publish(row, ST_ERROR, playNote(mine.versionName), remote);
                    } else {
                        publish(row, newer ? ST_AVAILABLE : ST_CURRENT,
                                newer ? note + " — OK to download and install it" : note + " · up to date",
                                remote);
                    }
                } else if (remote.versionName != null) {
                    // GitHub's tag: the words are the comparison, because a tag is not a versionCode.
                    boolean newer = !remote.versionName.trim().equals(nullToQ(mine.versionName).trim());
                    String note = String.format(Locale.US, "installed %s · source %s",
                            nullToQ(mine.versionName), remote.versionName);
                    if (newer && fromPlay(getPackageManager(), row.src.pkg)) {
                        publish(row, ST_ERROR, playNote(mine.versionName), remote);
                    } else {
                        publish(row, newer ? ST_AVAILABLE : ST_CURRENT,
                                newer ? note + " — OK to download and install it" : note + " · up to date",
                                remote);
                    }
                } else {
                    checkPlainUrl(row, remote, mine);
                }
                Prefs.saveSince(this, row.src.pkg, remote);
            } catch (Exception e) {
                publish(row, ST_ERROR, "check failed: " + e.getMessage(), null);
            }
        });
    }

    /** Where a plain URL's first-check APK is staged: on disk, so a restart still knows the
     *  server's copy is newer than the installed one. */
    private File probeFile(Row row) {
        return new File(new File(getFilesDir(), "probe"), row.src.pkg + ".apk");
    }

    /** A plain URL publishes no metadata (Atlantic): the saved change token decides, and the
     *  very first check has nothing to compare, so it stages the APK once and asks the APK. */
    private void checkPlainUrl(Row row, Sources.Remote remote, PackageInfo mine) throws Exception {
        boolean seen = Prefs.hasSeen(this, row.src.pkg);
        boolean changed = Prefs.isChanged(this, row.src.pkg, remote);
        String installedNote = String.format(Locale.US, "installed %s",
                nullToQ(mine.versionName));
        if (seen && !changed) {
            // The token says the server is quiet — but a staged copy from an earlier check may
            // still be ahead of what is installed, and that update must not be lost.
            File probe = probeFile(row);
            if (probe.isFile()) {
                PackageInfo archive = Installer.archiveInfo(getPackageManager(), probe);
                if (archive != null && archive.versionCode != 0
                        && Installer.versionCode(archive) > Installer.versionCode(mine)) {
                    if (fromPlay(getPackageManager(), row.src.pkg)) {
                        publish(row, ST_ERROR, playNote(mine.versionName), remote);
                        return;
                    }
                    row.apk = probe;
                    publish(row, ST_AVAILABLE, installedNote + " · the server's copy is newer ("
                            + archive.versionName + ") — OK to install it", remote);
                    return;
                }
                probe.delete();   // stale: the installed copy caught up with it
            }
            publish(row, ST_CURRENT, installedNote + " · up to date", remote);
            return;
        }
        if (fromPlay(getPackageManager(), row.src.pkg)) {
            // Nothing downloaded: a Play copy can only ever be replaced by Play itself.
            publish(row, ST_ERROR, playNote(mine.versionName), remote);
            return;
        }
        if (!seen) {
            File probe = probeFile(row);
            Installer.fetch(remote.url, probe, null);
            PackageInfo archive = Installer.archiveInfo(getPackageManager(), probe);
            if (archive == null) {
                probe.delete();
                publish(row, ST_ERROR, "Android cannot read that APK", remote);
                return;
            }
            String abi = Installer.abiMismatch(probe);
            if (abi != null) {
                probe.delete();
                publish(row, ST_ERROR, abi, remote);
                return;
            }
            String mismatch = Installer.signerMismatch(mine, archive);
            if (mismatch != null) {
                probe.delete();
                publish(row, ST_ERROR, mismatch, remote);
                return;
            }
            Prefs.markSeen(this, row.src.pkg);
            if (Installer.versionCode(archive) > Installer.versionCode(mine)) {
                row.apk = probe;   // reuse the download: OK installs this very file
                publish(row, ST_AVAILABLE, installedNote + " · the server's copy is newer ("
                        + archive.versionName + ") — OK to install it", remote);
            } else {
                probe.delete();
                publish(row, ST_CURRENT, installedNote + " · up to date", remote);
            }
            return;
        }
        row.apk = null;   // an older staged file would install the wrong build
        publish(row, ST_AVAILABLE, installedNote + " · the file changed on the server"
                + " — OK to download and install it", remote);
    }

    void publish(Row row, int state, String status, Sources.Remote remote) {
        main.post(() -> {
            row.state = state;
            row.status = status;
            if (remote != null) {
                row.remote = remote;
            }
            adapter.notifyDataSetChanged();
        });
    }

    PackageInfo installed(String pkg) {
        try {
            // The signing flags matter: without them signingInfo and signatures come back null
            // and the signer gate can never see a mismatch (it let LocalSend's through).
            int flags = Build.VERSION.SDK_INT >= 28
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;
            return getPackageManager().getPackageInfo(pkg, flags);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** The installer that put the copy on the TV, or null when nothing is recorded. */
    static String installerOf(PackageManager pm, String pkg) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                return pm.getInstallSourceInfo(pkg).getInstallingPackageName();
            }
            return pm.getInstallerPackageName(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    /** Google Play re-signs everything it serves, so no sideloaded build can ever replace a
     *  Play-installed copy in place — Play is its updater, and this app must say so. */
    static boolean fromPlay(PackageManager pm, String pkg) {
        return "com.android.vending".equals(installerOf(pm, pkg));
    }

    static String playNote(String version) {
        return String.format(Locale.US,
                "Google Play installed this copy%s — Google Play updates it, and sideloaded "
                        + "builds are signed differently, so Android would refuse them",
                version == null ? "" : " (" + version + ")");
    }

    String label(String pkg) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return getPackageManager().getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    static String nullToQ(String s) {
        return s == null ? "?" : s;
    }

    /** OK on a row: install what is already downloaded, download when the source has something
     *  newer, and re-check when the row is a question (up to date, error, plain URL). */
    void onRow(Row row) {
        if (row.state == ST_INSTALLING || row.state == ST_CHECKING) {
            return;
        }
        if (row.state == ST_AVAILABLE) {
            if (row.apk != null && row.apk.isFile()) {
                commit(row);
            } else {
                download(row);
            }
            return;
        }
        row.state = ST_CHECKING;
        row.status = "checking …";
        adapter.notifyDataSetChanged();
        check(row);
    }

    void download(Row row) {
        row.state = ST_INSTALLING;
        row.status = "downloading …";
        row.pct = -1;
        adapter.notifyDataSetChanged();
        File apk = new File(new File(getFilesDir(), "apk"), row.src.pkg + ".apk");
        io.execute(() -> {
            try {
                PackageInfo before = installed(row.src.pkg);
                if (before != null && fromPlay(getPackageManager(), row.src.pkg)) {
                    publish(row, ST_ERROR, playNote(before.versionName), null);
                    return;
                }
                Installer.fetch(row.remote.url, apk, pct -> main.post(() -> {
                    row.pct = pct;
                    row.status = "downloading … " + pct + "%";
                    adapter.notifyDataSetChanged();
                }));
                row.pct = -1;
                if (row.remote.sha256 != null
                        && !row.remote.sha256.equalsIgnoreCase(Installer.sha256(apk))) {
                    publish(row, ST_ERROR, "the download's sha256 does not match what GitHub published", null);
                    return;
                }
                PackageInfo archive = Installer.archiveInfo(getPackageManager(), apk);
                if (archive == null) {
                    publish(row, ST_ERROR, "Android cannot read that APK", null);
                    return;
                }
                String abi = Installer.abiMismatch(apk);
                if (abi != null) {
                    publish(row, ST_ERROR, abi, null);
                    return;
                }
                PackageInfo mine = installed(row.src.pkg);
                if (mine != null) {
                    String mismatch = Installer.signerMismatch(mine, archive);
                    if (mismatch != null) {
                        publish(row, ST_ERROR, mismatch, null);
                        return;
                    }
                    if (Installer.versionCode(archive) <= Installer.versionCode(mine)) {
                        publish(row, ST_CURRENT, "the downloaded build is not newer than the installed one", null);
                        return;
                    }
                }
                row.apk = apk;
                Prefs.markSeen(this, row.src.pkg);
                main.post(() -> {
                    row.state = ST_AVAILABLE;
                    row.status = "downloaded (" + (apk.length() / (1024 * 1024))
                            + " MB) — press OK to install " + archive.versionName;
                    adapter.notifyDataSetChanged();
                    commit(row);
                });
            } catch (Exception e) {
                publish(row, ST_ERROR, "download failed: " + e.getMessage(), null);
            }
        });
    }

    /** Stage the session and hand it to the TV's installer: the system dialog is next. */
    void commit(Row row) {
        if (row.apk == null || !row.apk.isFile()) {
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            pending = row;
            row.status = "the TV needs one allow first";
            adapter.notifyDataSetChanged();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        try {
            Installer.commit(this, row);
            row.state = ST_INSTALLING;
            row.status = "the TV will ask: confirm to install";
            adapter.notifyDataSetChanged();
        } catch (Exception e) {
            publish(row, ST_ERROR, "the installer refused: " + e.getMessage(), null);
        }
    }

    /** What the install dialog's status broadcast says, shown on the app's row. */
    void installed(String pkg, boolean ok, String text) {
        main.post(() -> {
            for (Row row : rows) {
                if (!row.src.pkg.equals(pkg)) {
                    continue;
                }
                if (ok) {
                    row.state = ST_CURRENT;
                    row.status = "installed " + text;
                    row.apk = null;
                } else if (row.state != ST_AVAILABLE) {
                    row.state = ST_ERROR;
                    row.status = "the TV declined: " + text;
                }
                adapter.notifyDataSetChanged();
                return;
            }
        });
        if (ok) {
            io.execute(() -> {
                try {
                    Thread.sleep(2500);   // let PackageManager's own dialog get out of the way
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                try {
                    InstallNotifier.send(this, pkg, text);
                } catch (Exception ignored) {
                    // no notification service: the row already says it installed
                }
            });
        }
    }

    /** One line per app: a state dot, the app's name, and what the check or the download says. */
    class Adapter extends BaseAdapter {

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row row = rows.get(position);
            View view = convertView != null ? convertView : getLayoutInflater()
                    .inflate(R.layout.item_row, parent, false);
            View dot = view.findViewById(R.id.dot);
            GradientDrawable shape = (GradientDrawable) dot.getBackground().mutate();
            int color;
            switch (row.state) {
                case ST_CURRENT: color = 0xFF4CAF50; break;
                case ST_AVAILABLE: color = 0xFFFFB300; break;
                case ST_ERROR: color = 0xFFEF5350; break;
                default: color = 0xFF9E9E9E; break;
            }
            shape.setColor(color);
            TextView title = view.findViewById(R.id.title);
            title.setText(label(row.src.pkg));
            TextView status = view.findViewById(R.id.status);
            status.setText(row.status);
            ProgressBar bar = view.findViewById(R.id.bar);
            if (row.pct >= 0) {
                bar.setVisibility(View.VISIBLE);
                bar.setProgress(row.pct);
            } else {
                bar.setVisibility(View.GONE);
            }
            return view;
        }
    }
}
