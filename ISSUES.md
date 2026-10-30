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
