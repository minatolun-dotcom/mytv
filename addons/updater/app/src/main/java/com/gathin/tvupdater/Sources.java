package com.gathin.tvupdater;

import org.json.JSONArray;
import org.json.JSONObject;

import android.os.Build;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * The apps this updater tracks and where each one's APK comes from. Mirrors apps.txt at the
 * repository root (the file tools/install_apps.py reads): the same three source kinds, the same
 * asset pin for this TV's armeabi-v7a. Keep the two in sync when an app is added.
 */
final class Sources {

    /** One tracked app. kind is "github" (owner/repo plus the asset to take), "fdroid"
     *  (the package name in F-Droid's index) or "url" (the APK itself; a ".../latest.apk"
     *  URL stays latest). */
    static final class Src {
        final String pkg, kind, id, asset;

        Src(String pkg, String kind, String id, String asset) {
            this.pkg = pkg;
            this.kind = kind;
            this.id = id;
            this.asset = asset;
        }
    }

    static final Src[] ALL = {
        new Src("io.gh.reisxd.tizentube.cobalt", "github", "reisxd/TizenTubeCobalt", "cobalt-arm.apk"),
        new Src("st.atlantic.app", "url", "https://atlantic.st/dl/app/latest.apk", null),
        new Src("net.mrowser", "github", "m-salehi-v/mrowser", "app-release.apk"),
        new Src("org.localsend.localsend_app", "fdroid", "org.localsend.localsend_app", null),
        new Src("org.videolan.vlc", "fdroid", "org.videolan.vlc", null),
    };

    /** What a source publishes right now: the download URL plus whatever metadata it has. */
    static final class Remote {
        final String url;
        /** The GitHub tag without its "v", or F-Droid's versionName; null when the source says nothing. */
        final String versionName;
        /** F-Droid's suggested versionCode; 0 when the source does not number its builds. */
        final long versionCode;
        /** The GitHub release's published sha256, hex; null when none is published. */
        final String sha256;
        /** A plain URL's change token (its ETag), null for the two APIs. */
        final String etag;
        /** Epoch millis of the file on a plain URL, 0 when the server does not say. */
        final long modified;
        /** Bytes when known, -1 otherwise. */
        final long size;

        Remote(String url, String versionName, long versionCode, String sha256,
               String etag, long modified, long size) {
            this.url = url;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.sha256 = sha256;
            this.etag = etag;
            this.modified = modified;
            this.size = size;
        }
    }

    static final String UA = "cleanyourtv-updater (addons/updater)";

    private Sources() {
    }

