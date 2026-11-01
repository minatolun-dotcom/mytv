# MYTV — runbook for our TV

Everything below has been run against the set and works. Follow it top to bottom to debloat,
re-install, or re-customise this TV without rediscovering anything.

Target: **Acer R4G 50"**, `TV_HOST=192.168.1.19`, Google TV 14, `UKR1.260308` (5597).

| Doc | What is in it |
|---|---|
| **[ISSUES.md](ISSUES.md)** | Every problem we hit and its fix — the bootloop, the lying default-launcher answer, the guard skips, the git push failure. **Start here when something looks wrong.** |
| [examples/acer-r4g-gtv/README.md](examples/acer-r4g-gtv/README.md) | The device, what is installed, what is kept vs disabled. |
| [docs/devices/acer-r4g.md](docs/devices/acer-r4g.md) | The cold-boot re-enable trap: ~60 packages come back, and `setupwraith` taking Home is the bootloop this TV had. |

## 0. One-time setup on the computer

```sh
git clone https://github.com/minatolun-dotcom/mytv.git && cd mytv
python3 -m venv tools/venv
tools/venv/bin/pip install -r tools/requirements.txt
```

`tools/tv.py` needs no `adb` binary — it speaks ADB over TCP itself.

## 1. Wake the TV and check it answers

TVs drop off Wi-Fi while rebooting, so poll rather than assume:

```sh
export TV_HOST=192.168.1.19
for i in $(seq 1 60); do adb disconnect $TV_HOST:5555 >/dev/null 2>&1; \
  out=$(timeout 3 adb connect $TV_HOST:5555 2>&1); case "$out" in *connected*) echo up; break;; esac; sleep 1; done

tools/tv.py --probe
```

Healthy answer: `ro.product.model = R4G`, `ro.build.version.sdk = 34`, `ro.adb.secure = 1`.

## 2. Debloat

```sh
TVINPUT_ALLOW=1 tools/disable.sh examples/acer-r4g-gtv/debloat.txt
```

- Saves a recovery record **before** touching the TV (`~/.cleanyourtv/recovery-*.json`).
- `TVINPUT_ALLOW=1` is required on this firmware: its `dumpsys` prints no permission block, so the
  input-driver guard fails closed and skips six harmless packages. Justification check:
  `tools/tv.py 'pm query-services --components -a android.media.tv.TvInputService'` → only
  `com.realtek.*`, none of them listed.
- `com.realtek.tv` is always skipped by the handlers guard (it owns HDMI/source switching). That is
  intended — hide it instead, step 4.
- Verify afterwards:

```sh
tools/tv.py 'pm list packages -d --user 0' | wc -l     # 6 disabled as of 2026-11-01 (40 before the uninstall pass, §2c)
```

**Undo:** `tools/restore.sh examples/acer-r4g-gtv/debloat.txt`

### 2b. Our five apps

Only needed on a fresh set (or after a factory reset). [`apps.txt`](apps.txt) at the repository root says
where each app's APK comes from — a GitHub release, F-Droid, or a URL — and the script resolves the
newest one from that source, pushes it and installs it:

```sh
TV_HOST=192.168.1.19 tools/venv/bin/python -I tools/install_apps.py            # every app in apps.txt
TV_HOST=192.168.1.19 tools/venv/bin/python -I tools/install_apps.py --dry-run  # resolve only, send nothing
TV_HOST=192.168.1.19 tools/venv/bin/python -I tools/install_apps.py --update   # install over what is there
```

- Nothing is ever uninstalled. An app already on the TV is skipped; `--update` runs `pm install -r`,
  and a build signed differently than the installed one is refused with that copy left alone
  (ISSUES.md 17).
- The sources are **named in `apps.txt`, not searched for**: a GitHub release is resolved through the
  API and checked against the sha256 it publishes, Atlantic comes straight from
  `https://atlantic.st/dl/app/latest.apk`, LocalSend and VLC from F-Droid's suggested version.
- This set is `armeabi-v7a` only, so `apps.txt` pins TizenTube to `cobalt-arm.apk`; the arm64 build
  would be refused with `INSTALL_FAILED_NO_MATCHING_ABIS`.
- TizenTube is ~80 MB: over `b64push.py` (48 KB shell chunks) that takes a while, so set
  `TV_ADB_BINARY` to a Google adb first (see [tools/native-adb.md](tools/native-adb.md)) and the push
  becomes one `adb push`.
- These are the same five packages `dock_apps` names, and the dock skips a missing app, so install
  order never matters.

### 2c. The uninstall pass (2026-11-01)

Hiding and disabling only ever covered packages that stay installed — the remote's Netflix/YouTube/
Prime/Hotstar buttons still opened them from any screen, and the firmware's cold-boot job kept
re-enabling what was merely disabled (§7, [ISSUES.md](ISSUES.md) 24). So every bloat package that was
only disabled or hidden was removed for user 0 instead, one call per package:

