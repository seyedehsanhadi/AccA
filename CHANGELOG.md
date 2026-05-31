# Changelog

Notable changes to this fork. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); version numbers match the app's own versionName.

## [1.0.44] - 2026-05-31

Charging could pulse on and off near the limit instead of holding it. With the limit at 75% and the battery at 77%, charging ran for ~20 seconds, stopped, then started again a little later — over and over — while the level just sat above the limit. It looked like ACC was ignoring the config.

### Fixed
- ACC no longer turns charging back **on** while it is trying to pause above the limit. The charging-switch picker added in the previous ACC build, when it hit a switch the charger firmware keeps re-arming, briefly re-enabled charging on every pause cycle and then re-tested the switch on the next loop — that was the on/off pulsing. It now keeps charging off while probing, and picks a switch once per session instead of re-probing every loop, so the configured limit holds steady.
- The limit is now enforced fail-safe: if the pause or resume level is ever missing or unreadable, the daemon treats it as "pause now / do not resume" rather than letting the battery charge past the limit.

### Changed
- Bundled ACC daemon updated to v2025.5.18-dev-fix4 (the charge-pulsing fix above).
- Version is now 1.0.44 (build 48).

## [1.0.43] - 2026-05-31

The switch test (acc -t) could leave charging uncontrolled. ACC stops its charge-control daemon while it tests switches, so during a test the configured stop level is not enforced, and if the test was killed (force-closing the app) the daemon could stay down, so the battery kept charging past the limit. The test also ran for minutes on one shared root shell, so every other command (daemon status, version, the diagnostics screen) hung until the app was force-closed.

### Fixed
- The daemon is now guaranteed to be running again after any switch test. After a test the app waits past ACC's own restart window and, if the daemon is still down, restarts it, so the configured stop level is always enforced and charging never stays uncontrolled.
- The switch test can no longer hang the app. It now runs under a hard time limit, so it can never hold the root shell open indefinitely and block daemon status, version, or the diagnostics screen.
- Running the "Test charging switches" script from the Scripts tab is bounded the same way and restores the daemon afterwards; every other script runs exactly as before.

### Changed
- Version is now 1.0.43 (build 47).

## [1.0.42] - 2026-05-31

### Added
- Charge-activity capture in Diagnostics (menu: Capture charging). It samples the battery once a second for 20 seconds and records level, status, current and voltage over time, so a brief charge flicker that a single snapshot would miss actually shows up. It names the active charging switch, says whether that switch is a clean on/off one or a level one, and prints a verdict that calls out a switch fight when charging keeps flipping on and off near the stop level. The capture is started by hand and runs for a fixed 20 seconds, so it adds no background drain, and it only reads, never changes charging.

### Changed
- Version is now 1.0.42 (build 46).

## [1.0.41] - 2026-05-31

### Changed
- Reworked the Logs screen into a Diagnostics report. It used to stream ACC's raw shell execution trace one line at a time, and you could only copy a single line at a time. Now it gathers one readable report: app, device, Android, kernel, ACC version, daemon status, battery, the active config, detected charging switches, and the tail of the daemon log. The text is selectable with Copy, Share, and Refresh buttons, and the full log bundle (dmesg, logcat, config, switch maps) is still one tap away under the menu for deep bug reports.
- The report is gathered once in the background instead of polling continuously, so the screen no longer keeps the CPU busy.
- Version is now 1.0.41 (build 45).

## [1.0.40] - 2026-05-31

### Fixed
- The "Automatically cycle through switches" state could read wrong. The check for a manual switch looked at the whole config, so an apply_on_boot or apply_on_plug command ending in " --" flipped the flag off by mistake. It now reads the charging-switch line only.
- Battery idle support is detected more loosely, so a reworded ACC output line no longer quietly disables the prioritize-idle option.

### Changed
- Version is now 1.0.40 (build 44).

## [1.0.39] - 2026-05-31

### Fixed
- Battery health no longer reads "Unknown" on ACC 2025.x. ACC stopped printing a health field, so the app now reads it from the kernel directly and shows Good, Overheat, Cold and the rest again. On a phone without that kernel node, health stays Unknown and everything else keeps working.

### Changed
- Version is now 1.0.39 (build 43).

## [1.0.38] - 2026-05-31

