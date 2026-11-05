# Issues we hit on our TV, and the fix for each

Every problem that came up while debloating and fixing the **Acer R4G 50"** (Google TV 14,
`UKR1.260308`), what it looked like, why it happened and exactly what fixed it. Next time, start
from the symptom column. The procedure itself is in [MYTV.md](MYTV.md).

| # | Symptom | Cause | Fix |
|---|---|---|---|
| 1 | TV unreachable: `No route to host`, ping dead | Wi-Fi drops during every reboot of the loop | Poll `adb connect` in a loop, `disconnect` first each try |
| 2 | Suspected "Google setup not complete" | Wrong: `user_setup_complete` and `device_provisioned` were already `1` | Check the flags before believing the theory |
| 3 | `get-default-launcher` says our launcher, HOME still opens Google's | Two different sources of truth | Trust `cmd package resolve-activity`, not `get-default-launcher` |
| 4 | `set-home-activity` answers `Success` but changes nothing | Android drops the preference whenever the set of HOME apps changes | Set the preference **after** the disables, never before |
| 5 | `SecurityException: Shell cannot change component state` | Shell may only toggle whole packages here | Disable the package, or hide the tile instead |
| 6 | Disabling `launcherx` rebooted the TV into a black screen | `setupwraith/.RecoveryActivity` takes Home and crash-loops `system_server` | Re-enable `launcherx`; disable `setupwraith` **first**, then start the new launcher, then disable stock, then set Home |
| 7 | `am start -c HOME` says "delivered to currently running top-most instance" | It reused the stock home that was already running; it does not prove the default | Read `topResumedActivity` after, or start the explicit component |
| 8 | Home key landed on Google's home right after enabling `HomeKeyService` | `stockHome` is only filled in `loadConfig()` at `onServiceConnected` | Wait for the service to load config; prove it via `dumpsys accessibility` |
| 9 | `adb: device offline` / `error: closed` mid-command | Connection died between commands | Treat empty output as *unknown*, never as success; reconnect and re-read |
| 10 | `grep: bad regex ... trailing backslash` from the TV | TV has busybox grep — no `\s` | Do all text processing locally, keep TV commands basic |
| 11 | `disable.sh` skipped 6 harmless packages | Firmware prints no permission block, so the input guard fails closed | Prove it with `pm query-services`, then run with `TVINPUT_ALLOW=1` |
| 12 | `disable.sh` refused to disable Live TV | It owns HDMI/source switching | Correct — hide it with `hidden_apps`, never disable it |
| 13 | Tiles still visible after disabling the apps | Cold-boot re-enable list, and `query-activities` ignores `hidden_apps` | Disable **and** hide; hiding is what survives a reboot |
| 14 | Everything reverts after unplugging the TV | Firmware's `FallbackHome` re-enables 60 packages on a **cold** boot | Use `hidden_apps` for durability, not `pm disable-user` alone |
| 15 | `git push` → `could not read Username for 'https://github.com'` | `credential.helper store` reads `~/.git-credentials`, not the repo's | `git config credential.helper "store --file=<repo>/.git-credentials"` |
| 16 | `git checkout -b main FETCH_HEAD` landed on an old commit | Bare `FETCH_HEAD` picked a non-HEAD ref after a multi-ref fetch | `git checkout -B main upstream/main` |
| 17 | `pm install -r` → `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed APK was signed with a different debug key than this Docker build | Rebuild with the host's key (`HOST_KEY_DIR=…`) if it matches, or uninstall and re-push the config |
| 18 | `settings_key_panel: true` changed nothing on the gear | Only `HomeKeyService` and `handleSettingsKey` read it; the on-screen gear's click listener called full Settings unconditionally | One-line change in `MainActivity`, which needs a rebuild — a config push can never do it |
| 19 | The network menu's first row (and the pill) says "Wi-Fi" instead of the network's name | Android redacts the SSID until **both** location permissions are granted **and** Location is on; this set had neither, and `settings put secure location_mode 3` read back `3` then reverted to `0` | `pm grant` `ACCESS_FINE_LOCATION` **and** `ACCESS_COARSE_LOCATION`, then `cmd location set-location-enabled true` (the `cmd` write sticks). Wi-Fi verbose logging shows the gate: `checkCallersLocationPermission(com.gathin.tvhome): ok` |
| 20 | Home fell back to the stock launcher right after replacing the APK | `am force-stop com.gathin.tvhome` dropped `HomeKeyService` from `enabled_accessibility_services`, so nothing catches the Home key any more | Re-set it as [MYTV.md](MYTV.md) §5 does. `tools/theme.sh` already does this for you around its restart (`tools/theme_restart.py` saves and restores the service around the force-stop) |
| 21 | Netflix and YouTube were back on Home after the TV was unplugged | Two layers. The firmware's cold-boot job (`com.android.tv.settings/.system.FallbackHome` → `SetPmodServiceJob` → `disableOrEnableApk(1)`) re-enables a hard-coded 60-package list, and they were **disabled but never hidden**, so nothing covered the re-enable — 17 of our 36 entries came back on 2026-10-30, five of them not in the device doc's named 20 (`com.netflix.ninja`, `com.google.android.youtube.tv`, `com.amazon.amazonvideo.livingroom`, `com.google.android.partnersetup`, `com.realtek.tv`) | Add them to `hidden_apps` in `themes/r4g-home/config.json` and push the theme: hiding survives every cold boot, `pm disable-user` does not. Re-run `disable.sh` after a power cut to have them disabled (not just hidden) again for the session. Stopping the job needs the factory `P_MODE` flag, i.e. root |
| 22 | After pushing the glass build the stock launcher kept the screen and our Home never came up; `logcat` filled with `NoSuchMethodException: <init> []` from `DrawableInflater` | Android 14 inflates a drawable named in XML through a **public no-argument constructor**; `BackdropGlassDrawable` only had `(density, radius, …)`, so every screen inflating `pill_glass.xml` crashed the launcher in a loop | Give the drawable a public no-arg constructor (the XML path) and keep the parametered one for code, then reinstall. The reinstall also drops `HomeKeyService` — re-set it as [MYTV.md](MYTV.md) §5 does (issue 20) |
| 23 | `launcher/build-and-install.sh` ends with `ERROR: Home resolves to com.google.android.tungsten.setupwraith …` and `launcherx owns Home again` | Expected **on this set**: `launcherx` and `setupwraith` stay enabled (cold-boot re-enable; the bootloop section below), and the preferred HOME activity is dropped whenever the set of HOME apps changes, so the resolver answers the stock launcher | Ignore the script's ERROR here and verify with [MYTV.md](MYTV.md) §6 instead: HOME key → our launcher, `topResumedActivity` = `com.gathin.tvhome/.MainActivity`. `stock_home_redirect` + `HomeKeyService` is what opens ours |
| 24 | The tiles for Netflix, Prime Video, Hotstar and YouTube were gone from Home, but the remote's app buttons still opened them from any screen | `hidden_apps` only hides tiles — the packages stayed installed, so any direct launch path still worked, and the firmware's cold-boot job keeps re-enabling what exists (#14, #21) | On our set: `pm uninstall --user 0` for every bloat package that was only disabled or hidden — 33 packages on 2026-11-01 (the four streaming apps, the hidden_apps utilities, the rest of the disabled list). The job can only enable a package that still exists, so nothing brings them back. Kept: `com.realtek.tv` (HDMI), `com.apps.ota` (firmware), `com.apps.bluetoothremote` (pairing), consent and backup components — the full list is [MYTV.md](MYTV.md) §2c |
| 25 | The Play Store came back after a reboot even though `pm disable-user --user 0 com.android.vending` answered `Success` | The firmware's cold-boot `pm enable` pass (#14, #21) re-enables every package that still exists on its list, and `vending` evidently gets the same treatment — a disable on this set lasts only until the next power cycle | Remove it: `pm uninstall --user 0 com.android.vending`. Checked across two cold boots on 2026-11-02 — `pm path com.android.vending` stayed empty, because the job can only `pm enable` a package that still exists (the same reason the 33 in #24 stay gone). Play Services stays enabled, and with LocalSend and VLC already on F-Droid builds nothing on the set is Play-managed. Record: [MYTV.md](MYTV.md) §2d |
| 26 | `tools/venv/bin/python -m unittest discover -s tests` was **red on a clean checkout** — 31 failures and 13 errors, none of them caused by an edit | Upstream's suite asserts upstream's pages. On 2026-10-30 this fork replaced the root `README.md` with its own 54-line page (`35ace4e3`), so the Tools table, the brand-grid markers, the translation links, the press card, the quick start's clone step and the privacy, Tizen, Fire TV and Home-protection sections the tests read were all gone; `gen_tables.py --check` stopped on the missing `<!-- tv-brands -->` markers; `themes/` held 13 folders (`r4g-home` is ours) while the German and Dutch rows said 12; `examples/acer-r4g-gtv/` had no `tv.json`; and five pages gave the set's real LAN address | Fixed 2026-11-03. The page is now our fork block on top — [MYTV.md](MYTV.md), [ISSUES.md](ISSUES.md), `apps.txt`, our one command, what our set runs — followed by upstream's sections, so every table and heading the tests read is back; the `tv.json` row is added, the real address is `<tv ip>` everywhere, the count reads 13 on all three front pages, `install_apps.py` has its Tools row, and `gen_tables.py` regenerated the brand grid, the table row and `tools/readme_stats.py`. **2251 tests, OK (41 skipped)**, and `gen_tables.py --check` exits 0. `gen_tables.py` still prints "a count changed": redraw `docs/stats.png` and `docs/card.jpg` when convenient |
| 27 | The dock band, the four top-right circles and their popups showed no blur and no transparency over red-rain's wallpaper, while the same build frosted normally over a bright picture | The glass was drawing correctly — `logcat` (`TvHome.DockBlur`) showed a valid bitmap and the band's rectangle — but the wallpaper was near-pure black exactly where the glass sits: the band's rows measured **0.0011** mean luminance, the button area **0.0001**. Blurring black returns black, so the shapes had nothing to smear and read as flat grey slabs — and the bottom third of that file carries almost no rain at all, so merely raising its brightness gave an evenly grey wash, which blurs to itself | Give the picture something to transmit: `gamma 0.55` then `add 4%`, so the rain text is far brighter than the black around it, plus a soft red glow behind the dock and a faint violet one behind the top bar, composited into the picture (the band's rows went 0.0011 → 0.09, the circles to 0.11 — later replaced by a picture with big bright detail behind the band, 0.44: [MYTV.md](MYTV.md) §8). Then give the glass its own material so the shapes read over a dark backdrop too: `BackdropGlassDrawable` and `DockDecoration` draw the white body (28% → 12%), a glint off the top-left corner, a dark inner shadow under the top rim, a bright rim that dims downwards, and 3.5% refraction. To tell "no blur" from "nothing to blur", push a green-over-red test picture and sample a column in a gap between band tiles: outside the band the colour flips in 2 rows, inside it ramps over about 60. Detail: [MYTV.md](MYTV.md) §8 |
| 28 | The CI size gate (APK under 589,824 B) fails on `main`, not only after an edit | **Known state, pre-existing:** the unmodified tree builds 594,273 B — 4,449 B over — because this fork's two features (the idle clock, the glass) sit on top of upstream's budget. The liquid glass added 536 B on top of that (594,809 B), and the light-wallpaper text (596,757 B). The gate is a replied-to issue for the maintainer, never a PR edit | Nothing to change here. Building with no JDK on the host goes through `tools/docker-build.sh launcher assembleRelease`, then `SKIP_BUILD=1 TV_HOST=<tv ip> launcher/build-and-install.sh`; check the size with `stat -c %s launcher/app/build/outputs/apk/release/app-release.apk` |

| 29 | After a theme push, Home fell back to Google TV's stock launcher and `settings get secure enabled_accessibility_services` was empty, with `accessibility_enabled` **0** | `theme_restart.py` only puts back the helpers it found on the list, and a force-stop (the launcher's install, or an earlier push) had already taken `HomeKeyService` off it; Android clears `accessibility_enabled` with the last service, so the flag says nothing about what the person chose | 2026-11-04: the restart now adds `HomeKeyService` back itself when the theme declares `stock_home_redirect` (`theme.sh`/`theme.ps1` pass `CYT_THEME_HOME_KEY=1`), this launcher is not the resolved HOME, and the package is installed — then it verifies the binding. Checked on the set by clearing both settings and pushing: the list, the flag and `Service[label=Home button]` all came back, exit 0 |
| 30 | `auto_ink` has no row in `docs/switches.md`, so the Switches page does not show it | The feature was finished while the set was off the network (`adb connect <tv ip>:5555` → `No route to host`), and that page's rule wants a screenshot from the real TV, not a mock-up | Take one with the picture up — `tools/tv.py --screenshot shots/auto_ink.png`, then a 1280×720 JPEG under 300 KB at `docs/switches/auto_ink.jpg`, add the row, and take `auto_ink` out of `SCREENSHOT_WANTED` in `tests/test_switches.py` (the suite fails while a key sits in both places) |
---

## The one that matters: the bootloop

**What it looked like:** TV stuck rebooting; in the brief window it was up, Home bounced between
`setupwraith/.RecoveryActivity` and a third-party launcher, roughly every 130 ms in `logcat`.

**What was actually wrong** — three things stacked:

1. `com.google.android.apps.tv.launcherx`'s `HomeActivity` was **component-disabled**, so Google's
   launcher no longer answered HOME, while `setupwraith/.RecoveryActivity` (HOME priority 1) did.
2. The default-home record pointed at our launcher, but **the resolver disagreed**:
   `cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME`
   answered `com.google.android.apps.tv.launcherx/.home.HomeActivity` (`priority=2`). The preferred
   activity is dropped every time the set of HOME apps changes.
3. Google setup flags were already complete — **the setup theory was wrong.**

**What fixed it**, in this exact order (this is the order `launcher/build-and-install.sh` uses, and
the reason it warns about it):

```sh
tools/tv.py 'pm disable-user --user 0 com.google.android.tungsten.setupwraith'    # 1. recovery out first
tools/tv.py 'am start -W -a android.intent.action.MAIN -c android.intent.category.HOME \
             -n com.gathin.tvhome/.MainActivity'                                  # 2. new launcher up (Status: ok)
