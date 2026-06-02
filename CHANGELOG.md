# Changelog

Notable changes to this fork. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); version numbers match the app's own versionName.

## [1.1.6-rc8] - 2026-06-02

**Pre-release.** Bundles ACC rc8 — THE fix for stop-then-reset.

### Fixed (bundled daemon)
- The limit stopped charging but kept restarting. Root cause: the switch-verification checked the *absolute* current, so a switch that works by **discharging** (negative current) was rejected as "still flowing" → the daemon never locked it → re-probed and restarted. Now it rejects a switch only if it's still *charging* (positive current); discharging/idle = stopped = locked + held. **Tap the ACC-update prompt, then reboot.**

### Changed
- Bundled ACC daemon: **v2025.5.18-stable.6-rc8 (202505199)**.
- Version 1.1.6-rc8 (build 74).

## [1.1.6-rc7] - 2026-06-02

**Pre-release.** Bundles ACC rc7 — limit now HOLDS (no more stop-then-reset).

### Fixed (bundled daemon)
- It stopped at the limit then started charging again ~1% later. Cause: auto-mode re-probes switches every 35 loops, which toggled charging back on, because the working switch was never locked. The daemon now **locks the switch once it's current-verified** and stops re-probing → it holds. **Tap the ACC-update prompt, then reboot** so the rc7 daemon runs.

### Changed
- Bundled ACC daemon: **v2025.5.18-stable.6-rc7 (202505198)**.
- Version 1.1.6-rc7 (build 73).

## [1.1.6-rc6] - 2026-06-02

**Pre-release.** Bundles ACC rc6 — Pixel/Tensor limit now holds.

### Fixed (bundled daemon)
- On Pixel/Tensor the limit was passed because auto-mode hit the `charging_state` trap first (reports stopped while still charging) and locked nothing. The daemon now tries `charge_stop_level` **first**, offering both the stable (`100 pcap`) and the 2022/2023 (`100 5`) drivings, and keeps whichever actually drops the current. **Reboot after install** so the new daemon runs.

### Changed
- Bundled ACC daemon: **v2025.5.18-stable.6-rc6 (202505197)**.
- Version 1.1.6-rc6 (build 72).

## [1.1.6-rc5] - 2026-06-02

**Pre-release.** Bundles ACC rc5 — fixes charging overshooting the limit.

### Fixed (bundled daemon)
- The limit could be **surpassed** (e.g. set 30%, kept charging past it) because the daemon's switch auto-lock trusted *status* alone, and the Pixel/Tensor `charging_state` node reports "stopped" while current keeps flowing. The daemon now **verifies the current actually drops** before accepting a switch, so it locks one that truly cuts (`charge_stop_level`). Works on any SoC; lenient on mA kernels.

### Changed
- Bundled ACC daemon: **v2025.5.18-stable.6-rc5 (202505196)**.
- Version 1.1.6-rc5 (build 71).

## [1.1.6-rc4] - 2026-06-02

**Pre-release.** Bundles ACC rc4 — smart sensing for every SoC.

### Added (bundled daemon)
- `acca --state` (the "Show ACC state" script) now reports **live measured sensing on any SoC**: `plugged`, `currentUnits` (µA/mA auto-detected), `polarity`, and `switch.measuredClass` (bypass / idle / charging / discharging from plug + current). Not Tensor-only.

### Changed
- Bundled ACC daemon: **v2025.5.18-stable.6-rc4 (202505195)**.
- Version 1.1.6-rc4 (build 70).

## [1.1.6-rc3] - 2026-06-02

**Pre-release.** Bundles ACC rc2 — fixes the `acca --state` export bugs found on a Pixel 9a.

### Fixed (in the bundled daemon)
- The "Show ACC state" script now shows the **correct acc version** (was empty), a **derived charging status** (was "unknown" on the front-end path), and an **always-fresh snapshot** (was frozen on repeated runs).

### Changed
- Bundled ACC daemon: **v2025.5.18-stable.6-rc2 (versionCode 202505194)**.
- Version 1.1.6-rc3 (build 69).