```sh
tools/tv.py 'pm uninstall --user 0 <package>'
```

What went (33 packages):

- **The four app buttons:** `com.netflix.ninja`, `com.google.android.youtube.tv`,
  `com.amazon.amazonvideo.livingroom`, `in.startv.hotstar`.
- **The hidden_apps utilities:** USB media player, meeting mode (`com.apps.biz.mt`), Play Games,
  Google feedback, esticker and esticker3, audiolink, scast, TV manager, `com.mk.tv.meeting.service`,
  time clock, fastscreen.
- **The rest of the disabled list:** MX Player, SonyLIV, `com.graymatrix.did`, Seraphic WebView,
  virtualkey, smartkey, `com.apps.mk.wd`, Wi-Fi hotspot, factory UI (`com.apps.factory.ui`),
  `com.realtek.media.notification`, AdServices, federated compute, on-device personalisation,
  Health Connect (controller + backuprestore), basic dreams, HTML viewer.

Kept — installed and hidden where relevant, or left disabled:

- `com.realtek.tv` — HDMI, source menu and channel handler; removing it kills the inputs.
- `com.apps.ota` — firmware updates.
- `com.apps.bluetoothremote` — remote and Bluetooth pairing, the recovery path if the remote ever
  needs re-pairing.
- The consent and setup plumbing: `com.apps.function.gdpr`, `com.android.tv.feedbackconsent`,
  `com.android.backupconfirm`, `com.android.sharedstoragebackup`, `com.android.wallpaperbackup`,
  `android.autoinstalls.config.scbc.device`.
- `com.netflix.tokenmanager` and `com.google.android.youtube.tvmusic` both answered
  `Failure [not installed for 0]` — stubs with no launchable entry, nothing to remove.

The cold-boot job can only *enable* a package that still exists, so none of the 33 can come back
short of a Play Store install or a factory reset. Verify:

```sh
tools/tv.py 'pm list packages | grep -iE "netflix|youtube|amazonvideo|hotstar|sonyliv|mxtech"'
#   expect no match beyond a stub line; pressing the four remote buttons opens nothing
```

## 3. Home: CleanYourTV owns the Home button, launcherx stays stock Home

**Do not disable `com.google.android.apps.tv.launcherx` or `com.google.android.tungsten.setupwraith`** —
see the device doc. The arrangement is: stock Home stays on `launcherx`, and the launcher catches the
remote's Home button.

```sh
tools/tv.py 'pm enable --user 0 com.google.android.apps.tv.launcherx'
tools/tv.py 'pm enable --user 0 com.google.android.tungsten.setupwraith'
tools/tv.py 'cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity'
```

If the launcher is missing altogether, build and install it (needs JDK 17–22 + Android SDK platform 34):

```sh
TV_HOST=$TV_HOST STOCK_LAUNCHER=com.google.android.apps.tv.launcherx launcher/build-and-install.sh
```

## 4. Launcher config: redirect + hidden tiles

```sh
tools/theme.sh themes/r4g-home
```

Pushes [`themes/r4g-home/config.json`](themes/r4g-home/config.json) and restarts Home. It holds:

- `stock_home_redirect` → `com.google.android.apps.tv.launcherx`, so the stock home screen is sent
  straight back to CleanYourTV whenever the firmware puts it up.
- `hidden_apps` → what stays installed but hidden: Live TV (`com.realtek.tv`), System upgrade
  (`com.apps.ota`) and the GDPR notice — everything else it named (Netflix, YouTube, Prime Video,
  Hotstar, Play Games, esticker, scast, fastscreen, timeclock, …) was **uninstalled for user 0 on
  2026-11-01** (§2c), and its entries remain in the config as a second layer should any of them ever
  be installed again. §7 explains why hiding alone was not enough: the cold boot keeps re-enabling
  whatever still exists on the set.
- `dock_apps` → the dock row in order: **TizenTube, Atlantic, Mrowser, LocalSend**. Anything not
  listed (Settings, Play Store, …) still appears in the grid below the dock; `missing apps are
  skipped`, so a package that is later uninstalled cannot break the dock.
- `home_idle_clock_minutes` → `10`: the clock screensaver after 10 idle minutes on Home (`0` = off).
- `dock_opacity` → `30`: the dock band at 30% opacity, so the wallpaper shows through it. Picking 100/70/50/30
  in Launcher settings still wins, until the next run of this command clears the pick.
- `wifi_status_color` + `wifi_status_warnings` → the Wi-Fi icon tints with the connection: **green** online,
  **orange** while a connection exists but is not usable yet (no internet, still connecting, or Wi-Fi on with
  nothing associated), **red** only while the Wi-Fi radio is off.

