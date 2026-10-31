# r4g-home — this TV's working config

The full launcher config our Acer R4G runs. `config.json` here is **byte-identical to the file on the
TV**, so pushing this folder restores the exact look, and editing it is how the TV gets changed.

![r4g-home on the TV](screenshot.jpg)

## Apply / undo

```sh
TV_HOST=192.168.1.19 tools/theme.sh themes/r4g-home    # push it and restart Home
TV_HOST=192.168.1.19 tools/theme.sh --reset            # back to the launcher's built-in config.xml
```

`tools/theme.sh` pushes `config.json` **and `wallpaper.jpg`** — the picture the TV shows behind Home.
The app-coloured glow is off (`ambient_glow: false`): the wallpaper is the whole background, with no
colour wash following the focused app. `screenshot.jpg` is here so you can see the layout first, the
way every folder in [`themes/`](../) is.

## Every key in it

| Key | Value | Why |
|---|---|---|
| `dock_apps` | TizenTube, Atlantic, Mrowser, LocalSend | The four apps that belong on Home; Settings and Play Store stay in the grid below. |
| `icon_only_buttons` | `true` | Wi-Fi and Settings as round icon buttons; Inputs keeps its label. |
| `icon_only_inputs` | `true` | Inputs round too — it has its own key, `icon_only_buttons` does not cover it. |
| `ambient_glow` | `false` | No app-coloured radial glow behind the dock: with a wallpaper of its own, the focused app's colour wash was noise on top of it. false hides the glow view entirely; the dock band and the tile highlight are unaffected. |
| `wallpaper_true_color` | `true` | Decode the wallpaper in 32-bit colour instead of RGB_565. Ours is a dark deep-space gradient — exactly the kind of image the built-in config warns "bands" in 565 — and this TV has the 2 GB the key is meant for. |
| `reorder_button` | `true` | A reorder button (two arrows) in the top bar, between Wi-Fi and Settings: press it to pick tiles up and move them with the D-pad (Back saves and leaves). Reordering is also in the Settings button's long-press menu; this just gives it a visible way in. |
| `wifi_label_width` | `0` | Icon only, no network name. **Pinned deliberately:** `Config` reads this from the installed APK's resources *before* overlaying `config.json`, and that build's default is non-zero, so the Wi-Fi pill kept its label until the value was written here. |
| `wifi_status_color` | `true` | The Wi-Fi icon tints with the connection instead of always white: green while online, white while it is not (needs a rebuild, see below). |
| `wifi_status_warnings` | `true` | Refines the tint: orange while a connection exists but is not usable yet — no internet, still connecting, or Wi-Fi on with nothing associated — and red only while the Wi-Fi radio is off, so a reconnect does not flash red. |
| `settings_key_panel` | `true` | Settings opens the small glass panel (TV settings, Display, Network, Apps, Launcher settings) instead of the full TV Settings. The remote's Settings key already reads this; the on-screen gear needs the rebuilt APK below. |
| `single_row` | `true` | Every app in one horizontal dock row: four tiles on screen at once (`columns` = 4), Right/Left scroll the row sideways to the rest. No grid below; Up goes to the top bar. |
| `dock_band_all` | `true` | With `single_row`, the dock band runs behind **every** app in the row instead of only the four in `dock_apps`. |
| `dock_glass` | `true` | The dock band as light frosted glass (iOS-"liquid glass"-ish: white gradient fading downwards, bright edge) so the wallpaper shows through it. Supersedes `dock_opacity` while on — the light glass has no dark colour to scale. |
| `dock_blur` | `24` | Real backdrop blur behind the dock band: a blurred copy of the wallpaper clipped to the band's rounded rectangle (RenderEffect, Android 12+). The same blur and the band's glass also frost the top-right pills (inputs, network, reorder, settings), every glass popup's background and the settings panel the remote's Settings key opens, each blurred against its own place on screen; the pills keep their translucent circles and the popups their rows on top. One setting, 0 turns it all off. |
| `dock_opacity` | `30` | The dock band at 30% opacity, so the wallpaper shows through it. Only applies while `dock_glass` is off. A value picked in Launcher settings still wins until the next `tools/theme.sh` push, which clears the pick (100, 70, 50 or 30 are its options). |
| `stock_home_redirect` | `com.google.android.apps.tv.launcherx` | Stock Home stays on `launcherx` — this firmware re-enables it every cold boot — and sends its home screen straight back to this launcher. |
| `home_idle_clock_minutes` | `10` | Clock screensaver after 10 idle minutes on Home (`0` = off). |
| `hidden_apps` | 18 packages | Live TV, USB media player, meeting mode, System upgrade, Netflix and YouTube, and everything the firmware re-enables at a **cold** boot (Prime Video, Play Games, esticker, gdpr, scast, fastscreen, timeclock, …). Hiding is what survives a reboot; `pm disable-user` alone does not — Netflix and YouTube were only disabled, so the last power cut brought their tiles back. |

## Not in this file

Two things that make the setup work live outside `config.json` — they are in Settings and in the
accessibility service, so a theme push cannot set them. Both are in [`MYTV.md`](../../MYTV.md):

- **System screensaver** → `settings_key_panel` is not it; Android's dream is `screensaver_components`
  (step 4b). Ours points at `com.gathin.tvhome/.ClockDream`, not Google's Backdrop.
- **Home key service** → `HomeKeyService` must be bound (step 5), or the remote's Home button opens
  the stock launcher instead of this one.

## The round gear and the glass panel

`settings_key_panel` was honoured by the remote's Settings key but **not** by the on-screen gear: the
gear's `setOnClickListener` called the full TV Settings unconditionally. `MainActivity` now checks the
flag first, which needs a rebuild — `tools/docker-build.sh launcher assembleRelease`, no local JDK.
See [`ISSUES.md`](../../ISSUES.md) for the install-signature caveat that goes with replacing the APK.