    static Remote resolve(Src src) throws Exception {
        if ("github".equals(src.kind)) {
            return github(src);
        }
        if ("fdroid".equals(src.kind)) {
            return fdroid(src);
        }
        return head(src);
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", UA);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        return c;
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder b = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                b.append(line).append('\n');
            }
        } finally {
            r.close();
        }
        return b.toString();
    }

    /** The newest GitHub release, the asset this TV's ABI takes, and the sha256 it publishes. */
    private static Remote github(Src src) throws Exception {
        HttpURLConnection c = open("https://api.github.com/repos/" + src.id + "/releases/latest");
        try {
            if (c.getResponseCode() != 200) {
                throw new Exception("GitHub answered " + c.getResponseCode() + " for " + src.id);
            }
            JSONObject release = new JSONObject(readAll(c.getInputStream()));
            JSONArray assets = release.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (!src.asset.equals(asset.optString("name"))) {
                    continue;
                }
                String tag = release.optString("tag_name", "");
                String version = tag.startsWith("v") ? tag.substring(1) : tag;
                String digest = asset.optString("digest", "");
                String sha256 = digest.startsWith("sha256:") ? digest.substring(7) : null;
                return new Remote(asset.getString("browser_download_url"), version, 0,
                        sha256, null, 0, asset.optLong("size", -1));
            }
            throw new Exception("release " + release.optString("tag_name") + " has no " + src.asset);
        } finally {
            c.disconnect();
        }
    }

    /** F-Droid's suggested build: its versionCode is the only honest comparison with what is
     *  installed, because F-Droid renumbers some apps' builds (ours: LocalSend 64 vs 643). */
    private static Remote fdroid(Src src) throws Exception {
        HttpURLConnection c = open("https://f-droid.org/api/v1/packages/" + src.id);
        try {
            if (c.getResponseCode() != 200) {
                throw new Exception("F-Droid answered " + c.getResponseCode() + " for " + src.id);
            }
            JSONObject pkg = new JSONObject(readAll(c.getInputStream()));
            long code = pkg.optLong("suggestedVersionCode", -1);
            if (code <= 0) {
                throw new Exception("F-Droid suggests no version for " + src.id);
            }
            String version = null;
            JSONArray list = pkg.optJSONArray("packages");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject entry = list.getJSONObject(i);
                    if (entry.optLong("versionCode", -1) == code) {
                        version = entry.optString("versionName", null);
                        break;
                    }
                }
            }
            String url = "https://f-droid.org/repo/" + src.id + "_" + code + ".apk";
            if (list != null && version != null) {
                // Some apps are published as one APK per architecture, each with its own
                // versionCode (LocalSend: 641 x86_64, 642 arm32, 643 arm64), and the API's
                // suggestedVersionCode does not say which is which. When siblings share the
                // suggested build's versionName, read their tails for a library this TV runs.
                java.util.List<Long> same = new java.util.ArrayList<>();
                for (int i = 0; i < list.length(); i++) {
                    JSONObject entry = list.getJSONObject(i);
                    if (version.equals(entry.optString("versionName", null))) {
                        same.add(entry.optLong("versionCode", -1));
                    }
                }
                if (same.size() > 1) {
                    try {
                        same.sort((a, b) -> Long.compare(b, a));   // newest first
                        for (long candidate : same) {
                            String candidateUrl = "https://f-droid.org/repo/" + src.id
+ "_" + candidate + ".apk";
                            if (tailNamesSupportedAbi(candidateUrl)) {
                                return new Remote(candidateUrl, version, candidate,
                                        null, null, 0, -1);
                            }
                        }
                    } catch (Exception ignored) {
                        // a probe that fails keeps the suggested build; the download's
                        // ABI gate has the last word either way
                    }
                }
            }
            return new Remote(url, version, code, null, null, 0, -1);
        } finally {
            c.disconnect();
        }
    }

    /** Whether the last 4 MB of an APK names a library this TV runs. The zip's directory sits
     *  near the end, so the tail normally lists every lib/ the build carries. A tail that names
     *  no lib/ at all answers "unknown", taken as yes — the download-time ABI gate decides for
     *  real once the whole file is there. */
    private static boolean tailNamesSupportedAbi(String url) throws Exception {
        HttpURLConnection c = open(url);
        c.setRequestProperty("Range", "bytes=-4194304");
        try {
            int status = c.getResponseCode();
            if (status != 206 && status != 200) {
                return false;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = c.getInputStream();
            try {
                byte[] buf = new byte[1 << 16];
                int n;
                long total = 0;
                while (total < (1L << 22) && (n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                }
            } finally {
                in.close();
            }
            String tail = out.toString("ISO-8859-1");
            for (String abi : Build.SUPPORTED_ABIS) {
                if (tail.contains("lib/" + abi + "/")) {
                    return true;
                }
            }
            return !tail.contains("lib/");   // nothing about libs in the tail: unknown
        } finally {
            c.disconnect();
        }
    }

    /** The RFC 1123 date some servers publish, or 0. Android's HttpURLConnection has no
     *  getDateHeader, so the one format nginx and friends send is parsed here. */
    private static long lastModified(String header) {
        if (header == null || header.isEmpty()) {
            return 0;
        }
        try {
            java.text.SimpleDateFormat fmt =
                    new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            fmt.setLenient(false);
            java.util.Date d = fmt.parse(header);
            return d != null ? d.getTime() : 0;
        } catch (Exception e) {
            return 0;   // a format this parser does not know: the first check falls back and downloads once
        }
    }

    /** A direct URL publishes no metadata: a HEAD gives the change token, the date and the size. */
    private static Remote head(Src src) throws Exception {
        HttpURLConnection c = open(src.id);
        c.setRequestMethod("HEAD");
        try {
            if (c.getResponseCode() / 100 != 2) {
                throw new Exception("server answered " + c.getResponseCode());
            }
            String etag = c.getHeaderField("ETag");
            long modified = lastModified(c.getHeaderField("Last-Modified"));
            return new Remote(src.id, null, 0, null, etag, modified, c.getContentLengthLong());
        } finally {
            c.disconnect();
        }
    }
}