### 4b. System screensaver: only CleanYourTV's clock

Android's own screensaver (a "dream") is a separate setting from the launcher's idle clock, and on
this set it was pointing at `io.freemytv/io.freemytv.Screensaver` — **a package that is not
installed** — while the platform default stayed `com.google.android.apps.tv.dreamx/.service.Backdrop`
(Google Ambient). So the stock dream could still take over:

```sh
tools/tv.py 'settings put secure screensaver_components com.gathin.tvhome/.ClockDream
settings put secure screensaver_enabled 1'
```

Verify:

```sh
tools/tv.py 'settings get secure screensaver_components'   # com.gathin.tvhome/.ClockDream
tools/tv.py 'settings get secure screensaver_enabled'       # 1
tools/tv.py 'cmd package query-services --brief -a android.service.dreams.DreamService'
#   expect com.gathin.tvhome/.ClockDream (ours) and com.google.android.apps.tv.dreamx/.service.Backdrop
#   (installed but no longer selected)
```

`dreamx` itself stays **enabled** — it is on the firmware's cold-boot re-enable list, so disabling it
only lasts until a power cycle; not being the selected component is what keeps it from running.
To put the stock screensaver back: `settings put secure screensaver_components
com.google.android.apps.tv.dreamx/.service.Backdrop`.

### 4c. The network menu needs Location to show the network's name

Pressing the Wi-Fi icon opens a menu (name and state, band and link speed, the TV's address, then a row for
Wi-Fi settings). Every row but the name reads without Location; Android redacts the SSID until **both**
location permissions are granted and Location is on, so the name falls back to "Wi-Fi" without this:

```sh
tools/tv.py 'pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION
pm grant com.gathin.tvhome android.permission.ACCESS_COARSE_LOCATION
cmd location set-location-enabled true'
```

Use `cmd location set-location-enabled` — on this set `settings put secure location_mode 3` reads back `3`
and then reverts to `0` (ISSUES.md 19). Prove the Wi-Fi service accepts us:
`tools/tv.py 'logcat -d | grep checkCallersLocationPermission | tail -1'` → `com.gathin.tvhome: ok`.

## 5. Home key service

```sh
tools/tv.py 'settings put secure enabled_accessibility_services ""; settings put secure accessibility_enabled 0; sleep 1
settings put secure enabled_accessibility_services "com.gathin.tvhome/.HomeKeyService"
settings put secure accessibility_enabled 1'
```

Confirm it bound: `tools/tv.py 'dumpsys accessibility | grep -A1 "Bound services"'` must show
`Service[label=Home button …]`.

## 6. Checks before you walk away

```sh
# Home resolves and the Home key lands on CleanYourTV (run the key twice)
tools/tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
tools/tv.py 'input keyevent KEYCODE_HOME'; sleep 5
tools/tv.py 'dumpsys activity activities | grep topResumedActivity | head -1'   # expect com.gathin.tvhome/.MainActivity

# Not rebooting, and the bootloop has not come back
tools/tv.py 'echo -n "up=$(uptime | sed "s/.*up //;s/,.*//") boot=$(getprop sys.boot_completed)"'
tools/tv.py 'logcat -c'; sleep 10; tools/tv.py 'logcat -d | grep -c RecoveryActivity'   # expect 0

# Your apps are still there
for p in io.gh.reisxd.tizentube.cobalt st.atlantic.app net.mrowser; do
  tools/tv.py "pm list packages -e --user 0 | grep -q $p && echo \"$p OK\" || echo \"$p MISSING\""
done
```

## 7. Cold boot

The firmware's `FallbackHome` re-enables its 60-package list only on a **cold** power cycle, never on
standby. After unplugging the TV, re-check step 6 — anything on that list comes back enabled, which is
why `hidden_apps` rather than `pm disable-user` is what keeps those tiles away.

## Known behaviour on this set

| Thing | State | Why |
|---|---|---|
| `com.realtek.tv` (Live TV) | enabled, hidden | owns `SelectInputActivity` + channel handler; disabling kills HDMI |
| `com.apps.ota` (System upgrade) | enabled, hidden | would block firmware updates if disabled |
| Stock screensaver (`dreamx` Backdrop) | installed, **not selected** | selected component is `com.gathin.tvhome/.ClockDream`; `dreamx` is on the cold-boot re-enable list so it cannot be reliably disabled |
| `screensaver_components` | `com.gathin.tvhome/.ClockDream` | was pointing at `io.freemytv`, a package that was never installed |
| `com.apps.biz.mt` (meeting mode) | disabled + hidden | has a `BootReceiver` |
| `launcherx`, `setupwraith` | enabled, always | cold-boot re-enable + RecoveryActivity loop |
| 6 input-guard skips | disabled with `TVINPUT_ALLOW=1` | firmware prints no permission block |
