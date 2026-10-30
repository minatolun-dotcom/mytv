# Acer R4G 50" (MOKA `R4_GTV`, Google TV 14): the firmware turns packages back on at every boot

Owner report from #1106 by [@madhusoodhans](https://github.com/madhusoodhans) (diagnosis also in #1135), India. Four days of use without a reboot, then a power cut. This is a note,
not a list: on this firmware a disable-only list can't keep the stock Home away, so there is no
`examples/` folder for it.

| Field | Observed value |
|---|---|
| Retail model | Acer R4G 50" |
| Manufacturer / device | MOKA / `R4_GTV` |
| Board | Realtek `rtd2851m`, 2 GB RAM, 32-bit ARM |
| Android | Google TV 14 |
| Build | `UKR1.260308`, security patch `2026-04-05` |
| Fingerprint | `ACER/ACER_NFF/R4_GTV:14/UKR1.260308/5597:user/release-keys` |

The Panasonic 4KTV-3MUP in #1116 is the same MOKA `R4_GTV` platform on the same `UKR1.260308` build
and shows the same behaviour.

## What happens

The owner disabled 31 packages in small batches, then disabled `com.google.android.apps.tv.launcherx`
and `com.google.android.tungsten.setupwraith` so a new launcher could keep Home. It worked until the
first cold boot. After that Home was black while volume, HDMI and the app keys still worked, and
logcat showed setupwraith's `.RecoveryActivity` being started as HOME every ~130 ms:

```
I/ActivityTaskManager: START u0 {act=android.intent.action.MAIN cat=[android.intent.category.HOME] ... cmp=com.google.android.tungsten.setupwraith/.RecoveryActivity (has extras)}
```

## Why

Acer/Realtek's `com.android.tv.settings` is modified. On every boot its `.system.FallbackHome` runs
`SetPmodServiceJob`, which calls `disableOrEnableApk(1)` and re-enables every package on a hard-coded
list of 60, unless a factory `P_MODE` flag is set (that needs root). setupwraith is on the list. Once
it is back, its `.RecoveryActivity` (HOME priority 1) outranks any third-party launcher (priority 0)
and loops trying to bring back the disabled launcherx. Standby and wake do not run the job; only a
cold boot does.

What the shell cannot do here: `pm hide` needs `MANAGE_USERS`, and disabling only `.RecoveryActivity`
is refused (`Shell cannot change component state`).

## What that means for a debloat

- **Keep `launcherx` and `setupwraith` enabled on this TV.** A new launcher can still take the Home
  button with `HOME_KEY_SERVICE=1` and `stock_home_redirect=com.google.android.apps.tv.launcherx`,
  which is how the Panasonic in #1116 runs it (that issue tracks a cold-boot fix for the redirect).
- These 20 are on the firmware's list, so disabling them only lasts until the next cold boot:

  ```
  com.google.android.tungsten.setupwraith
  com.google.android.katniss
  com.google.android.apps.tv.dreamx
  com.google.android.videos
  com.google.android.play.games
  com.google.android.feedback
  com.apps.ota
  com.apps.authota
  com.realsil.ota
  com.google.android.tv.dfuservice
  com.apps.biz.mt
  com.mk.tv.meeting.service
  com.apps.function.audiolink
  com.mk.tv.timeclock
  com.apps.tvmanager
  com.apps.esticker
  com.apps.esticker3
  com.apps.function.gdpr
  com.apps.scast
  com.creative.fastscreen.tv
  ```

- The job's list is 60 packages and only the 20 above were named when this note was written. The cold
  boot of **2026-10-30** (uptime reset, 17 of our 36 debloat entries back) also re-enabled these five,
  so they are on it too — **Netflix and YouTube** first among them, which is why they reappeared on Home:

  ```
  com.netflix.ninja
  com.google.android.youtube.tv
  com.amazon.amazonvideo.livingroom
  com.google.android.partnersetup
  com.realtek.tv
  ```

  What hides them afterwards is `hidden_apps`, not the disable: those five were only ever disabled, and
  a re-enabled package with no hidden tile is simply back on Home. Netflix and YouTube are in
  `hidden_apps` now, so they stay off it whatever the job does.

- These 11 stayed disabled across cold boots (`launcherx` among them, which is what made Home black
  once setupwraith came back):

  ```
  android.autoinstalls.config.scbc.device
  com.alphainventor.filemanager
  com.android.tv.feedbackconsent
  com.apps.mk.wd
  com.apps.wifihotspot
  com.google.android.apps.tv.launcherx
  com.jio.media.stb.ondemand
  com.mk.tv.systemserver
  com.mxtech.videoplayer.television
  com.realtek.leanbacklauncher.partnercustomizer
  com.yupptv.scope.tvapp
  ```

The owner went further than this project's disable-only rule to keep their own launcher as Home.
Those steps are not documented here.

## If Home is already black after a reboot

Check that it is this: `tools/tv.py 'logcat -d | grep RecoveryActivity'`. Then re-enable the stock
launcher right away and do not reboot while `.RecoveryActivity` has the screen (a reboot there
factory-reset a Xiaomi MiTV-MOSR4, see [TROUBLESHOOTING](../TROUBLESHOOTING.md)):

```sh
tools/tv.py 'pm enable --user 0 com.google.android.apps.tv.launcherx'
```

## What worked on a second R4G (this fork, 2026-10-30)

A second unit (same build, incremental 5597) reached this note with a black screen and a bootloop: Home was
bouncing between `setupwraith/.RecoveryActivity` and a third-party launcher, `launcherx`'s `HomeActivity`
had been component-disabled, and `cmd shortcut get-default-launcher` still named the third-party launcher
while `cmd package resolve-activity` answered `launcherx` (its filter has `priority=2`). Re-enabling
`launcherx` alone was not enough, and disabling it reproduced the loop above.

What settled it, in this order:

1. **Disable `setupwraith` first, then start the new launcher, then disable `launcherx`, then set the home
   preference last** — the order `launcher/build-and-install.sh` uses. Android drops the preference whenever
   the set of HOME apps changes, so setting it before the disables is what left Home on the wrong package.
2. **Put `launcherx` and `setupwraith` back enabled** and leave Home on `launcherx`, exactly as the section
   above prescribes — the state that survives a cold boot.
3. **`HomeKeyService` + `stock_home_redirect`** (`themes/r4g-home/config.json`, pushed with `tools/theme.sh`)
   so the remote's Home button opens the new launcher while `launcherx` stays stock Home. The redirect fires
   from `TYPE_WINDOW_STATE_CHANGED`, so it only arms once `loadConfig()` has read `config.json`: before that,
   a Home press lands on `launcherx` and looks like a failure.
4. **`hidden_apps` for what comes back.** The boot re-enable list means a disable-only list loses ground at
   every cold power cycle; the tiles stay gone because they are hidden rather than disabled.

Verified after the run: HOME key opens the new launcher (3/3), uptime climbing across a 60 s watch with
`sys.boot_completed=1`, zero `RecoveryActivity` starts in a cleared `logcat`, empty crash buffer.

The full runbook for this set is [`MYTV.md`](../../MYTV.md) at the repository root, the debloat list is
[`examples/acer-r4g-gtv/`](../../examples/acer-r4g-gtv/), and **every symptom, cause and fix from that
session** — including the misleading `get-default-launcher` answer and the recovery steps for a black
screen — is logged in [`ISSUES.md`](../../ISSUES.md).