## [1.1.6-rc2] - 2026-06-02

**Pre-release.** AccA can now ask ACC for its state export.

### Added
- **"Show ACC state (--state)"** one-tap script (Scripts tab) — runs `acca --state` and shows ACC's live machine-readable JSON snapshot (level, signed current, status, config-as-ACC-holds-it, locked switch). First consumer of the rc1 state export; the in-app diagnostics view that parses it (closed-loop confirm + warnings) is the next increment.

## [1.1.6-rc1] - 2026-06-02

**Pre-release.** Bundles ACC RC1 (the state-export keystone) so it can be tested through the app installer. No AccA app-behavior change yet.

### Changed
- Bundled ACC daemon: **v2025.5.18-stable.6-rc1 (versionCode 202505193)** — adds `acca --state` (alias `acc -j`), a machine-readable JSON snapshot of ACC's actual state that the upcoming control-bus + diagnostics rebuild reads back. Additive — charging behavior unchanged.
- Version 1.1.6-rc1 (build 67).

## [1.1.5] - 2026-06-01

Temperature band fix and an idle-above-limit toggle.

### Fixed
- **Default temperature band corrected to cooldown 45 °C < max 50 °C.** The 1.1.4 "lower max to 45 °C" change set max_temp equal to cooldown_temp (the config read `45 45 40 55`). cooldown_temp is where the gentle cooldown cycle starts and max_temp is the hard pause — with both at 45 the cooldown cycle is dead (it breaks the instant it would start). Restored ACC's proven 50 °C max with a 5 °C cooldown gap. The bundled daemon repairs an already-collapsed `45 45` config once, automatically.
- **Config parser fallbacks now use ACC's real defaults** (cooldown 45 / max 50 / resume 40), not the legacy 90/95 placeholders. A config missing `max_temp` no longer loads as 95 °C — which would have meant no thermal pause at all.

### Added
- **Idle above limit toggle.** Two one-tap scripts under Scripts — "Idle above limit: ON (default)" and "OFF" — flip ACC's `allow_idle_above_pcap`. ON (the default) lets the battery rest at your charge limit; OFF cycles down to the resume level instead, for forever-plugged 40–60 % setups.

### Changed
- Bundled ACC daemon: v2025.5.18-stable.5 (versionCode 202505192).
- Version 1.1.5 (build 66).

## [1.1.4] - 2026-05-31

Standalone ACC, a cooler default, and boot/profile housekeeping.