A round of cleanup after reading the whole app against the ACC engine it drives. The headline: AccA no longer asks for access to your photos and media, and the config editor stops showing empty quotes and wrong toggle states.

### Removed
- The "Allow AccA to access photos, videos, music, and audio" prompt is gone. The app never read media; the permission was left over from an old storage approach. Profile export and sharing already work through the share sheet and a private file provider, so nothing needs it.

### Fixed
- The accd quick-settings tile now refreshes. It was waiting on a storage permission the app doesn't even declare, so the check always failed and the tile sat stale.
- Charging switch, apply_on_boot and apply_on_plug no longer show a literal `""`. ACC wraps these values in quotes; the app now unwraps them, so an unset switch reads "Automatic" again.
- The Apply on Boot / Apply on Plug toggles reflect whether a command is actually set instead of always showing on, and the plug row is labelled `apply_on_plug` to match ACC's real key.
- Reading the ACC config can no longer crash on any ACC version. Every older parser fell back to safe defaults the way the current one already did, instead of force-unwrapping a missing or empty field.

### Changed
- Version is now 1.0.38 (build 42).

## [1.0.37] - 2026-05-31

The Battery card was showing nothing real on ACC 2025.x — capacity stuck at -1%, voltage at 0.000 V, everything else blank or Unknown. This release reads the battery again.

### Fixed
- Battery info now parses ACC 2025.x output. ACC rewrote what `acca -i` prints: the old `CAPACITY=23`, `VOLTAGE_NOW=4100000` style became lowercase `level 23`, `voltage_now 4.10`, and so on. AccA only knew the old style, so every field fell back to its empty default and the dashboard looked dead. It now reads both. Older ACC still works because the old format is tried first.
- Voltage no longer shows as 0.004 V. AccA assumed the raw value was always millivolts and divided by 1000; on a build that already reports volts that turned 4.1 into 0.004. The scale is now picked from the value's magnitude, so it reads right whatever ACC sends.

### Changed
- Version is now 1.0.37 (build 41).

## [1.0.36] - 2026-05-31

This fork's first bug-fix release. The main reason it exists: AccA crashed on ACC 2025.x, and now it doesn't.

### Fixed
- Reading the charging config no longer crashes on ACC 2025.x. ACC renamed the `max_temp_pause` setting to `resume_temp`; older AccA force-unwrapped that field while parsing and threw a NullPointerException the moment it met the new config. The parser now reads every field safely and falls back to ACC's own defaults when a key is missing, renamed, or blank. Both the old and new key names are accepted.
- The quick-settings profile tile survives an empty profile list. Tapping it with nothing saved used to index past the end of the list and crash; now it does nothing.
- Boot no longer risks an ANR. The boot receiver ran root shell calls on the main thread, which could hang the system broadcast. That work moved to a background thread.

### Changed
- Applying a temperature profile writes `resume_temp` as well as `max_temp_pause`, so the control takes effect on old and new ACC alike.
- Version is now 1.0.36 (build 40).

### Build
- The CircleProgressBar widget is vendored into the source tree. Its jcenter artifact was pulled and jitpack could not build it, which left the project unbuildable.
- Fixed a layout attribute the vendored widget had renamed (`style` became `progress_style`), which was failing resource compilation.
- Added GitHub Actions. Every push builds a debug APK; tagging a release (`v*`) builds a signed APK and attaches it to a GitHub Release.

[1.0.44]: https://github.com/seyedehsanhadi/AccA/releases/tag/v1.0.44
[1.0.43]: https://github.com/seyedehsanhadi/AccA/releases/tag/v1.0.43
[1.0.42]: https://github.com/seyedehsanhadi/AccA/releases/tag/v1.0.42
[1.0.41]: https://github.com/seyedehsanhadi/AccA/releases/tag/v1.0.41
[1.0.40]: https://github.com/seyedehsanhadi/AccA/releases/tag/v1.0.40
[1.0.39]: https://github.com/seyedehsanhadi/AccA/releases/tag/v1.0.39
[1.0.38]: https://github.com/seyedehsanhadi/AccA/releases/tag/v1.0.38
[1.0.37]: https://github.com/seyedehsanhadi/AccA/releases/tag/v1.0.37
[1.0.36]: https://github.com/seyedehsanhadi/AccA/releases/tag/v1.0.36
