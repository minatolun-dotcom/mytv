package com.gathin.tvupdater;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * What a plain URL published at the last check — its ETag, its date, its size, and that the app
 * has seen it at least once. That last bit matters: a first check cannot compare versions
 * against a URL that publishes none, so it stages the APK once and compares the two builds by
 * file. GitHub and F-Droid need none of this; their APIs answer the question themselves.
 */
final class Prefs {

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("sources", Context.MODE_PRIVATE);
    }

    /** Whether the URL's answer differs from what was saved at the last check. */
    static boolean isChanged(Context c, String pkg, Sources.Remote remote) {
        SharedPreferences p = prefs(c);
        String seen = p.getString(pkg + ".etag", null);
        long date = p.getLong(pkg + ".date", -1);
        long size = p.getLong(pkg + ".size", -1);
        if (seen == null && date < 0 && size < 0) {
            return false;   // nothing was ever saved: "first check" is the caller's branch
        }
        if (remote.etag != null) {
            return !remote.etag.equals(seen);
        }
        if (remote.modified > 0) {
            return remote.modified != date;
        }
        return remote.size > 0 && remote.size != size;
    }

    static boolean hasSeen(Context c, String pkg) {
        return prefs(c).getBoolean(pkg + ".seen", false);
    }

    /** Save a source's answer. Called after the comparison, never before. */
    static void saveSince(Context c, String pkg, Sources.Remote remote) {
        if (remote.etag == null && remote.modified <= 0 && remote.size <= 0) {
            return;
        }
        prefs(c).edit()
                .putString(pkg + ".etag", remote.etag)
                .putLong(pkg + ".date", remote.modified)
                .putLong(pkg + ".size", remote.size)
                .apply();
    }

    static void markSeen(Context c, String pkg) {
        prefs(c).edit().putBoolean(pkg + ".seen", true).apply();
    }
}
