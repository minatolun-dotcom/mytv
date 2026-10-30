# mytv

Our TV's copy of [CleanYourTV](https://github.com/Gathin23/cleanyourtv) — debloated, with our launcher,
our dock and our screensaver. Everything here has already been run against the set and works.

**TV:** Acer R4G 50" · Google TV 14 · `UKR1.260308` · `TV_HOST=192.168.1.19`

## Install

One line, from a machine on the same Wi-Fi as the TV:

```sh
git clone https://github.com/minatolun-dotcom/mytv.git && cd mytv && python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt && TV_HOST=192.168.1.19 TVINPUT_ALLOW=1 tools/disable.sh examples/acer-r4g-gtv/debloat.txt && TV_HOST=192.168.1.19 tools/theme.sh themes/r4g-home
```

Or hand this to a coding assistant:

> Clone `https://github.com/minatolun-dotcom/mytv.git` and do what `MYTV.md` says for our TV at
> `192.168.1.19`: set up the venv, debloat with `examples/acer-r4g-gtv/debloat.txt` (`TVINPUT_ALLOW=1`),
> push `themes/r4g-home`, enable `HomeKeyService`, then run the checks in step 6. Never disable
> `com.google.android.apps.tv.launcherx` or `com.google.android.tungsten.setupwraith` — that is what
> made Home bootloop last time. If anything looks wrong, start from `ISSUES.md`.

## What's here

| | |
|---|---|
| [MYTV.md](MYTV.md) | The runbook — the whole procedure, step by step, with the checks to run before you walk away. |
| [ISSUES.md](ISSUES.md) | Every problem we hit and its fix: the bootloop, the default-launcher answer that lied, the guard skips. |
| [examples/acer-r4g-gtv/](examples/acer-r4g-gtv/) | The 36-package debloat list plus device notes. |
| [themes/r4g-home/](themes/r4g-home/) | Launcher config: dock, hidden tiles, redirect, clock screensaver. |

## What it does to the TV

- **Removes** YouTube, Netflix, Prime Video, Hotstar, SonyLIV, MX Player, telemetry/consent agents and
  the OEM demo/signage apps — 36 packages disabled, nothing uninstalled.
- **Keeps** TizenTube, Atlantic, Mrowser, VLC, Settings, Play Store, HDMI/inputs and OTA.
- **Dock:** TizenTube · Atlantic · Mrowser · LocalSend.
- **Home:** CleanYourTV, with the clock screensaver after 10 idle minutes. Android's stock screensaver
  is switched to CleanYourTV's clock; Google Ambient stays installed but unselected.

Undo anything with the same file:

```sh
TV_HOST=192.168.1.19 tools/restore.sh examples/acer-r4g-gtv/debloat.txt
```

## Upstream

Tooling, launcher and docs come from [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv)
(MIT) — this fork keeps their full history, adds the files above, and points its README here instead of
theirs. Fixes worth sending upstream are in `ISSUES.md`.
