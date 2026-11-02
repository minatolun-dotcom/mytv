# Updater

One page that updates the sideloaded apps this launcher's users keep outside the Play Store:
check every source at once, and press OK on the apps that have something newer — Android's own
install dialog is the only thing the remote confirms, once per app. For the
[Clean Your TV](../../README.md) launcher it declares a [module card](../../docs/modules.md), so
once you answer **Show card** its tile reads "Updater"; on any other Android TV home it is an
ordinary app.

## How it works

The page opens and checks its sources on one background thread: green dot means up to date,
amber means an update is ready, red means the check or the download failed, grey means not
tested yet. Nothing runs in the background, at boot, or on a schedule — the page checks when
you open it.

Android never lets a normal app install silently, so "one OK per app" is the whole design: the
page downloads and verifies an update, stages a `PackageInstaller` session, and the TV shows its
own confirm dialog. OK in that dialog is the install. No root, no Shizuku, no ADB kept open.

Four gates run before anything is asked of the TV:

- **The Google Play gate.** A copy Google Play installed (`installerPackageName`
  `com.android.vending`) is re-signed by Play, so no sideloaded build can ever replace it in
  place — Play is its updater. When a source has something newer for such a copy, the row says
  so in words instead of offering a download nothing can install.
- **The ABI gate.** The download's own zip is read for the `lib/` folders it carries: a build
  with no library this TV runs (F-Droid's LocalSend 643 is arm64-only; this TV is armeabi-v7a)
  is refused in words, where the system would only say
  `INSTALL_FAILED_NO_MATCHING_ABIS` after the dialog.
- **The signer gate.** A build signed differently than the copy already installed would be
  refused by Android anyway (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`); the page says so in words
  and deletes the download.
- **The version gate.** F-Droid renumbers some apps' builds (its LocalSend is versionCode 643
  where a Play-style build of the same version is 64), so a source that publishes a
  versionCode is compared by that, and a source that only publishes a name (GitHub tags) by
  the name.

## Sources

The five apps, in the same order and from the same URLs as `apps.txt` at the repository root —
the file `tools/install_apps.py` reads. Change an app's source there and here together.

| App | Kind | The APK |
|---|---|---|
| TizenTube (Cobalt) | GitHub `reisxd/TizenTubeCobalt` | the newest release's `cobalt-arm.apk` (arm32 pin), its published `sha256` checked after the download |
| Atlantic | the vendor's URL `https://atlantic.st/dl/app/latest.apk` | whatever the URL serves: an ETag/Last-Modified token decides whether it changed, and the first ever check stages the APK once and compares the two builds by file |
| Mrowser | GitHub `m-salehi-v/mrowser` | the newest release's `app-release.apk`, sha256 checked |
| LocalSend | F-Droid index | F-Droid publishes one APK per architecture with its own versionCode (641 x86_64, 642 arm32, 643 arm64 for 1.18.2) and its API does not say which is which, so the resolver reads the candidates' tails for a library this TV runs and takes the build that fits; compared by versionCode |
| VLC | F-Droid index | the suggested versionCode's APK, compared by versionCode |

The "install unknown apps" allow is one switch, once, in the TV's own settings; the page sends
you there the first time and remembers the answer afterwards.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Checking the three sources (GitHub's API, F-Droid's index, the vendor's URL) and downloading the APKs they publish. |
| `REQUEST_INSTALL_PACKAGES` | Staging the downloaded APK in a `PackageInstaller` session, so the TV shows its own confirm dialog. Without it the updater cannot hand an update to the system installer at all. |

That is all. What is sent: the requests themselves, to those sources and no other server. No
account, no telemetry, no ads, no background service, no location permission. The download
token each URL published is kept in the app's own storage (backups are off).

## Build and install

JDK 17 and Android SDK platform 34 with build-tools 34.0.0, as for the launcher:

```bash
cd addons/updater && ./gradlew assembleRelease
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py --push app/build/outputs/apk/release/app-release.apk /data/local/tmp/addon.apk
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py 'pm install -r /data/local/tmp/addon.apk; rm -f /data/local/tmp/addon.apk'
```

Press Home: the launcher asks once whether to show the card. Change the answer later by holding
OK on Settings, then Module cards.

The release build is signed with the debug key so it installs over ADB like the launcher. Sign
your own builds with your own key if you hand them out; the launcher pins its Show answer to
the signing key.