tools/tv.py 'pm disable-user --user 0 com.google.android.apps.tv.launcherx'       # 3. then stock down
tools/tv.py 'cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity'  # 4. preference LAST
```

**Then immediately undo steps 1 and 3** on this firmware — see issue 14. The state that actually
survives is:

- `launcherx` and `setupwraith` **enabled**, Home left on `launcherx`
- `HomeKeyService` bound + `stock_home_redirect=com.google.android.apps.tv.launcherx`, so the
  remote's Home button opens our launcher anyway
- `hidden_apps` for everything the firmware turns back on

**Proof it worked:** HOME key → our launcher 3/3, uptime climbing across a 60 s watch,
`sys.boot_completed=1`, `logcat -c` then `grep -c RecoveryActivity` → **0**, crash buffer empty.

### If it goes black again after a power cut

```sh
tools/tv.py 'pm enable --user 0 com.google.android.apps.tv.launcherx'   # first, before anything else
tools/tv.py 'logcat -d | grep RecoveryActivity'                          # confirms it is this loop
```

Do **not** reboot while `.RecoveryActivity` owns the screen — on a Xiaomi MiTV-MOSR4 that reboot
factory-reset the set.

---

## Reading the TV without being fooled

- **`cmd shortcut get-default-launcher`** — echoes the stored preference. Can say one thing while the
  resolver does another. Use it as a hint only.
- **`cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`**
  — what actually opens when Home is pressed. This is the one to believe.
- **`dumpsys activity activities | grep topResumedActivity`** — what is actually on screen right now.
- **`dumpsys accessibility | grep -A1 "Bound services"`** — `eventTypes=TYPE_WINDOW_STATE_CHANGED`
  here is also proof that `config.json` was read by `HomeKeyService`.
- **Empty command output = unknown state**, not success. The TV dropping offline mid-batch looks
  exactly like a command that did nothing.

## Running the tooling

- `tools/tv.py` speaks ADB over TCP itself; the `adb` binary is only needed for the polling loop.
- On the TV, `grep` is busybox: no `\s`, no `\S`. Keep TV-side scripts POSIX and filter locally.
- `tools/disable.sh` fails **closed** by design — its skips are it protecting the TV. Read the
  `Skipping …` lines before assuming a run was complete, and confirm with
  `pm list packages -d --user 0`.
