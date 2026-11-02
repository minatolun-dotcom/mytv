package com.gathin.tvupdater;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageInstaller;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Everything between "the source has a newer APK" and "the TV installed it": the download with
 * its sha256, the two gates (a build that is not newer, a build signed differently), and the
 * PackageInstaller session whose result lands in {@link InstallResultReceiver}. The install
 * itself is the TV's own dialog: one remote OK per app, which is all Android allows without
 * root or a shell.
 */
final class Installer {

    interface Progress {
        void percent(int pct);
    }

    private Installer() {
    }

    static long versionCode(PackageInfo pi) {
        return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
    }

    /** The APK's own versionCode and signer, read from the file before anything is asked of the TV. */
    static PackageInfo archiveInfo(PackageManager pm, File apk) {
        int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        return pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
    }

    /** The ABIs a downloaded APK actually carries, read from its own zip entries (lib/<abi>/...). */
    static Set<String> abis(File apk) throws Exception {
        Set<String> out = new HashSet<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                int slash = name.indexOf('/', 4);
                if (name.startsWith("lib/") && slash > 4) {
                    out.add(name.substring(4, slash));
                }
            }
        }
        return out;
    }

    /** A sentence when the APK carries no library this TV can run — what the system would
     *  refuse later as INSTALL_FAILED_NO_MATCHING_ABIS — or null when it fits. */
    static String abiMismatch(File apk) throws Exception {
        Set<String> have = abis(apk);
        if (have.isEmpty()) {
            return null;   // no native libraries: nothing to extract, any ABI takes it
        }
        for (String abi : Build.SUPPORTED_ABIS) {
            if (have.contains(abi)) {
                return null;
            }
        }
        StringBuilder found = new StringBuilder();
        for (String abi : have) {
            if (found.length() > 0) {
                found.append(", ");
            }
            found.append(abi);
        }
        return String.format(Locale.US, "that APK is built for %s; this TV runs %s",
                found, Build.SUPPORTED_ABIS[0]);
    }

    /** The hex SHA-256 digests of a package's signing certs, or null when the platform will not say. */
    static Set<String> signerDigests(PackageInfo pi) throws Exception {
        Signature[] sigs = null;
        if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
            sigs = pi.signingInfo.getApkContentsSigners();
        }
        if (sigs == null && pi.signatures != null) {
            sigs = pi.signatures;
        }
        if (sigs == null) {
            return null;
        }
        Set<String> out = new HashSet<>();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (Signature sig : sigs) {
            StringBuilder b = new StringBuilder();
            for (byte x : md.digest(sig.toByteArray())) {
                b.append(String.format(Locale.US, "%02x", x));
            }
            out.add(b.toString());
        }
        return out;
    }

    /** A sentence when the downloaded APK is signed differently than the copy on the TV, else null.
     *  The installer would refuse it anyway (ISSUES.md 17); this says so before the dialog. */
    static String signerMismatch(PackageInfo installed, PackageInfo archive) throws Exception {
        Set<String> have = signerDigests(installed);
        Set<String> came = signerDigests(archive);
        if (have == null || came == null) {
            return null;
        }
        return have.equals(came) ? null
                : "this APK is signed differently than the copy already installed, so Android would refuse it";
    }

    static void fetch(String url, File dest, Progress progress) throws Exception {
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new Exception("cannot make the download folder " + parent);
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", Sources.UA);
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setInstanceFollowRedirects(true);
        try {
            if (c.getResponseCode() / 100 != 2) {
                throw new Exception("the download answered " + c.getResponseCode());
            }
            long total = c.getContentLengthLong();
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[1 << 16];
                long done = 0;
                int last = -1;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0 && progress != null) {
                        int pct = (int) (done * 100 / total);
                        if (pct != last) {
                            last = pct;
                            progress.percent(pct);
                        }
                    }
                }
            }
        } finally {
            c.disconnect();
        }
    }

    static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder b = new StringBuilder();
        for (byte x : md.digest()) {
            b.append(String.format(Locale.US, "%02x", x));
        }
        return b.toString();
    }

    /** Stage the APK in a session and commit it: from here the TV's own dialog takes over. */
    static void commit(UpdaterActivity activity, UpdaterActivity.Row row) throws Exception {
        PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
        // There is no "update" mode: MODE_FULL_INSTALL against a package that exists IS the
        // update path, and the session then asks for the replace the dialog confirms.
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(row.src.pkg);
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (OutputStream out = session.openWrite("update", 0, -1);
                 InputStream in = new FileInputStream(row.apk)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            Intent fillIn = new Intent(activity, InstallResultReceiver.class)
                    .putExtra("package", row.src.pkg);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pending = PendingIntent.getBroadcast(activity, id, fillIn, flags);
            session.commit(pending.getIntentSender());
        }
    }
}
