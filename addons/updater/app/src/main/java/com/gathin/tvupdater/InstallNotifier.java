package com.gathin.tvupdater;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * The quiet note after an install lands: a notification, once, saying which app was updated.
 * Nothing before that — the add-on's rules allow a notification for a result the person asked
 * for, and no badge, no repeat, no summary.
 */
final class InstallNotifier {

    private InstallNotifier() {
    }

    static void send(Context context, String pkg, String text) {
        PackageManager pm = context.getPackageManager();
        String name = pkg;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            name = pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            // the label went away mid-install: the package name still says which app it was
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, UpdaterActivity.CHANNEL)
                : new Notification.Builder(context);
        Notification n = b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(name + " was updated")
                .setContentText(text)
                .setContentIntent(PendingIntent.getActivity(
                        context, 0, new Intent(context, UpdaterActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_IMMUTABLE : 0)))
                .setAutoCancel(true)
                .build();
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(pkg.hashCode(), n);
    }
}