### Changed
- **Uninstalling AccA no longer removes ACC.** ACC is a standalone module with its own persistent config; the app is just a front-end (a remote control). Previously an uninstall could delete the daemon on next boot — fixed, and cleaned up automatically on update.
- **Default max temperature lowered 50 → 45 °C** (heat above ~45 °C ages the cell faster). A limit you set yourself is left untouched.
- The boot receiver is now registered (justifies the boot permission; redundant with the module's own boot hook, so harmless), and a stale profile temperature default (legacy "90") is corrected.
- Bundled ACC daemon: v2025.5.18-stable.4 (versionCode 202505191).
- Version 1.1.4 (build 65).

## [1.1.3] - 2026-05-31

Fixes charging getting stuck below your range (e.g. frozen at 64 %).

### Fixed
- If the battery dropped below your range it could **freeze — not charging, not draining**. The charge-stop chip latches "stopped" at your limit, and the previous build's resume re-sent the limit value, which doesn't re-arm it. Resume now works the way 2022/2023 did: charge up to your limit, stop, drift down, charge again at your resume level — a normal cycle inside your range. A locked switch from an older build is upgraded automatically on update (no command).

### Changed
- Bundled ACC daemon: v2025.5.18-stable.3 (versionCode 202505190).
- Version 1.1.3 (build 64).

## [1.1.2] - 2026-05-31

Fixes the battery draining to ~70 % instead of holding at your limit.

### Fixed
- The previous build could drain the battery down to your resume level (~70 %) instead of holding at your limit. That came from the discharge variant; it's been removed. Now charging stops at your limit and holds there — the only behavior. Existing installs migrate automatically on update (no command).

### Changed
- Removed the "Scan & lock: discharge-cycle" script (no discharge variant anymore); the remaining one is just "Scan & lock charging switch".
- Bundled ACC daemon: v2025.5.18-stable.2 (versionCode 202505189).
- Version 1.1.2 (build 63).

## [1.1.1] - 2026-05-31

No setup needed — the corrected defaults now apply automatically on update.

### Changed
- Existing installs adopt the **"never sit above your limit"** default automatically when you update — **no manual command, no script**. The bundled installer does it once and never overrides a setting you deliberately change later. (Fresh installs already shipped this default.)
- Bundled ACC daemon: **v2025.5.18-stable.1** (versionCode 202505188).
- Version **1.1.1** (build 62).

## [1.1.0] - 2026-05-31

**First stable release** — the charge limit holds exactly where you set it. Consolidates and hardens the 1.0.36–1.0.56 fixes.

### Charge limit
- **Holds exactly at your limit** on Pixel/Tensor — no overshoot. The limit node is driven to your target level on both sides, never "charge to 100% then interrupt" (the old 75→77 breach).
- **Never sits above your limit.** If the battery is over it (e.g. you set 75 % while at 80 %), it discharges *down* to the limit (`allow_idle_above_pcap` now defaults off).
- **Default: hold at the limit** (battery-idle). Turn battery-idle off for a discharge-cycle between your resume level and the limit.
- **Lock a method** from Scripts: "Scan & lock: hold at limit" (default) or "Scan & lock: discharge-cycle".

### Reliability
- Settings apply within ~1 second — no daemon restart, no app freeze.
- A switch scan can no longer leave the daemon stopped / charging uncapped.
- Fail-safe against bad config: an empty or non-numeric limit pauses instead of running on.
- A breach watchdog warns if the cap ever isn't holding.

### Under the hood
- Bundled ACC daemon: **v2025.5.18-stable** (versionCode 202505187).
- Version **1.1.0** (build 61).

## [1.0.56] - 2026-05-31

Choose and lock your charging method — hold at the limit, or discharge-cycle.

### Added
- Two one-tap options in the Scripts tab:
  - **"Scan & lock: hold at limit (default)"** — parks the battery at your limit (pcap). Longevity-friendly; this is the default.
  - **"Scan & lock: discharge-cycle"** — discharges to your resume level, recharges to the limit, and repeats. Use when you've turned battery-idle off.
  Both *lock* the chosen method (ACC won't auto-switch it), and the recharge always stops exactly at your limit.

### Changed
- Bundled ACC daemon updated to v2025.5.18-dev-fix12 (versionCode 202505186).
- Version is now 1.0.56 (build 60).

## [1.0.55] - 2026-05-31

The cap now stops exactly at your limit (no overshoot), and you choose hold vs cycle.

### Fixed
- **No more overshoot.** The charge limit is driven to your *target* level on both the charge and the pause side, so charging stops exactly at your limit (e.g. 75%), never above. The old behaviour told the firmware to "charge to 100%" then interrupt, which sailed past the limit (the 75→77 you saw).

### Changed
- **Default = hold at the upper limit (battery-idle).** With "prioritize battery idle mode" on (the default), the battery is parked at your upper limit.
- **Idle off = discharge-cycle.** Turn "prioritize battery idle mode" off and the battery discharges to your lower limit (resume_capacity), then recharges to the upper limit, and repeats — the recharge still stops exactly at the upper limit.
- Removed the old `charge_stop_level 100 5` / `100 battery/capacity` variants (they recharged toward 100% and overshot).
- Bundled ACC daemon updated to v2025.5.18-dev-fix11 (versionCode 202505185).
- Version is now 1.0.55 (build 59).

## [1.0.54] - 2026-05-31

Settings apply instantly, and a scan can no longer leave charging uncapped.

### Fixed
- **Settings apply immediately.** Changing a limit, temperature, or switch now takes effect within ~1 second — the daemon wakes the moment the config changes and re-reads it live, instead of waiting out the full loop delay. No daemon restart, no app freeze.
- **A scan no longer kills the daemon.** "Scan & fix charging switch" restarts the daemon when it finishes; that restart ran the daemon *inside* the one-shot scan script, so it died when the script exited — leaving charging uncapped (the daemon showing "stopped" after a scan). The daemon now launches detached and stays running, and the scan verifies it came back, warning if it didn't.
- **Scanner evaluates every switch.** It re-arms charging between tests, so a switch tested right after a stopping one (like the precise `pcap` flat-hold variant) is no longer skipped as "not charging".

### Changed
- Bundled ACC daemon updated to v2025.5.18-dev-fix10 (versionCode 202505184).
- Version is now 1.0.54 (build 58).

## [1.0.53] - 2026-05-31

Final hardening pass — full software coverage of the charge-control safety paths.

### Fixed
- Full fail-safe coverage: every capacity limit check (pause, resume, cooldown, shutdown, idle-reassert) now treats an empty or malformed value as the safe outcome, so no bad config can make a check error out and skip a stop. Previously only two of seven were guarded.
- Flat-hold extended to the `/proc/driver/charger_limit` charge-limit node (in addition to the Google `charge_stop_level` node), so more chipsets hold the cap flat with no overshoot.
- Breach watchdog: if the battery is at/above your limit and charging still hasn't stopped, you now get a warning notification instead of it failing silently. It clears itself once charging stops.

### Changed
- Bundled ACC daemon updated to v2025.5.18-dev-fix9 (versionCode 202505183).
- Version is now 1.0.53 (build 57).

## [1.0.52] - 2026-05-31

Safety hardening on top of 1.0.51.

### Fixed
- Fail-safe against a malformed (non-numeric) pause/resume limit: the daemon now treats it as "pause now / don't resume" instead of letting the numeric check error out and silently skip the cap.
- A charging switch locked by "Scan & fix" (or by hand) that later stops working now auto-recovers — it re-selects a working switch and posts a warning — instead of silently letting the battery charge past the limit. The lock still prevents routine re-cycling while the switch works, so there's no churn in the normal case.

### Changed
- Bundled ACC daemon updated to v2025.5.18-dev-fix8 (versionCode 202505182).
- Version is now 1.0.52 (build 56).

## [1.0.51] - 2026-05-31

Fixes the upper charge limit being overshot on Pixel/Tensor — the cap now holds flat.

### Fixed
- On Pixel/Tensor the only working charge switch is `charge_stop_level`, which is a charge *limit* ("charge to N %, then hold"), not an on/off switch. The bundled daemon (fix5/fix6) drove it as 100/5 — writing `5` to pause made the firmware *discharge*, then resume at the resume level and re-charge, overshooting the cap in a 70↔limit sawtooth (e.g. a 75 % limit drifting to 77 %). The bundled daemon (now v2025.5.18-dev-fix7) instead sets the limit node to your target level, so the firmware holds the battery flat at the cap: tight, no overshoot, and true battery-idle.
- The "Scan & fix charging switch" scanner now prefers idle/flat-hold switches over discharging ones, so it locks in the variant that actually holds the cap.

### Changed
- Bundled ACC daemon updated to v2025.5.18-dev-fix7 (versionCode 202505181, so it installs over an existing 202505180 daemon instead of being skipped).
- Version is now 1.0.51 (build 55).

### After updating
- If you previously ran "Scan & fix charging switch" or locked a switch by hand, tap "Scan & fix charging switch" once more so the new flat-hold switch is selected. Or set it directly (75 = your pause limit): `acc -s s='/sys/devices/platform/google,charger/charge_stop_level 100 75 --'`

## [1.0.50] - 2026-05-31

A fast charging-switch scanner, built into the app.

### Added
- Two entries in the Scripts tab: "Scan charging switches (fast)" and "Scan & fix charging switch". They run a new scanner that polls the charging current ~3×/second and decides each switch in 1–4 s. The old "acc -t" waited up to 35 s per switch and AccA's timeout often killed it before it finished the list. The scan ranks the switches that actually stop your charger; the "& fix" one locks the best one in automatically.

### Changed
- Bundled ACC daemon updated to v2025.5.18-dev-fix6 (ships the scanner).
- Version is now 1.0.50 (build 54).

## [1.0.48] - 2026-05-31

Reverts the 1.0.47 auto-restart, which made the app hang on every settings change.

### Fixed
- 1.0.47 restarted the ACC daemon after every config change. A restart re-detects every charging switch, which takes several seconds, so AccA froze (and sometimes ANR'd) each time you changed a setting. Reverted. The daemon already re-reads the limit and temperature live within a few seconds, so those still apply on their own; only a charging-switch change needs a restart, which you can do from the dashboard.

### Changed
- Version is now 1.0.48 (build 52). Bundled ACC daemon stays v2025.5.18-dev-fix5.

## [1.0.47] - 2026-05-31

Settings take effect immediately now — no more restarting the daemon by hand.

### Fixed
- Changing a setting used to need a manual daemon restart to apply. AccA writes config through ACC's `acca` applet, which (unlike `acc --set`) never restarts the daemon, and the apply path didn't restart it either — so the charging switch and a few other settings sat dormant until you hit Restart. AccA now restarts the daemon right after applying a change, so what you set is what runs.
- Turning off "Prioritize battery idle mode" now tells ACC to actively prefer a clean on/off switch (it sends `no`, not just `false`). On Pixel/Tensor that's what stops the charge limit cycling on and off.
- Picking a specific charging switch on older ACC builds now locks it (appends ` --`) so the daemon holds it instead of auto-cycling. The current ACC build already did this.

### Changed
- Version is now 1.0.47 (build 51). Bundled ACC daemon stays v2025.5.18-dev-fix5.

## [1.0.46] - 2026-05-31

A polish pass on the charging-limit fix, specifically for Pixel / Tensor devices.

### Fixed
- No more brief on/off charge bursts near the limit on Pixel. ACC was auto-selecting the Pixel `charge_stop_level` switch in its `battery/capacity` form, whose stop level tracks the *live* battery %, so the firmware kept nudging charging back on at the threshold and the daemon kept re-testing the switch. ACC now uses the fixed-threshold form of that switch, which holds the limit cleanly without the churn — the limit just holds, with the normal slow charge/pause cycle between resume and pause.

### Changed
- Bundled ACC daemon updated to v2025.5.18-dev-fix5 (carries fix4 plus the Pixel switch fix above).
- Version is now 1.0.46 (build 50).

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

[1.0.50]: https://github.com/seyedehsanhadi/AccA/releases/tag/v1.0.50
[1.0.48]: https://github.com/seyedehsanhadi/AccA/releases/tag/v1.0.48
[1.0.47]: https://github.com/seyedehsanhadi/AccA/releases/tag/v1.0.47
[1.0.46]: https://github.com/seyedehsanhadi/AccA/releases/tag/v1.0.46
[1.0.44]: https://github.com/seyedehsanhadi/AccA/releases/tag/v1.0.44
[1.0.43]: https://github.com/seyedehsanhadi/AccA/releases/tag/v1.0.43
[1.0.42]: https://github.com/seyedehsanhadi/AccA/releases/tag/v1.0.42
[1.0.41]: https://github.com/seyedehsanhadi/AccA/releases/tag/v1.0.41
[1.0.40]: https://github.com/seyedehsanhadi/AccA/releases/tag/v1.0.40
[1.0.39]: https://github.com/seyedehsanhadi/AccA/releases/tag/v1.0.39
[1.0.38]: https://github.com/seyedehsanhadi/AccA/releases/tag/v1.0.38
[1.0.37]: https://github.com/seyedehsanhadi/AccA/releases/tag/v1.0.37
[1.0.36]: https://github.com/seyedehsanhadi/AccA/releases/tag/v1.0.36
