# Acer R4G 50" — this fork's TV

The set this repository is personalised for. Everything here was applied over network ADB and checked
on the unit itself on 2026-10-30.

| Field | Value |
|---|---|
| Retail model | Acer R4G 50" (`ro.product.model` = `R4G`) |
| Manufacturer / device | MOKA / `R4_GTV` (product `ACER_NFF`) |
| Board | Realtek `rtd2851m` |
| Android | Google TV 14 (SDK 34) |
| Build | `UKR1.260308`, incremental `5597` |
| Fingerprint | `ACER/ACER_NFF/R4_GTV:14/UKR1.260308/5597:user/release-keys` |
| ADB | `TV_HOST=192.168.1.19`, port 5555, `ro.adb.secure=1` |

Same platform and build as documented in [docs/devices/acer-r4g.md](../../docs/devices/acer-r4g.md) —
read that first: the firmware re-enables ~60 packages at every **cold** boot.

## What is installed on it

| App | Package | Kept? |
|---|---|---|
| TizenTube | `io.gh.reisxd.tizentube.cobalt` | yes |
| Atlantic | `st.atlantic.app` | yes |
| Mrowser | `net.mrowser` | yes |
| CleanYourTV launcher | `com.gathin.tvhome` | yes (Home) |
| Settings, Play Store, Play Services, Gboard, Assistant, Chromecast, HbbTV | | yes |
| Realtek Live TV / HDMI / tuner stack, key handlers, OTA | | yes |
| YouTube, Netflix, Prime Video, Hotstar, SonyLIV, MX Player | | disabled |
| Telemetry, ad/consent agents, OEM demo & signage apps | | disabled |

## Files

- [`debloat.txt`](debloat.txt) — 36 packages, applied with `tools/disable.sh`. Restore with
  `tools/restore.sh` and the same file.
- [`../../themes/r4g-home/config.json`](../../themes/r4g-home/config.json) — the launcher config
  overlay: `stock_home_redirect` plus `hidden_apps`.

## Apply / undo

```sh
TV_HOST=192.168.1.19 TVINPUT_ALLOW=1 tools/disable.sh  examples/acer-r4g-gtv/debloat.txt   # debloat
TV_HOST=192.168.1.19                 tools/restore.sh   examples/acer-r4g-gtv/debloat.txt   # undo
TV_HOST=192.168.1.19                 tools/theme.sh     themes/r4g-home                     # launcher config
```

The one-shot runbook for this TV — debloat, Home handover, key service, checks — is
[MYTV.md](../../MYTV.md) at the repository root, and **every problem we hit with its fix** is in
[ISSUES.md](../../ISSUES.md) — the bootloop, the default-launcher answer that disagreed with the
resolver, the guard skips and the recovery steps for a black screen after a power cut.

## Notes from this set

- **Never disable `com.google.android.apps.tv.launcherx` or `com.google.android.tungsten.setupwraith`.**
  They are on the firmware's cold-boot re-enable list; with `launcherx` disabled, `setupwraith`'s
  `.RecoveryActivity` takes Home and crash-loops `system_server` — a black screen that looks exactly
  like a bootloop, and it is what this TV was showing before the fix.
- Home stays on `launcherx`; CleanYourTV takes the remote's Home button through `HomeKeyService` and
  `stock_home_redirect`, which is the arrangement [the device doc](../../docs/devices/acer-r4g.md)
  prescribes for this platform.
- `com.realtek.tv` stays **enabled** (source menu, channel handler) and is only hidden from Home.
- `com.apps.ota` (the "System upgrade" tile) stays **enabled** and is hidden too — the user never
  updates, but disabling it would block firmware updates.
- Two runs used `TVINPUT_ALLOW=1`, justified by the `pm query-services` check above.
- Verified after the run: HOME key opens CleanYourTV (3/3), uptime climbed without a reboot,
  `sys.boot_completed=1`, zero `RecoveryActivity` starts in a cleared logcat, no crash-buffer entries.
