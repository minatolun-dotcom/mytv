package com.gathin.tvupdater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/**
 * The status broadcast the PackageInstaller session sends when the TV's dialog is answered: the
 * app's row says "installed 1.0.9", or what the TV declined with. The dialog itself is the TV's;
 * this only hears its verdict.
 */
public class InstallResultReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        String pkg = intent.getStringExtra("package");
        if (pkg == null) {
            pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
        }
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android does not show the confirm dialog itself: it hands this app the intent for
            // it (EXTRA_INTENT) and waits. Starting it is what puts the TV's own dialog up.
            Intent show = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (show == null) {
                return;   // nothing to show: the session will fail with a status instead
            }
            show.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(show);
            } catch (Exception e) {
                // the TV refused to start the installer: the row already says the install failed
            }
            return;
        }
        if (pkg == null) {
            return;
        }
        String text;
        if (status == PackageInstaller.STATUS_SUCCESS) {
            text = "done";
        } else {
            text = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (text == null || text.isEmpty()) {
                text = "status " + status;
            }
        }
        UpdaterActivity activity = UpdaterActivity.active;
        if (activity != null) {
            activity.installed(pkg, status == PackageInstaller.STATUS_SUCCESS, text);
        }
    }
}
