# Changelog

Notable changes to this fork. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); version numbers match the app's own versionName.

## 2.0.1-rc23 (223)

Pairs with ACC v2025.5.18-6.5.1-rc24. Install both.

Added
- Home-screen widget that works on Android 12 and later.
- Schedules remember which configuration sections they were written with.
- Bundled AMPS switch-finder v7.3.0, byte-identical to the module's copy.
- CI runs the unit tests and lint before it builds anything, on a recorded lint baseline.

Fixed
- Boot receiver no longer reachable from other applications.
- Widget crashed on Android 12 and later (missing FLAG_IMMUTABLE).
- Widget refreshes from the charge meter instead of an exact alarm.
- Widget applied the current sign twice, so an inverted-polarity phone flipped between plus and minus.
- Widget printed charger watts on a row labelled "To battery".
- Dashboard and widget disagreed about whether the phone was charging; one rule now answers it everywhere.
- A held input read as Idle instead of a cut.
- Idle now means the pack is sitting still, not merely plugged and not charging.
- A measured drain overrides both the daemon's class and the kernel status. A collapsed charger and a latched native limit each printed Charging over a negative current.
- Charger row hid itself at a 0 W hold; it now shows the plug state and input voltage.
- Flipping a schedule's checkbox rewrote it with voltage, current and temperature control switched back on.
- Schedules ignored the DJS cue and rebuilt the command with every gate enabled.
- A daemon error payload was parsed as a real reading, showing -1%, 0 mA, 0 V instead of falling back to the legacy path.
- A truncated state document was accepted the same way.
- Celsius turned into Fahrenheit on stock installs; the unit preferences are three-valued, not two.
- A failed apply was still recorded as the current profile, so the screen could name profile B while ACC held profile A.
- Voltage and current-max are gated independently.
- Cloud backup uploaded the root scripts database; it is now excluded.
- The app resolves its real files directory, so a clone or secondary user can start the app-managed ACC install.
- AMPS recorded a probe-collapsed 0 as a current-limit node's original value, which let a restore replay 0 and leave the port at no input current.

## 2.0.1-rc21 (221)

Pairs with ACC v2025.5.18-6.5.1-rc22. Install both.

Added
- Find my charging switch has its own card on the dashboard, above the configuration.
- Profile and script export screens gained select-all and select-none.

Fixed
- A charge limit of 100% no longer silently becomes 80%.
- A limit of 100% no longer reads back as capacity control being off.
- Exporting several profiles or scripts no longer produces a truncated file.
- Importing no longer overwrites a local profile or script of the same name.
- Settings now shows the refresh interval ACC actually holds, not the default.
- The dashboard no longer shows wrong watts, amps or volts, or 0.00 W while current is flowing.

Changed
- Export saves the file by default; sharing moved to the toolbar.
- Exports go to their own Download/AccA folder and never overwrite an earlier backup.
- Import offers the same three routes as export: a file, the clipboard, or another app.
- Export screens start with everything selected.

Removed
- The fast-charge re-kick, and its "Re-kick fast charge on plug" setting. It wrote apsd_rerun and rerun_aicl straight to the nodes, without the guard ACC gained in rc22 that refuses to re-run charger detection while a working high-voltage contract is live. On a QC or PD supply that drops the contract to 5 V until you physically replug. ACC still re-kicks automatically when charging is genuinely stalled, and `acc -sk on|off` remains the control.

AMPS 7.2.3
- A scan run during a pause or a thermal throttle no longer suggests pinning the phone at that current.
- A level-limit switch on a phone below its stop level now produces a usable config line instead of falling through to a current cap.

## 2.0.1-rc20 (220)

Everything since rc19, in one release.

- Blocked settings could not show or remove anything once ACC started recording what a setting was doing when it crashed the phone. The list file gained two extra fields per line and the app was still reading the whole line as the setting's name, so every entry looked like a path that does not exist: nothing appeared in the list, and removing or editing silently did nothing. It now reads the name from the first field and shows the rest, so an entry says what it was writing and when it happened rather than only that it is blocked.

- Blocked settings can now add, remove and clear, not only read. A setting you already know is unsafe on your phone can be blocked by name (a bare node name is resolved to its full path), any entry can be removed, and the whole list cleared in one step with a count shown before you confirm. The list reopens after every action, so a change is visible instead of leaving you looking at the old contents.

Pairs with ACC v2025.5.18-6.5.1-rc21. Install both.

Version bump only. The app and the module keep separate release numbers, so
AccA rc20 is the build that goes with ACC rc21.

No app code changed. rc21 is a module-side release, and every command AccA
issues returns exactly what it returned under ACC rc20. That was checked rather
than assumed: the full set of commands AccA runs was executed against both
module versions on a Pixel 9a and a Mi A3 and the results compared. AccA also
picks its handler by ACC version code, and rc21 (202505301) resolves to the same
handler as rc20, so the module bump cannot quietly drop the app onto an older
code path.

One module fix is worth knowing about as an AccA user. On rc20, pressing an
unrecognised key in ACC's charging-switch menu silently reset your chosen switch
to Automatic and reported success. If you ever picked a switch by hand and later
found it back on Automatic, that is why. rc21 fixes it.

## [2.0.1-rc19] - 2026-07-19

Pairs with ACC v2025.5.18-6.5.1-rc20. Install both.

### Changed
- **Battery percentage now comes from ACC (the kernel) by default, not Android.** Identical
  numbers for almost everyone; it only differs if you set a Capacity Mask. Fixes the reported
  case: when something freezes Android's battery state the app showed a stale number and
  looked wrong. Measured on a Pixel 9a at 69% - no mask: both read 69; Android frozen: old
  default read 23, new one read 69. Several users had already found this setting themselves.
- Using a Capacity Mask and want the app to match your status bar? The System option is one
  tap away. Display only - ACC's charging decisions always used the kernel and are unchanged.
- Battery-source options reworded so it is clear which is which without trying them.

### Fixed
- The fallback ACC version assumed before the installed one can be read now tracks rc20, so a
  fresh install picks the newest handler.

## [2.0.1-rc18] - 2026-07-16

### Fixed
- **Battery percentage source labels** were backwards. With a Capacity Mask set, "System" shows the masked value (it follows the status bar) and "ACC" shows the true measured level (it ignores the mask). The labels now match what each option does. Wording only, no behaviour change.

### Changed
- **Re-kick fast charge on plug** description is clearer. It said "below your limit", which read as ambiguous; it now says "below your Stop level" and spells out that it does not start charging (your Resume level still does that) - it only makes a resumed charge run at full speed. Wording only.

## [2.0.1-rc17] - 2026-07-14

### Fixed
- **Find my switch (AMPS) v7.1.5.** The charger/speed panel was accusing healthy phones of charging slowly. Three fixes, found from a Realme GT Neo 2 (65W SuperDart) report:
  - It looked for the charger only on usb/main/dc/wireless/pc_port. On Qualcomm and OPLUS phones (Realme, OPPO, OnePlus) the mains path reports online on `ac` while usb stays at online=0 mid-charge, so it found nothing and said "not plugged / no input supply reports online" **while the phone was actively charging**, with input current and voltage all zero. It now scans every supply and takes whichever one the firmware marks online.
  - A cap that reads back negative is a kernel error code, not a value: `-22` is `-EINVAL` ("property not supported"). AMPS stripped the sign and reported "IC cap (CCC)=22mA", which also hid the real ceiling and could fire a false "IC/THERMAL-CAPPED" verdict blaming your charge IC. Caps now reject negatives.
  - **Model spoofing.** A ROM that fakes `ro.product.*` (this one claimed to be a Galaxy S23 Ultra) would file its switches into the device database under someone else's model. AMPS now cross-checks the vendor partition, device tree and charger-driver family, reports the spoof, and keys the database on the hardware identity.

## [2.0.1-rc16] - 2026-07-13

### Added
- **Re-kick fast charge on plug** (Settings > Charging, off by default). Plug in below your pause limit and AccA nudges the charger to re-negotiate fast charge - useful on phones that drop to slow charging after a cut. Guarded so it can never overcharge or fight your setup: it only fires below the limit, is a no-op on phones with no such control (Pixel/Tensor and other PD chargers), and steps aside entirely if you run your own Apply-on-plug script.
- **AMPS reports whether your phone can re-kick.** The bundled compatibility tester (v7.1.4) detects your phone's resume mechanism: a software re-kick on Qualcomm (`apsd_rerun`/`rerun_aicl`/`dp_dm`) and MediaTek (`en_power_path`), or "physical replug or reboot only" on newer PD-glink/UCSI chargers that self-negotiate in firmware.
- **Update notifications** (on by default). AccA now checks for newer ACC *and* AccA versions and posts one combined, persistent notification - with each version's changelog and a per-app *Update* button - so a pending update for one never hides the other. It stays until you act on it or swipe it away, and a version you dismissed will not nag again.
- **"See all switches" in the switch finder**, so you can pin any verified switch, not only the recommended one.
- **Sponsor links in About**: Buy Me a Coffee and Ko-fi, alongside the fork's Telegram.

### Changed
- **ACC install/update reads GitHub Releases.** The bundled ACC copy is gone - AccA now detects a separately-flashed ACC and points you to the download instead of shipping (and overwriting with) its own. Both update screens list every published version newest-first, default to the latest, show pre-releases, and show the version you have now.
- **Battery-% source is one app-wide setting.** The dashboard, the status-bar meter and the notification all follow the same choice (Android System vs ACC's real level).
- **Support and community links** now point to the fork's Telegram group and issue tracker.
- Long setting descriptions (re-kick fast charge, updating ACC) are now a short line with an info (i) icon that opens the full explanation on tap.
- Built against SDK 33; the switch finder is trimmed to verified switches; the redundant "Test" button was dropped from the switch editor.

### Fixed
- **Settings no longer crashes on open** - a bare `%` in the battery-source summary threw a format exception.
- **The switch picker no longer opens empty**, recovers cleanly on first run, and no longer mis-renders under some locales; switches that share a short label (the same node reached by two paths) are shown by full path so you can tell them apart.
- **Charge meter no longer dies or freezes.** Screen-off used to detach its notification from the foreground service (letting the system kill it), and one glitchy "is the screen on?" read could freeze updates for good. It now stays a foreground service, swaps to a quiet no-status-bar-icon channel when the screen is off, and debounces the screen check.
- **Consistent charge words and current everywhere.** The status word (Charging / Idle / Bypass / Draining / Discharging) is derived from the same reading shown beside it, the dashboard and notification agree, the status-bar number is a median of recent samples (no fuel-gauge spikes), and the dashboard shows watts alongside amps.
- The About / team card lists the maintainer first.

## [2.0.1-rc15] - 2026-07-07

### ✨ Added
- **Status-bar charge meter.** Turn on *Show charging current in the status bar* for a live, edge-to-edge number in the status bar showing the current going into your battery, plus a matching notification line (current, watts, volts, temperature, and the hold level when ACC is holding your limit). It is a foreground service that draws zero battery while the screen is off.
- **Battery source choice.** A new setting picks which battery reading AccA trusts: the Android *System* percentage (matches the icon in your status bar, the default) or ACC's own *Real Level*. Use Real Level on phones whose framework rounds or smooths the percentage.
- **Update browser.** *Check for updates* now lists every AccA release published on GitHub, newest first, and downloads the one you tap instead of guessing a single "latest". Both the AccA and ACC update screens show the version you have installed now.

### 🔧 Changed
- *Show charging current in the status bar* is off by default and, when on, always shows and is bold and large by design; the earlier per-condition toggles for size, weight, unit, sign and on-battery were removed.

### 🐛 Fixed
- The status-bar meter no longer leaves a lingering on-battery notification row with a Stop button when you only wanted the number.
- A charge daemon left dead by a force-killed test is revived within about ten seconds of plugging in the charger (pairs with the ACC-side fix in 6.5.1-rc13).
- Charge vs discharge is read correctly on dual-path PMICs whose current sign flips with the charge mode (understands ACC's new `polarity=unstable`).

## [2.0.1-rc14] - 2026-07-05

### ✨ Added
- **The dashboard now tells you how fast you are charging, and why not faster.** While charging, the Battery card shows a physics-based line like *"Fast charge: 19 W (9.0V), 2.15 A in (x1.8 to battery)"* or *"Slow charge: 2 W (5.1V), 0.50 A in (x1.1 to battery)"*. Watts is measured (input volts times amps), so it is correct on every charger including the vendor-locked 120W+ ones that expose no protocol label. When charging is held back it says why: *limited by your Max current*, *slowed by heat*, or *topping off, near full*. Hidden when not charging, and on phones without input sensors it falls back to a battery-side estimate marked approximate. (Reads ACC's new `--state` charge block; the research behind the wattage bands was independently fact-checked, and it is fully read-only.)

## [2.0.1-rc13] - 2026-07-05

### 🛠️ Fixed
- **The dashboard charger line no longer overlaps the battery circle or leaves a blank gap.** The rc12 line was a label + value row (like Status/Voltage), but its value is a long sentence, so the label collapsed and the value overflowed the narrow text column - spilling over the capacity circle and off the right edge, with an empty gap before the "Manual lock" badge. It is now a single full-width line (max two lines, ellipsized), the same style as the badge below it.

## [2.0.1-rc12] - 2026-07-05

### ✨ Added
- **The charger is now shown right on the dashboard.** While charging, the Battery card names your charger and shows the input current and how much reaches the battery, e.g. *"9V fast charger, 2.15 A in (x1.8 to battery)"*. Before, this only appeared inside the Max-current edit dialog (behind the gear, and only if a current limit was set), so most people never saw it. It reads ACC's `--state` input telemetry (rc11) and hides itself when not charging or on phones without input sensors.

## [2.0.1-rc11] - 2026-07-05

### ✨ Added
- **Max current now tells you what your battery actually gets.** On most phones the current limit caps the CHARGER INPUT, not the battery: a 9V fast charger delivers about 1.8x your setting into the battery (it steps 9V down to ~4V), a 5V charger about 1x. The edit-limit dialog now detects your charger (5V / 9V / 12V / 15V / 20V) from the live input voltage and, while charging, shows the measured line: e.g. *"9V fast charger detected (9.0 V in). Your battery gets about x1.8 your setting: 976 mA in now means ~1795 mA into the battery. Set about 543 to put 1000 mA into the battery."* It refreshes every few seconds, so swapping chargers updates it live. Not charging, or on a phone without input sensors, it shows the general rule instead. (Reads ACC's new `--state` input telemetry; device-measured on a Pixel 9a at both 5V and 9V.)

## [2.0.1-rc10] - 2026-07-05

### 🔥 Removed
- **The "Test charging switches" quick-action (`acca -t`).** It stops the charge-control daemon and runs for minutes, and if you force-close the app mid-run it is killed before it can restore - leaving a switch cut (no charge until reboot) or the battery charging past your limit (reproduced on a Mi A3). Same danger that retired the "Test battery idle mode" button in rc6. Everything it did is covered, safely, by "Find my charging switch" (which snapshots and restores every change) and the daemon's own switch auto-lock. Removed from new installs and, on upgrade, from your existing script list. The safe diagnostic actions (List switches, Battery info, Show ACC state, etc.) stay.

### 🛠️ Fixed
- **The dashboard now shows BOTH the voltage limit and the current limit when you set both.** It was only ever showing one of them (usually voltage). They are independent controls, so both rows are shown.
- **Custom scripts that call `acc`/`acca` past the first command now work on KernelSU/APatch.** The app rewrote only a *leading* `acc`/`acca` to its full path; a multi-command script (e.g. `sleep 2; acc -D restart`) failed with "acca: not found" on roots where acc is not on PATH. The script's PATH now includes the acc directory, so `acc`/`acca` resolve anywhere in the body.

### 🔬 Find my switch (AMPS 7.1.3)
- From a layer-by-layer audit for device universality: the switch value-gate now accepts capitalized states (Enabled/Disabled/ON) some kernels report; charger-supply detection also recognises adapter/pogo/dock supplies; and on non-Qualcomm SoCs a charger that drops offline mid-test now logs the missing re-kick node for a field report instead of failing silently. No new hardware writes were added.

## [2.0.1-rc8] - 2026-07-04

### 🛠️ Fixed
- **Find my switch (AMPS 7.1.1) no longer recommends a battery-draining cut on phones with a real firmware charge limit.** On a Pixel it was demoting `charge_stop_level` - the native %-limit it had just *verified* - because the phone's own Adaptive Charging rewrites that node's value, which the stress-test miscounted as the switch "re-arming" even though the battery held flat. A native %-limit is now judged only by whether the battery actually charges past the limit, never by the firmware churning the node. It stays the recommendation, as it should. (Cut/drain switches keep the re-arm check.)
- **Cancelling the Apply on Boot / Apply on Plug dialog is now a clean no-op.** Enabling the switch and then cancelling the command dialog left a phantom "unsaved changes?" prompt on exit. The editor now re-checks against what you loaded, so a reverted toggle no longer counts as a change.

### 📝 Changed
- **Clearer descriptions.** "Apply on Boot" now says it runs your own commands at boot and does *not* re-apply your charge config (ACC does that automatically). "Allow custom shell scripts" now describes what it actually gates - adding or editing script bodies - instead of implying only bundled actions can run.

## [2.0.1-rc7] - 2026-07-04

### 🛠️ Fixed
- **The Apply on Boot / Apply on Plug switches no longer silently revert.** Both switches derive their on-state from whether the config field actually holds a command, so turning one on without entering a command wrote an empty value and the switch flipped back off the next time you opened the editor (field report: "Is the Apply on Boot toggle not supposed to stick?"). Enabling the switch now opens the command editor straight away, so "on" always means a command is set; leaving it empty or cancelling flips the switch off immediately instead of on the next open, and turning it off clears the command. Load-time state is unaffected. Same class of fix as the Charging Power Control switch in rc5, applied to the two command-backed toggles.

## [2.0.1-rc6] - 2026-07-03

Whole-app audit pass (the reporter asked for a fine-tooth comb after years of neglect), plus charging-switch editor and Diagnostics follow-ups. The ACC-interface layer round-tripped clean.

### 🔥 Removed
- **The "Test battery idle mode" button.** It ran a full daemon-stopping charging-switch scan just to answer one yes/no question, took minutes on multi-switch phones (the app cuts it off at 60s, making the verdict unreliable), and repeatedly cut charging while doing it - on some chargers (Snapdragon PMI632) that can wedge charging until a reboot. The switch finder ("Find my charging switch") already classifies every working switch, including battery-idle/bypass ones, and the daemon self-disables idle-priority on phones that cannot do it. The Prioritize-battery-idle toggle stays. (This supersedes the earlier partial "battery-idle test no longer hangs when unplugged" fix.)

### 🛠️ Fixed
- **Diagnostics now shows your CURRENT charging switch.** A field report: "Diagnostics ## Battery shows my old charging switch (sm7250_bms/online 1), not my present switch (charge_stop_level 100 pcap)". Those `*/online` lines at the end of the Battery section are the kernel's power-supply online status - they just happen to look like a switch tuple. Diagnostics now has a dedicated "Charging switch (current, from ACC config)" section (including whether it is pinned by you), and the Battery section is labeled for what it actually dumps.
- **The Charging-switch editor shows your present switch, opens instantly, and explains itself.** A switch pinned via Apply & Lock is stored as a full /sys path that ACC's candidate list may not contain, so the dialog opened with nothing selected and your active switch invisible; the current switch is now always listed and selected. Opening the editor could also freeze for ~30s (an ACC-side bug where listing switches on a missing candidate file tripped the log exporter, fixed in ACC 6.5.1-rc5). "Automatic" now says what it does, and Add Charging Switch has help text plus example values.
- **Schedules can no longer be wiped by a database rebuild.** The schedule list refresh treated any DJS entry with no matching local database row as garbage and deleted it from DJS. If the local database was ever rebuilt (a downgrade to an older build, or a failed upgrade migration), every schedule survived in DJS but lost its database row, so the very next refresh - which now runs every time you open the tab and every 30s - silently deleted all of them. A DJS entry without a database row is now recovered (shown as "Recovered schedule", still firing) instead of deleted. Verified on device.
- **Dashboard STOP** uses ACC's canonical `acca -D stop` (matches every other code path) instead of a fragile alias.
- **The Miscellaneous card's restore (↺)** now reverts both reset-stats switches (on-pause was left unchanged).
- **The seeded "CoolDown Temp after 40%" script** used ACC's years-old `max_temp_pause=90` (renamed to `resume_temp`, and it's °C not seconds) on fresh installs; corrected to `resume_temp=40`.
- **Reopening after installing DJS or reinstalling ACC** no longer stacks a duplicate config/apply observer (double error dialogs) or re-hits the GitHub update API.

## [2.0.1-rc5] - 2026-07-03

Follow-up to the current-limit fix: the value stuck, but the Charging Power Control switch reverted off (4a 5G field report).

### 🛠️ Fixed
- **Charging Power Control switch tracks current, not just voltage.** The section's on/off switch was wired only to the voltage limit, and the per-profile current-enable flag was never actually written, so a current-only limit left the switch reflecting "voltage is off" - it reverted off on reopen even though the current value was saved. The switch now reflects either limit, the dialog updates both enables when you set them, and turning the section off clears both limits. Verified on device: set a current-only limit, reopen, the switch stays on and the value shows; turn it off and the limit clears.
- **Section switches show the real state on the live-config editor.** On Custom Settings the config loads asynchronously, so the enable switches were being read once from defaults before the values arrived and never refreshed - the same reason the power switch looked off. The editor now re-publishes the derived switch states after the config loads, so Power Control (and the other derived switches) reflect what is actually set.
- **"Prioritize battery idle mode" stays off when you turn it off.** ACC records this switch as `no` when disabled (a stronger "off" than `false`), but the app only recognised `true`/`false`, so `no` fell through to the default and the switch sprang back on. It now reads `no` as off. Found by a full round-trip audit of every setting.

### 🔍 Audited
- **Every setting round-tripped on-device.** Each field the app writes was set through the app's exact command and read back through `acca --set --print`: capacity (shutdown/resume/pause/cooldown), temperature (cooldown/max/resume/shutdown), cooldown ratio, current limit, voltage limit, apply-on-boot, apply-on-plug, reset-stats on pause/unplug, prioritize-idle, and the charging switch with its manual lock - all 18 checks pass. The two mismatches found (the power switch and prioritize-idle) are the only ones, and both are fixed here.

## [2.0.1-rc4] - 2026-07-02

The KernelSU-Next tester confirmed DJS boots cleanly on rc3, then found two Scheduler bugs; a pre-release tester also caught the update link.

### 🛠️ Fixed
- **"Get update" opens the build you were told about.** It used to open /releases/latest, which is always the newest STABLE - so a pre-release tester who tapped the update notification landed on the wrong (older) build. It now opens the exact release from the notification: the AccA app update goes straight to that version's APK download, and the ACC module update opens that release's page with its flashable zip. "Get ACC" when the module is missing still points at the latest stable, as it should.
- **Boot schedules now save and appear.** A boot (or apply-on-boot) schedule carries a "wait for ACC, then apply" prefix before its id marker; the list parser was anchored to the marker at the very start of the line, so it silently dropped every boot schedule - you saved one and nothing showed up ("the save button doesn't work"). The parser now finds the id anywhere in the command, still anchored on the trailing ';' so ids never collide (1 vs 10).
- **Run-once schedules disappear after they fire.** DJS deletes a run-once job from its own config when it runs, but the app only re-read that list on cold start, so a fired one lingered in the UI. The Schedules screen now refreshes when you open it and every 30s while it is open, so a spent run-once drops off on its own.

## [2.0.1-rc3] - 2026-07-02

Third candidate, from the KernelSU-Next tester's rc2 logs. Those logs proved two things: the daemon never even started at his boots yet internal storage still went missing (so the daemon is innocent), and his ROM never ran the module's early-boot watchdog stage. The common factor in every broken boot is the module being mounted at all.

### 🛠️ Fixed
- **DJS no longer mounts anything off Magisk.** On KernelSU / KernelSU Next / APatch the module is installed with skip_mount and without the /system/bin command symlinks, so susfs/hybrid-mount setups have nothing of DJS to digest at boot. Schedules are unaffected: AccA talks to the daemon through its runtime path, which needs no mount.
- **Watchdog moved where it provably runs.** rc2's watchdog lived in an early-boot stage this tester's ROM never executed. It now also runs in the boot stage that his logs prove does run, using a per-boot id so the two stages can share one marker without false alarms. A boot where internal storage never comes up now counts as a failed boot too, so DJS self-disables instead of silently retrying forever.
- **The whole gate runs in init's namespace off Magisk** (not just the daemon start), so its storage checks see the same filesystem the user does.
- **One starter per boot.** The module's boot script and AccA's boot receiver both used to start DJS, and the daemon's own startup sweep let the racers kill each other's daemon (caught on the Mi A3: the daemon was dead after every boot). A per-boot lock now lets exactly one starter through.
- **Namespace-proof boot detection.** The gate now waits on the storage backing store (/data/media), which every mount namespace can see, instead of the FUSE path that an app-context root shell never sees; the FUSE path is still what the self-disable watchdog checks after the daemon starts, since that is what the user experiences.
- **Honest daemon verification.** "djsd started" is only logged after the daemon's runtime link actually appears (30s window, one retry); otherwise the log says it did not come up and the daemon's own output is kept in logs/djs-start.log. rc2 could log nothing when the start hung.

## [2.0.1-rc2] - 2026-07-02

Second candidate. A KernelSU-Next tester confirmed rc1's DJS fix was not enough (stuck first boot, then slow + internal storage missing), so the boot path was redesigned around one rule: nothing DJS does may ever hold up or wedge a boot.

### 🛠️ Fixed
- **DJS boot path rebuilt (KernelSU / KernelSU Next / APatch).** The module's boot script now returns instantly (the old one could hold the root manager's script stage for up to 10 minutes); the actual start waits for full boot AND visible internal storage in a fully detached helper; off Magisk the daemon starts strictly inside init's mount namespace or not at all (no more wrong-namespace fallback, which was the storage-missing mechanism); and the install-time cleanup hook no longer loops inside the boot stage either.
- **Boot watchdog.** DJS arms a marker early in boot and clears it once the boot completes. If a boot ever fails with DJS enabled, DJS disables itself on the next boot, so at most one bad boot is possible. If internal storage disappears right after the daemon starts, DJS stops itself and self-disables too. Everything is logged to /data/adb/vr25/djs-data/logs for bug reports.
- **KernelSU install warning.** Enabling DJS on KernelSU/APatch now explains the experimental status and the watchdog before installing.

### ✨ Added
- **Include pre-releases (Settings > Updates).** Off by default. Turn it on to be notified about beta and release-candidate builds of AccA; leave it off to stay on stable releases only. ACC module candidates are offered the same way once one ships with a higher version code.
- **Update prompts are version-aware.** The check now compares versions properly instead of by plain string match, so a release-candidate tester is no longer told an older stable build is "available" (that was an accidental downgrade prompt); the message also shows the version you are on. Stable users still only see stable releases.

### 🔄 Changed
- **ACC version picker is release-driven.** The install-specific-version list now shows this fork's published GitHub releases (newest first, as GitHub orders them) instead of raw git tags, and honours the new "Include pre-releases" setting: off shows stable releases only, on adds release candidates. The dead Master/Develop branch entries (they pointed at a branch this fork does not have) are gone.

## [2.0.1-rc1] - 2026-07-02

Release candidate driven by two field reports (Pixel 4a 5G boot films, KernelSU-Next DJS logs).

### 🛠️ Fixed
- **One honest battery status.** The dashboard now says "Draining to N%" while ACC or the firmware lowers the battery to your limit (some kernels report "Charging" the whole time - the 4a 5G film showed 10 minutes of it), "Bypass" (plugged in, battery idle) and "Standby" (unplugged). A missed status read no longer flips the card to a different format for one tick (the reported "Not Charging - Unknown" flicker).
- **DJS on KernelSU / KernelSU Next.** The bundled DJS no longer touches /sbin off Magisk, its daemon waits for boot AND decrypted storage before starting and runs in the global namespace (field report: boots-but-internal-storage-missing + slow), and app-driven installs of both DJS and ACC are relabeled the way KernelSU expects.

### 🔄 Changed
- Bundled ACC engine 6.5.1-rc1: Pixel 4a / 4a 5G / 5 class native-limit pair management with a fuel-gauge-verified hold (see the ACC changelog).

## [2.0.0] - 2026-06-30

🎉 Major release: **AMPS**, the universal switch finder, is built in; maintained by seyedehsanhadi; release builds are now signed with a stable key so the app updates in place, and the app tells you (and shows the changelog) when a new version is published.

### ✨ Added
- **Find my switch (AMPS).** A pre-test screen, every verified switch listed and badged (bypass, cut, drain), Apply and Lock with no re-test, and a clear "battery not ready" reason when the precondition gate stops a run.
- **In-app update notifications for both AccA and ACC,** each showing the changelog (release notes) before you update, with a link to download.
- AMPS logo on the About screen and a fork-maintainer credit.

### 🛠️ Fixed
- Valid temperature configs were rejected on save. Shutdown-temperature validation now matches ACC's own rule (at or above the max temperature, floor 40, ceiling 70), so setups like max 50 with shutdown 50 save correctly.
- Profile import dropped per-section enable flags, and a legacy profile with an invalid shutdown could block the next save. Both fixed.
- Undo and restore left some toggles stale, so a save could keep a value you thought you reverted. All toggles revert together now.
- Battery-idle priority defaulted off, flipping ACC's default; it now defaults on. New-profile capacity defaults match ACC. The charging-switch reader strips the trailing manual-lock marker safely.
- The bundled AMPS engine could recommend a battery-draining switch over a working firmware percent-limit when the current-sign reading was unreliable (seen on Pixel 4a, where ACC mis-reported the polarity); it now trusts a high-confidence live reading and defers to ACC's already-running native limit, so a working firmware limit is never demoted below a drain. The install-specific-ACC-version download now points at this fork.

### 🔄 Changed
- Bundled AMPS finder engine 7.0.0 (formerly acc-compat). The update check and ACC release URL point to this fork.

## [1.1.8-rc5] - 2026-06-24

A hotfix for battery-reading and inaccurate-warning issues, surfaced on Pixel, Tensor and OnePlus. Bundles ACC 6.4.1.

### 🛠️ Fixed
- **Robust current units on mixed-unit phones.** The bundled ACC now detects microamp-vs-milliamp from a charging current (sticky and persisted) instead of the voltage/charge scale, which mis-detected phones like the OnePlus 8 Pro (microvolt voltage and microamp-hour charge, but milliamp current) and would have shown their current about 1000 times too small.
- **Impossible dashboard current ("4687 mA").** The dashboard reads current from ACC's live state export, which was mislabelling a small idle current's unit and printing it about 1000 times too large (a 4.7 mA idle current shown as "4687 mA"). The unit is now anchored to ACC's calibrated scale, so it reads correctly. A display safety net also folds any out-of-range value.
- **"Discharging" while actually holding.** With the bundled ACC 6.4.1, a current polarity cached wrong (seen on Pixel and Android 17) self-corrects, so charging and idle are no longer reported as discharging.
- **Removed the "Charging may be broken" card.** It false-positived on native-limit and bypass devices, where "plugged but not charging below the pause level" is the firmware's normal hold, not a fault. The bundled ACC daemon already warns accurately, only on a real overcharge.
- **Accurate "charge did not stop" warning (bundled ACC).** Now fires only when the battery genuinely goes past the limit, not when a bypass switch holds it at 0 A with a "Charging" status (OnePlus `op_disable_charge`), and it points to "Find my charging switch" in the config editor.
- **Charging power control layout.** The "Max current" row no longer overflows into a one-character-per-line column; the label is shorter and the row is robust to long values.
- **Cool Down layout.** The third picker is now correctly labelled "Pause seconds" (it had inherited the temperature section "Resume temp" label), and the first label now reads "Start cooling down at %:" instead of a literal "%%".

### Bundled ACC 6.4.1
- Bundles ACC v2025.5.18-6.4.1-rc5 (versionCode 202505245): robust current-unit detection (sticky from a charging current, fixing the mixed-unit OnePlus regression), correct current scale (acca -i and the live state export), self-healing polarity, native firmware limit shows as a locked switch, and overcharge-only warnings.

### Notes
- Release candidate for testing. The signing key is unchanged, so updating from an older build still needs an uninstall first. **Reboot after installing** so Magisk activates the updated ACC daemon.

## [1.1.7] - 2026-06-24

The big stable since 1.1.6. A cleaner, more honest dashboard; accurate and complete charge limits; safer defaults; one tap to detect and lock the charging switch that works on your phone; and a built-in diagnostic. Bundles ACC 6.4 stable.

### Dashboard

- Status, signed current, plug state and the active switch are read from ACC's atomic state snapshot instead of scraped text, so while ACC holds your limit it correctly shows Discharging with the real negative current.
- A badge marks a switch you have locked manually (ACC will not auto-replace it).
- A health card warns when the daemon is running but charging is actually stuck, instead of staying silent.

### Find my charging switch

- One tap runs the bundled acc-compat tester on your phone with live progress, finds the switch that actually works, and offers Apply and Lock. It is written only after a live `acca -t` passes and the result matches your exact device.
- Copy or Share the full run log so you can paste it back to us for help.
- The screen stays awake during the run so it always finishes.
- Tester upgraded to v5.7: it ranks the most reliable switch first, proves a firmware percent limit before recommending it, and warns when a switch can behave differently per charger (for example fast USB PD versus wireless). It also ships as a standalone script you can run yourself in Termux or over ADB.

### Accurate and complete limits

- The temperature card's third value is now correctly Resume temp in degrees C, the temperature charging resumes at after a thermal pause, not the old and wrong "pause seconds".
- Shutdown temperature is editable (50 to 70 C, at least 3 C above pause).
- Millivolt capacity is supported: set Shutdown, Resume and Stop by voltage as well as percent, for voltage-precise limiting.
- Stop and Resume capacity are locked to 0 to 100 percent so the daemon never silently rewrites them.
- Every edit mirrors the daemon's own rules (temperature ordering, the 3 C cool-down gap, capacity and temperature bands). Save is blocked with the exact rule when a value would be reset. Max charging current is capped at 9999 mA (empty means native speed).

### Safer by default

- The charging switch is always locked. The "Automatically cycle through switches" toggle is gone, because it could let the battery briefly rise past your limit during background discovery. Upgrading from 1.1.6 re-locks an unlocked switch automatically.
- Stop asks first ("ACC will stop enforcing your charge limit until you start it again").
- Adding a switch waits for the cable to be plugged in, because the live test needs it.
- apply_on_boot and apply_on_plug show a root warning. Custom shell scripts are off by default; the 12 curated acca quick-actions still run.

### Diagnostics

- A built-in Deep Diagnostic captures one shareable report (device architecture, every charge-control node, the live config, and a passive 60-second observe), so an unsupported phone can be diagnosed from a single paste.

### KernelSU and APatch

- "Charge once to N percent", the ACC version readout and switch discovery use the absolute daemon path (they were silent no-ops on some setups). The init probe times out around 20 seconds with a recovery dialog instead of a stuck loading screen.

### Profiles

- Tapping a profile now applies and stays selected; apply failures surface as a toast instead of being swallowed.

### Bundled ACC 6.4 stable (device-tested)

Bundles ACC v2025.5.18-stable.6.4 (versionCode 202505240). Headline daemon improvements since 6.3.3:

- A boot-window guard cuts charging before the daemon starts, so a phone rebooted at its limit does not overcharge while the daemon waits for boot to complete.
- A switch you pin manually is never auto-replaced; ACC warns you to fix it instead, and your lock survives a reboot.
- A raised limit and auto-resume react within seconds, not up to about 120 seconds.
- Switches are locked only after a sustained hold, so the "passes a quick check then re-arms" overcharge family is rejected up front.
- A manual switch reset re-discovers reliably; fresh-install discovery is bounded to about 1 percent.
- Polarity-correct state, present-first plug detection, corrupt-config survival, and wider device support.

### Notes

- Existing settings are kept on upgrade; old auto-cycle profiles are re-saved as manual-lock.

## [1.1.7-rc3] - 2026-06-22

**One-tap "Apply & Lock" of a tester-verified charging switch, a charging-health warning, and KernelSU fixes.**

### Added
- **Verified switch → "Apply & Lock".** When the acc-compat tester has verified a switch for this exact device, pin it in one tap -- written only after a live `acca -t` passes on a plugged-in phone and the device fingerprint matches, so it can never pin a stale or wrong-device switch (pump / low-confidence = a suggestion that still requires the test, never auto-pinned). You can still add, test and switch to any other working switch from the editor as before.
- **Charging-health card** -- warns when the daemon is running but charging is actually broken; gated so it stays quiet during normal pause/cooldown.

### 🛠️ Fixed
- **KernelSU:** "charge once to N%" and the ACC version readout now use the absolute daemon path (were silent no-ops).
- **Capacity limits validate as a warning, not a wall** -- resume can't sit below shutdown, drain + disabled-shutdown warns of deep-discharge, but `shutdown_capacity=0` ("Disabled") stays saveable.
- **No more stuck loading screen** when the daemon can't initialize (KernelSU): the probe times out (~20 s) with a recovery dialog.

### Note
Pairs with ACC 6.4-rc3 (charging daemon unchanged from rc2); switch verification via the acc-compat v5.3 tester.

## [1.1.7-rc2] - 2026-06-13

**Fixes profiles not "landing" when you tap them, plus ACC 6.4-rc2 daemon fixes.**

### 🛠️ Fixed
- **Tapping a profile now actually applies and stays selected.** The selection used to bounce off and the dashboard showed "not selected". Two causes: the selection listener re-read the live ACC config mid-apply (race), and it required an exact config match that ACC's own write-normalization (resume-temp clamp, dropped control-file, unit coercion) can never satisfy. The app now trusts the chosen profile id.
- **Apply failures are now shown** (toast) instead of being silently swallowed.
- **Profiles and Dashboard share one config state**, so a profile applied from the list immediately reflects on the dashboard.

### Note
Bundles ACC v2025.5.18-6.4-rc1; the standalone ACC 6.4-rc2 daemon fixes (Pixel/Tensor instant resume, daemon survives "no working switch", more device layouts) ship as the ACC flashable zip.

## [1.1.7-rc1] - 2026-06-08

**Bundles ACC v2025.5.18-6.4-rc1 — a safety + diagnostics pre-release.**

ACC 6.4-rc1 hardens how a charging switch is chosen: it is locked only after charging stays stopped across several checks, so a switch that briefly stops then resumes (the cause of the earlier Xiaomi/MediaTek overcharge) is rejected up front — in both the automatic picker and the manual "Scan & lock". The safety watchdog also no longer goes blind on phones that pause by cutting the charger input. Battery info and the `--state` diagnostic now read the kernel battery event in one atomic snapshot, so charging status shows a real trust level instead of "Unknown", and Pixel/Tensor show their native charge-limit instead of an empty switch.

No change to how charging is controlled on a phone that already works; existing settings are kept.

## [1.1.6.6] - 2026-06-06

**Bundles ACC v2025.5.18-stable.6.3.3 — fixes an overcharge that 1.1.6.5 could cause on some MediaTek phones.**

1.1.6.5 switched MediaTek phones to a charging method (`current_cmd`) meant to give true idle. On some MediaTek kernels (e.g. the Xiaomi "klee" / HyperOS) that method passes ACC's quick check but doesn't actually stop charging at your limit — so the battery charged past it. This release rolls that back: ACC again prefers the method that reliably stops charging on these phones, and a one-time fix moves any affected phone back to it automatically. The earlier "won't charge until reboot" fix is kept (charging still resumes on its own).

Result on affected MediaTek phones: your limit is enforced again (no overcharge), and charging resumes without a reboot. (It may discharge slightly at the limit instead of holding perfectly flat — that's the trade for not overcharging; true idle is disabled where it isn't reliable.) Non-MediaTek phones are unaffected.

After updating, reinstall ACC from the app. If your phone was overcharging, it should now stop at your limit.

## [1.1.6.5] - 2026-06-06

**Bundles ACC v2025.5.18-stable.6.3.2 — MediaTek phones now truly IDLE at the limit (not discharge), and resume without a reboot.**

6.3.1's MediaTek fix didn't reach people who'd already been using AccA, for two reasons now fixed: on these chips, stopping the charge alone makes the phone run off the battery (so it drains), and true idle also needs the power path kept on so the charger feeds the phone while the battery sits flat — ACC now uses that combination first. And because updating never re-ran the charging-switch scan, MediaTek users stayed stuck on the old method; a one-time migration now clears that so the daemon re-picks the idle method automatically. Non-MediaTek phones are unaffected.

Also fixes a separate "won't charge until I reboot" bug on phones that use the *input-suspend* method (e.g. some MediaTek Motorola): suspending the input made the phone look unplugged to ACC, so it could never re-enable charging on its own. ACC now re-enables those reliably (it can't overcharge — the limit is still enforced). Other phones are unaffected.

After updating, if it still drains at the limit, open AccA and re-run the charging-switch scan once.

## [1.1.6.4] - 2026-06-06

**Bundles ACC v2025.5.18-stable.6.3.1** — a safe bug-fix batch (the 6.2/6.3 resume logic is unchanged).

The headline: **MediaTek phones now idle at the limit instead of disconnecting/draining, and resume on their own without a reboot.** ACC now tries the MediaTek battery-idle bypass (`current_cmd`) before `input_suspend`, so on MTK/Xiaomi (e.g. the "klee" Xiaomi) charging holds flat at your limit with the charger still feeding the phone, and resumes by itself when it drifts down — no more "it discharges at the limit" or "won't charge until I reboot." It's MediaTek-only and still verified on-device by ACC, so non-MediaTek phones are completely unaffected.

Also: the power-path stays on while paused (true idle, not discharge); a corrupted out-of-range charge limit in the config is clamped to a safe value (it could otherwise overcharge); and the charge scheduler is fixed for `08:xx`/`09:xx` times.

If your phone still discharges at the limit after updating, open AccA and re-run the charging-switch scan so it can pick the new idle method.

## [1.1.6.3] - 2026-06-05

**Hotfix — resume-after-limit now works on non-Pixel phones too (Motorola/Qualcomm/etc.).** Bundles **ACC v2025.5.18-stable.6.3 (202505214)**.

6.2 fixed Pixel/Tensor. This extends the same idea to generic devices. Some charging switches (`input_suspend`, `current_max=0`, `charging_enabled=0`) hold their "off" state across an unplug/replug, so after your battery hit the limit, plugging the charger back in did nothing until the battery fell to your resume level — or, on switches that latch, only a **reboot** restored charging (the reported Motorola symptom: it stops correctly at the limit, then will not resume on re-plug).

Now, on a genuine plug-in below your limit, ACC re-arms the switch immediately so charging resumes without a reboot. A shared plug-transition tracker drives both the Pixel path (`native_unlatch`) and the generic path (`generic_rearm`). It fires once per plug (no sawtooth), is skipped on the boot loop so it never fights *off mid charge*, and cannot overcharge (the limit logic is unchanged). Verified by a 13-case mock-sysfs harness.

If your phone reports inverted current (shows discharging while charging), that's a separate sign-detection issue still tracked for a later build; manual workaround remains `discharge_polarity=+` / `=-`.

## [1.1.6.2] - 2026-06-05

**Hotfix — Pixel/Tensor charging resumes again without a reboot.** Bundles **ACC v2025.5.18-stable.6.2 (202505213)**.

Since rc20, Pixel/Tensor used the native firmware limit (`charge_stop_level` + `charge_start_level`) and trusted the driver to resume on its own. But the Tensor `google,charger` driver *latches* "stopped" and ignores `charge_start_level` (an upstream Google bug — it reproduces even with no ACC installed). So once your battery hit the limit, plugging the charger back in did nothing and only a **reboot** restored charging.

The daemon now detects that latched state (plugged in, at/below your resume level, or a fresh plug-in below the limit, while the kernel still reports not-charging) and briefly pulses `charge_stop_level=100` — the only value that re-arms the charger — then immediately restores your real stop level, so there is no overshoot. It self-disables on phones whose firmware already resumes correctly, never fires inside the resume→limit idle band (no sawtooth), and can never overcharge (the limit is still enforced). **Non-Pixel devices are unaffected** (native mode only runs on `google,charger` hardware).

Known follow-up: inverted-current kernels (e.g. some Motorola/msm8953) can still mis-read charge/discharge state when `discharge_polarity` auto-detects wrong; that needs per-device logs and is tracked for 6.3. Manual workaround: set `discharge_polarity=+` or `=-` in AccA.

## [1.1.6.1] - 2026-06-03

**Stable.** Bundles the latest ACC daemon — **v2025.5.18-stable.6.1 (202505212)** — so a fresh install / in-app ACC install now sets up the hardened daemon directly.

### 🔄 Changed
- **Bundled ACC daemon → v2025.5.18-stable.6.1** with the all-phones auto-detection hardening: curated switch priority restored (order-preserving de-dup, was alphabetized), sign-agnostic + unit-scaled auto-lock (fixes inverted-current Motorola and milliamp Exynos false-locks; Pixel/Tensor discharge-hold preserved), input-node classification fix, broader charger-online detection (main-charger/oplus/glink), and a spurious-shutdown guard. App logic unchanged — the audit confirmed AccA needs no control-side changes for cross-device correctness.
- `Acc.bundledVersion` → 202505212 so the app offers/installs the new daemon.
- Version 1.1.6.1 (build 96).

## [1.1.6] - 2026-06-03

**Stable.** A reliability release: the DJS scheduler is now installable and safe, and the whole app went through a crash / ANR / robustness audit (107 findings, 98 confirmed) with every fix adversarially verified. Phone-tested. Bundles ACC v2025.5.18-stable.6 (versionCode 202505211).

### DJS (Daily Job Scheduler)
- Fixed the false **"DJS Installation Failed!"** dialog — install is now verified against `module.prop` (race-free) instead of an async daemon symlink; version detection has a fallback chain; a missing busybox is reported correctly instead of as a generic failure.
- Fixed two scheduler data-loss bugs: deleting/editing one schedule no longer wipes siblings (delimiter-anchored match), and a failed edit no longer destroys the schedule (snapshot + rollback).

### App-wide robustness
- **No more background crashes** — coroutine scopes carry an exception handler and every root/`Acc.instance` call in QS tiles, the widget, dialogs and activities is guarded.
- **No silent failures** — failed config applies, install errors and dropped schedules are logged/surfaced.
- **No ANRs** — blocking root shell moved off the main thread (Schedules tab, Settings DJS toggle, config editor, schedule dialog).
- **Correctness & safety** — charging status reads correctly on ACC 2025.x; injection-safe `djsc`/`acca` command builders; lifecycle-safe fragments (no leaks / stale-view writes); boot handles `QUICKBOOT_POWERON`; `BatteryDialogActivity` is no longer exported.

### Notes
- Two items deferred pending on-device validation: legacy-ACC (2020–2021) handler binary path, and a no-op foreground-service type at targetSdk 31.

### 🔄 Changed
- Version 1.1.6 (build 95). Bundled ACC daemon == v2025.5.18-stable.6.

## [1.1.6-rc28] - 2026-06-03

**Pre-release — stable candidate.** Fixes 3 regressions that an adversarial verification pass (13 agents, 99 checks) found in rc27's own fixes. All 70 rc27 fixes verified correct; these 3 are the only corrections.

### Fixed (regressions introduced in rc27)
- **Schedules tab highlight + Back key.** The rc27 async nav check returned `false`, so the tab never highlighted and Back exited the app instead of returning to Home. Now, once the DJS check passes, the tab is selected programmatically (re-entrant, guarded) so the highlight and Back behave correctly.
- **Schedules tab double-trigger.** Rapid taps could stack multiple DJS checks / install dialogs. Added an in-flight debounce.
- **Config-editor Undo button stuck disabled.** On the edit-current-config path, `onCreateOptionsMenu` ran before the async setup initialised the ViewModel, leaving Undo greyed out all session. `finishSetup()` now calls `invalidateOptionsMenu()` once the ViewModel exists.

### 🔄 Changed
- Version 1.1.6-rc28 (build 94). ACC daemon bundle unchanged (rc22).

## [1.1.6-rc27] - 2026-06-03

**Pre-release.** App-wide robustness remediation — 70 fixes from a full read-only audit (13 agents over 32 files), reviewed compile-clean. No daemon change (ACC bundle unchanged from rc24).

### Fixed — crashes
- Coroutine scopes (`ScopedAppActivity`, `ScopedFragment`, both QS tile services) now carry a `CoroutineExceptionHandler` that logs and swallows — an exception escaping any `launch{}` can no longer kill the process (this alone neutralised ~15 crash sites).
- Wrapped the unguarded `Acc.instance`/`Shell.su` calls in the QS tiles, the widget battery dialog, the config editor's switch/idle dialogs, `MainActivity` result/FAB handlers, and `BatteryInfoWidget` so a root-shell throw can't crash the app; `Acc.instance`'s `initAcc`/`isAccInstalled` are now exception-guarded.

### Fixed — silent failures
- A failed ACC config apply now logs and is surfaced via a new `applyFailed` signal instead of a bare `// TODO`.
- Every ACC `update*` partial-apply failure is logged at always-on level (was debug-only, invisible by default).
- Unsupported/empty current-max command no longer reports false success.
- DJS `list()` logs dropped/unparsable lines; ACC install failures and GitHub/JSON/Room-converter errors are logged (were `printStackTrace`-only or `catch(ignored)`).

### Fixed — parsing / handlers
- **`isBatteryCharging()` now reads ACC 2025.x lowercase status** (was always false while charging → wrong tile icon, skipped charging-switch test).
- Shell-quote-safe builders for on-boot/on-plug/charging-switch free text (injection-safe).
- Legacy reset-on-pause used the wrong binary (`acc-en`→`acc`); `v201903071` charging-switch no longer does a redundant root re-read; `CHARGE_DISABLE` regex corrected; boolean info fields parsed null-safe.

### Fixed — ANR / main thread
- Moved blocking root shell off the main thread: Schedules-tab nav, the DJS settings toggle, the config-editor `onCreate` (`runBlocking`→async), and the schedule dialog's default-config read.

### Fixed — lifecycle / leaks
- `onDestroyView` now nulls bindings and the dashboard/config/schedules/scriptes fragments guard post-async UI writes; `SharedPreferences` listeners and an `observeForever` are unregistered; dialogs guarded with `isAdded`/`isFinishing`; `DashboardConfigFragment` shares the activity-scoped `SharedViewModel`.

### Fixed — security / boot / misc
- DJS `djsc` args are POSIX single-quote escaped (**command-injection fix**); `BatteryDialogActivity` is no longer `exported`; `WidgetService` promotes to foreground correctly + `FOREGROUND_SERVICE` permission; `AccBootReceiver` handles `QUICKBOOT_POWERON`; `djsInstalledCache` no longer pins a failed probe; `ConfigTemperature` default aligned to the parser.

### Deferred (2, need a device)
- ACC version range `[202007220,202107280)` handler-binary-path routing; `WidgetService` `foregroundServiceType` (moot at targetSdk 31).

### 🔄 Changed
- Version 1.1.6-rc27 (build 93).

## [1.1.6-rc26] - 2026-06-02

**Pre-release.** Two P0 data-loss fixes in the DJS scheduler delete/edit path (found by a full read-only audit of the DJS pipeline).

### Fixed (DJS scheduler)
- **Deleting/editing one schedule no longer wipes its siblings.** `deleteById` matched `: accaScheduleId<id>` with no delimiter, and `djsc --delete` treats the pattern as a sed substring/regex — so deleting schedule **1** also deleted **10, 11, 100…**. The pattern is now anchored on the trailing `;` that the schedule line actually carries (`: accaScheduleId<id>;`).
- **A failed schedule edit no longer destroys the schedule.** `edit()` is delete-then-append, which is not atomic; if the re-append failed (busybox gone, DJS stopped, a quoting break) the original line was already deleted and lost. The edit now snapshots the current entry first and rolls it back on append failure, so a failed edit changes nothing (DJS and the local DB stay consistent).

### 🔄 Changed
- Version 1.1.6-rc26 (build 92). Builds on rc25's crash-proof, race-free DJS install pipeline.

## [1.1.6-rc25] - 2026-06-02

**Pre-release.** DJS (Daily Job Scheduler) installation pipeline made crash-proof and race-free — fixes the false "DJS Installation Failed!" dialog.

### Fixed (DJS install)
- **False "DJS Installation Failed!" eliminated.** The old code probed the DJS version via `/dev/.vr25/djs/djs-version`, a symlink created **asynchronously** by a fire-and-forget daemon (`service.sh` → `djs.sh`). The probe ran immediately after install and lost the race → null → thrown "DJS installation failed" even when every file installed correctly. Success is now verified by reading `versionCode` from `/data/adb/vr25/djs/module.prop`, which `install.sh` writes **synchronously** — race-free (mirrors how ACC version detection already works).
- **Version detection fallback chain** for DJS: runtime symlink → `djs-version` on PATH → `module.prop` (canonical). Each probe is independently exception-guarded, so a slow/absent daemon or a denied shell yields a clean null, never a crash.
- **Busybox-missing is no longer masked.** The installer exits `3` when busybox is absent; that case is now surfaced as its own outcome so the busybox prompt actually appears. `onBusyboxMissing()` was previously unreachable dead code (the version probe nulled the exit code first).
- **Equal/already-installed version** (installer early-exits `0` without restarting the daemon) now resolves as success via the `module.prop` read, instead of a false failure; the daemon is also nudged so runtime links exist.
- **No silent failures, no false success:** the install path returns an explicit `DjsInstallOutcome(success/busyboxMissing/result)`; a genuine failure always reaches the log-sharing dialog, a genuine success never reports failure, and vice-versa.
- **Crash-safe DJS helpers:** `isDjsInstalled`, `initDjs`, `uninstallDjs`, and the `Djs.instance` getter (now correct double-checked locking) wrap all root-shell calls so a libsu exception can never propagate.

### 🔄 Changed
- Version 1.1.6-rc25 (build 91). Bundled ACC daemon unchanged from rc24 (rc22 bundle).

## [1.1.6-rc13] - 2026-06-02

**Pre-release.** Bundles ACC rc13 — Tensor hard-pause finally applies + all-paths stop.

### Fixed (bundled daemon)
- The Tensor hard-pause config (`allowIdleAbovePcap=false`/`prioritizeBattIdleMode=no`) now **actually applies** on install (fresh marker — the prior one was stale, leaving it true).
- **All-paths group switch** cuts every Pixel charge path at once, so a single path can't keep charging.
- Every config param hardened against empty/garbage values.
- **Install (recovery/Magisk flash) applies it even if AccA's settings writes are stuck.**

### 🔄 Changed
- Bundled ACC daemon: **v2025.5.18-stable.6-rc13 (202505204)**.
- Version 1.1.6-rc13 (build 79).

## [1.1.6-rc12] - 2026-06-02

**Pre-release.** Bundles ACC rc12 — robustness for every phone.

### Fixed (bundled daemon)
- **Brick-safe switch probing** (#305/#308): a node that kernel-panics the device is blacklisted on next boot, never retried.
- **No 2-second phantom "Charging" on unplug.**
- **Deep sleep** (#293): no more constant CPU wakeups when unplugged/idle.
- **Install robustness** (#216/#223/#247): busybox + start-stop-daemon fallbacks, clearer errors — installs on more roots/ROMs/old Android.

### 🔄 Changed
- Bundled ACC daemon: **v2025.5.18-stable.6-rc12 (202505203)**.
- Version 1.1.6-rc12 (build 78).

## [1.1.6-rc11] - 2026-06-02

**Pre-release.** App-wide crash hardening + all-SoC stop switches.

### 🛠️ Fixed
- **Crash hardening sweep across the whole app** (26+ files): removed 16 `!!` force-unwraps, made 14 unchecked casts null-safe, guarded 13 RecyclerView index accesses (no more `IndexOutOfBounds` on stale clicks), wrapped 8 Room/JSON parses in try/catch (a corrupt row can't take down profiles/schedules), fixed a **launch crash** in `MainApplication.onCreate` (non-numeric pref), the **#258 `runBlocking` onCreate crash** in the config editor, moved root reads off the main thread (ANR), and guarded the quick-settings tiles + version probes. The app should no longer crash on malformed config, corrupt DB, stale UI, or missing root.
- **All-SoC stop switches** in the bundled daemon: generic `current→0` switches (the method proven on A16 Pixel) for Qualcomm/MediaTek/etc.

### 🔄 Changed
- Bundled ACC daemon: **v2025.5.18-stable.6-rc11 (202505202)**.
- Version 1.1.6-rc11 (build 77).

## [1.1.6-rc10] - 2026-06-02

**Pre-release.** Bundles ACC rc10 — Pixel/Tensor limit holds automatically. ✅

### Fixed (bundled daemon)
- **Confirmed working on Android-16 Pixel 9a.** `charge_stop_level` is dead on A16; the real stop is `usb/current_max …0` (cut input current). The daemon's idle-above-pcap path faked "stopped" while charging continued. On Pixel/Tensor it now defaults to **hard-pause** (`allow_idle_above_pcap=false` + `prioritize_batt_idle_mode=no`), so the current-verified auto-lock grabs the working current-limit switch on its own — no manual commands. **Tap the ACC-update prompt, then reboot.**

### 🔄 Changed
- Bundled ACC daemon: **v2025.5.18-stable.6-rc10 (202505201)**.
- Version 1.1.6-rc10 (build 76).

## [1.1.6-rc9] - 2026-06-02

**Pre-release.** Bundles ACC rc9 — auto-lock reworked, finally locks on Pixel.

### Fixed (bundled daemon)
- The limit never held because the switch the Pixel needs (`charge_stop_level`) works by **discharging**, but the daemon demanded a true-idle switch and only tried once. Reworked: it now judges a switch purely by current (idle **or** discharge = stopped), locks the first that truly cuts, and keeps trying until it does. **Tap the ACC-update prompt, then reboot.**

### 🔄 Changed
- Bundled ACC daemon: **v2025.5.18-stable.6-rc9 (202505200)**.
- Version 1.1.6-rc9 (build 75).

## [1.1.6-rc8] - 2026-06-02

**Pre-release.** Bundles ACC rc8 — THE fix for stop-then-reset.

### Fixed (bundled daemon)
- The limit stopped charging but kept restarting. Root cause: the switch-verification checked the *absolute* current, so a switch that works by **discharging** (negative current) was rejected as "still flowing" → the daemon never locked it → re-probed and restarted. Now it rejects a switch only if it's still *charging* (positive current); discharging/idle = stopped = locked + held. **Tap the ACC-update prompt, then reboot.**

### 🔄 Changed
- Bundled ACC daemon: **v2025.5.18-stable.6-rc8 (202505199)**.
- Version 1.1.6-rc8 (build 74).

## [1.1.6-rc7] - 2026-06-02

**Pre-release.** Bundles ACC rc7 — limit now HOLDS (no more stop-then-reset).

### Fixed (bundled daemon)
- It stopped at the limit then started charging again ~1% later. Cause: auto-mode re-probes switches every 35 loops, which toggled charging back on, because the working switch was never locked. The daemon now **locks the switch once it's current-verified** and stops re-probing → it holds. **Tap the ACC-update prompt, then reboot** so the rc7 daemon runs.

### 🔄 Changed
- Bundled ACC daemon: **v2025.5.18-stable.6-rc7 (202505198)**.
- Version 1.1.6-rc7 (build 73).

## [1.1.6-rc6] - 2026-06-02

**Pre-release.** Bundles ACC rc6 — Pixel/Tensor limit now holds.

### Fixed (bundled daemon)
- On Pixel/Tensor the limit was passed because auto-mode hit the `charging_state` trap first (reports stopped while still charging) and locked nothing. The daemon now tries `charge_stop_level` **first**, offering both the stable (`100 pcap`) and the 2022/2023 (`100 5`) drivings, and keeps whichever actually drops the current. **Reboot after install** so the new daemon runs.

### 🔄 Changed
- Bundled ACC daemon: **v2025.5.18-stable.6-rc6 (202505197)**.
- Version 1.1.6-rc6 (build 72).

## [1.1.6-rc5] - 2026-06-02

**Pre-release.** Bundles ACC rc5 — fixes charging overshooting the limit.

### Fixed (bundled daemon)
- The limit could be **surpassed** (e.g. set 30%, kept charging past it) because the daemon's switch auto-lock trusted *status* alone, and the Pixel/Tensor `charging_state` node reports "stopped" while current keeps flowing. The daemon now **verifies the current actually drops** before accepting a switch, so it locks one that truly cuts (`charge_stop_level`). Works on any SoC; lenient on mA kernels.

### 🔄 Changed
- Bundled ACC daemon: **v2025.5.18-stable.6-rc5 (202505196)**.
- Version 1.1.6-rc5 (build 71).

## [1.1.6-rc4] - 2026-06-02

**Pre-release.** Bundles ACC rc4 — smart sensing for every SoC.

### Added (bundled daemon)
- `acca --state` (the "Show ACC state" script) now reports **live measured sensing on any SoC**: `plugged`, `currentUnits` (µA/mA auto-detected), `polarity`, and `switch.measuredClass` (bypass / idle / charging / discharging from plug + current). Not Tensor-only.

### 🔄 Changed
- Bundled ACC daemon: **v2025.5.18-stable.6-rc4 (202505195)**.
- Version 1.1.6-rc4 (build 70).

## [1.1.6-rc3] - 2026-06-02

**Pre-release.** Bundles ACC rc2 — fixes the `acca --state` export bugs found on a Pixel 9a.

### Fixed (in the bundled daemon)
- The "Show ACC state" script now shows the **correct acc version** (was empty), a **derived charging status** (was "unknown" on the front-end path), and an **always-fresh snapshot** (was frozen on repeated runs).

### 🔄 Changed
- Bundled ACC daemon: **v2025.5.18-stable.6-rc2 (versionCode 202505194)**.
- Version 1.1.6-rc3 (build 69).

## [1.1.6-rc2] - 2026-06-02

**Pre-release.** AccA can now ask ACC for its state export.

### Added
- **"Show ACC state (--state)"** one-tap script (Scripts tab) — runs `acca --state` and shows ACC's live machine-readable JSON snapshot (level, signed current, status, config-as-ACC-holds-it, locked switch). First consumer of the rc1 state export; the in-app diagnostics view that parses it (closed-loop confirm + warnings) is the next increment.

## [1.1.6-rc1] - 2026-06-02

**Pre-release.** Bundles ACC RC1 (the state-export keystone) so it can be tested through the app installer. No AccA app-behavior change yet.

### 🔄 Changed
- Bundled ACC daemon: **v2025.5.18-stable.6-rc1 (versionCode 202505193)** — adds `acca --state` (alias `acc -j`), a machine-readable JSON snapshot of ACC's actual state that the upcoming control-bus + diagnostics rebuild reads back. Additive — charging behavior unchanged.
- Version 1.1.6-rc1 (build 67).

## [1.1.5] - 2026-06-01

Temperature band fix and an idle-above-limit toggle.

### 🛠️ Fixed
- **Default temperature band corrected to cooldown 45 °C < max 50 °C.** The 1.1.4 "lower max to 45 °C" change set max_temp equal to cooldown_temp (the config read `45 45 40 55`). cooldown_temp is where the gentle cooldown cycle starts and max_temp is the hard pause — with both at 45 the cooldown cycle is dead (it breaks the instant it would start). Restored ACC's proven 50 °C max with a 5 °C cooldown gap. The bundled daemon repairs an already-collapsed `45 45` config once, automatically.
- **Config parser fallbacks now use ACC's real defaults** (cooldown 45 / max 50 / resume 40), not the legacy 90/95 placeholders. A config missing `max_temp` no longer loads as 95 °C — which would have meant no thermal pause at all.

### Added
- **Idle above limit toggle.** Two one-tap scripts under Scripts — "Idle above limit: ON (default)" and "OFF" — flip ACC's `allow_idle_above_pcap`. ON (the default) lets the battery rest at your charge limit; OFF cycles down to the resume level instead, for forever-plugged 40–60 % setups.

### 🔄 Changed
- Bundled ACC daemon: v2025.5.18-stable.5 (versionCode 202505192).
- Version 1.1.5 (build 66).

## [1.1.4] - 2026-05-31

Standalone ACC, a cooler default, and boot/profile housekeeping.

### 🔄 Changed
- **Uninstalling AccA no longer removes ACC.** ACC is a standalone module with its own persistent config; the app is just a front-end (a remote control). Previously an uninstall could delete the daemon on next boot — fixed, and cleaned up automatically on update.
- **Default max temperature lowered 50 → 45 °C** (heat above ~45 °C ages the cell faster). A limit you set yourself is left untouched.
- The boot receiver is now registered (justifies the boot permission; redundant with the module's own boot hook, so harmless), and a stale profile temperature default (legacy "90") is corrected.
- Bundled ACC daemon: v2025.5.18-stable.4 (versionCode 202505191).
- Version 1.1.4 (build 65).

## [1.1.3] - 2026-05-31

Fixes charging getting stuck below your range (e.g. frozen at 64 %).

### 🛠️ Fixed
- If the battery dropped below your range it could **freeze — not charging, not draining**. The charge-stop chip latches "stopped" at your limit, and the previous build's resume re-sent the limit value, which doesn't re-arm it. Resume now works the way 2022/2023 did: charge up to your limit, stop, drift down, charge again at your resume level — a normal cycle inside your range. A locked switch from an older build is upgraded automatically on update (no command).

### 🔄 Changed
- Bundled ACC daemon: v2025.5.18-stable.3 (versionCode 202505190).
- Version 1.1.3 (build 64).

## [1.1.2] - 2026-05-31

Fixes the battery draining to ~70 % instead of holding at your limit.

### 🛠️ Fixed
- The previous build could drain the battery down to your resume level (~70 %) instead of holding at your limit. That came from the discharge variant; it's been removed. Now charging stops at your limit and holds there — the only behavior. Existing installs migrate automatically on update (no command).

### 🔄 Changed
- Removed the "Scan & lock: discharge-cycle" script (no discharge variant anymore); the remaining one is just "Scan & lock charging switch".
- Bundled ACC daemon: v2025.5.18-stable.2 (versionCode 202505189).
- Version 1.1.2 (build 63).

## [1.1.1] - 2026-05-31

No setup needed — the corrected defaults now apply automatically on update.

### 🔄 Changed
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

### 🔄 Changed
- Bundled ACC daemon updated to v2025.5.18-dev-fix12 (versionCode 202505186).
- Version is now 1.0.56 (build 60).

## [1.0.55] - 2026-05-31

The cap now stops exactly at your limit (no overshoot), and you choose hold vs cycle.

### 🛠️ Fixed
- **No more overshoot.** The charge limit is driven to your *target* level on both the charge and the pause side, so charging stops exactly at your limit (e.g. 75%), never above. The old behaviour told the firmware to "charge to 100%" then interrupt, which sailed past the limit (the 75→77 you saw).

### 🔄 Changed
- **Default = hold at the upper limit (battery-idle).** With "prioritize battery idle mode" on (the default), the battery is parked at your upper limit.
- **Idle off = discharge-cycle.** Turn "prioritize battery idle mode" off and the battery discharges to your lower limit (resume_capacity), then recharges to the upper limit, and repeats — the recharge still stops exactly at the upper limit.
- Removed the old `charge_stop_level 100 5` / `100 battery/capacity` variants (they recharged toward 100% and overshot).
- Bundled ACC daemon updated to v2025.5.18-dev-fix11 (versionCode 202505185).
- Version is now 1.0.55 (build 59).

## [1.0.54] - 2026-05-31

Settings apply instantly, and a scan can no longer leave charging uncapped.

### 🛠️ Fixed
- **Settings apply immediately.** Changing a limit, temperature, or switch now takes effect within ~1 second — the daemon wakes the moment the config changes and re-reads it live, instead of waiting out the full loop delay. No daemon restart, no app freeze.
- **A scan no longer kills the daemon.** "Scan & fix charging switch" restarts the daemon when it finishes; that restart ran the daemon *inside* the one-shot scan script, so it died when the script exited — leaving charging uncapped (the daemon showing "stopped" after a scan). The daemon now launches detached and stays running, and the scan verifies it came back, warning if it didn't.
- **Scanner evaluates every switch.** It re-arms charging between tests, so a switch tested right after a stopping one (like the precise `pcap` flat-hold variant) is no longer skipped as "not charging".

### 🔄 Changed
- Bundled ACC daemon updated to v2025.5.18-dev-fix10 (versionCode 202505184).
- Version is now 1.0.54 (build 58).

## [1.0.53] - 2026-05-31

Final hardening pass — full software coverage of the charge-control safety paths.

### 🛠️ Fixed
- Full fail-safe coverage: every capacity limit check (pause, resume, cooldown, shutdown, idle-reassert) now treats an empty or malformed value as the safe outcome, so no bad config can make a check error out and skip a stop. Previously only two of seven were guarded.
- Flat-hold extended to the `/proc/driver/charger_limit` charge-limit node (in addition to the Google `charge_stop_level` node), so more chipsets hold the cap flat with no overshoot.
- Breach watchdog: if the battery is at/above your limit and charging still hasn't stopped, you now get a warning notification instead of it failing silently. It clears itself once charging stops.

### 🔄 Changed
- Bundled ACC daemon updated to v2025.5.18-dev-fix9 (versionCode 202505183).
- Version is now 1.0.53 (build 57).

## [1.0.52] - 2026-05-31

Safety hardening on top of 1.0.51.

### 🛠️ Fixed
- Fail-safe against a malformed (non-numeric) pause/resume limit: the daemon now treats it as "pause now / don't resume" instead of letting the numeric check error out and silently skip the cap.
- A charging switch locked by "Scan & fix" (or by hand) that later stops working now auto-recovers — it re-selects a working switch and posts a warning — instead of silently letting the battery charge past the limit. The lock still prevents routine re-cycling while the switch works, so there's no churn in the normal case.

### 🔄 Changed
- Bundled ACC daemon updated to v2025.5.18-dev-fix8 (versionCode 202505182).
- Version is now 1.0.52 (build 56).

## [1.0.51] - 2026-05-31

Fixes the upper charge limit being overshot on Pixel/Tensor — the cap now holds flat.

### 🛠️ Fixed
- On Pixel/Tensor the only working charge switch is `charge_stop_level`, which is a charge *limit* ("charge to N %, then hold"), not an on/off switch. The bundled daemon (fix5/fix6) drove it as 100/5 — writing `5` to pause made the firmware *discharge*, then resume at the resume level and re-charge, overshooting the cap in a 70↔limit sawtooth (e.g. a 75 % limit drifting to 77 %). The bundled daemon (now v2025.5.18-dev-fix7) instead sets the limit node to your target level, so the firmware holds the battery flat at the cap: tight, no overshoot, and true battery-idle.
- The "Scan & fix charging switch" scanner now prefers idle/flat-hold switches over discharging ones, so it locks in the variant that actually holds the cap.

### 🔄 Changed
- Bundled ACC daemon updated to v2025.5.18-dev-fix7 (versionCode 202505181, so it installs over an existing 202505180 daemon instead of being skipped).
- Version is now 1.0.51 (build 55).

### After updating
- If you previously ran "Scan & fix charging switch" or locked a switch by hand, tap "Scan & fix charging switch" once more so the new flat-hold switch is selected. Or set it directly (75 = your pause limit): `acc -s s='/sys/devices/platform/google,charger/charge_stop_level 100 75 --'`

## [1.0.50] - 2026-05-31

A fast charging-switch scanner, built into the app.

### Added
- Two entries in the Scripts tab: "Scan charging switches (fast)" and "Scan & fix charging switch". They run a new scanner that polls the charging current ~3×/second and decides each switch in 1–4 s. The old "acc -t" waited up to 35 s per switch and AccA's timeout often killed it before it finished the list. The scan ranks the switches that actually stop your charger; the "& fix" one locks the best one in automatically.

### 🔄 Changed
- Bundled ACC daemon updated to v2025.5.18-dev-fix6 (ships the scanner).
- Version is now 1.0.50 (build 54).

## [1.0.48] - 2026-05-31

Reverts the 1.0.47 auto-restart, which made the app hang on every settings change.

### 🛠️ Fixed
- 1.0.47 restarted the ACC daemon after every config change. A restart re-detects every charging switch, which takes several seconds, so AccA froze (and sometimes ANR'd) each time you changed a setting. Reverted. The daemon already re-reads the limit and temperature live within a few seconds, so those still apply on their own; only a charging-switch change needs a restart, which you can do from the dashboard.

### 🔄 Changed
- Version is now 1.0.48 (build 52). Bundled ACC daemon stays v2025.5.18-dev-fix5.

## [1.0.47] - 2026-05-31

Settings take effect immediately now — no more restarting the daemon by hand.

### 🛠️ Fixed
- Changing a setting used to need a manual daemon restart to apply. AccA writes config through ACC's `acca` applet, which (unlike `acc --set`) never restarts the daemon, and the apply path didn't restart it either — so the charging switch and a few other settings sat dormant until you hit Restart. AccA now restarts the daemon right after applying a change, so what you set is what runs.
- Turning off "Prioritize battery idle mode" now tells ACC to actively prefer a clean on/off switch (it sends `no`, not just `false`). On Pixel/Tensor that's what stops the charge limit cycling on and off.
- Picking a specific charging switch on older ACC builds now locks it (appends ` --`) so the daemon holds it instead of auto-cycling. The current ACC build already did this.

### 🔄 Changed
- Version is now 1.0.47 (build 51). Bundled ACC daemon stays v2025.5.18-dev-fix5.

## [1.0.46] - 2026-05-31

A polish pass on the charging-limit fix, specifically for Pixel / Tensor devices.

### 🛠️ Fixed
- No more brief on/off charge bursts near the limit on Pixel. ACC was auto-selecting the Pixel `charge_stop_level` switch in its `battery/capacity` form, whose stop level tracks the *live* battery %, so the firmware kept nudging charging back on at the threshold and the daemon kept re-testing the switch. ACC now uses the fixed-threshold form of that switch, which holds the limit cleanly without the churn — the limit just holds, with the normal slow charge/pause cycle between resume and pause.

### 🔄 Changed
- Bundled ACC daemon updated to v2025.5.18-dev-fix5 (carries fix4 plus the Pixel switch fix above).
- Version is now 1.0.46 (build 50).

## [1.0.44] - 2026-05-31

Charging could pulse on and off near the limit instead of holding it. With the limit at 75% and the battery at 77%, charging ran for ~20 seconds, stopped, then started again a little later — over and over — while the level just sat above the limit. It looked like ACC was ignoring the config.

### 🛠️ Fixed
- ACC no longer turns charging back **on** while it is trying to pause above the limit. The charging-switch picker added in the previous ACC build, when it hit a switch the charger firmware keeps re-arming, briefly re-enabled charging on every pause cycle and then re-tested the switch on the next loop — that was the on/off pulsing. It now keeps charging off while probing, and picks a switch once per session instead of re-probing every loop, so the configured limit holds steady.
- The limit is now enforced fail-safe: if the pause or resume level is ever missing or unreadable, the daemon treats it as "pause now / do not resume" rather than letting the battery charge past the limit.

### 🔄 Changed
- Bundled ACC daemon updated to v2025.5.18-dev-fix4 (the charge-pulsing fix above).
- Version is now 1.0.44 (build 48).

## [1.0.43] - 2026-05-31

The switch test (acc -t) could leave charging uncontrolled. ACC stops its charge-control daemon while it tests switches, so during a test the configured stop level is not enforced, and if the test was killed (force-closing the app) the daemon could stay down, so the battery kept charging past the limit. The test also ran for minutes on one shared root shell, so every other command (daemon status, version, the diagnostics screen) hung until the app was force-closed.

### 🛠️ Fixed
- The daemon is now guaranteed to be running again after any switch test. After a test the app waits past ACC's own restart window and, if the daemon is still down, restarts it, so the configured stop level is always enforced and charging never stays uncontrolled.
- The switch test can no longer hang the app. It now runs under a hard time limit, so it can never hold the root shell open indefinitely and block daemon status, version, or the diagnostics screen.
- Running the "Test charging switches" script from the Scripts tab is bounded the same way and restores the daemon afterwards; every other script runs exactly as before.

### 🔄 Changed
- Version is now 1.0.43 (build 47).

## [1.0.42] - 2026-05-31

### Added
- Charge-activity capture in Diagnostics (menu: Capture charging). It samples the battery once a second for 20 seconds and records level, status, current and voltage over time, so a brief charge flicker that a single snapshot would miss actually shows up. It names the active charging switch, says whether that switch is a clean on/off one or a level one, and prints a verdict that calls out a switch fight when charging keeps flipping on and off near the stop level. The capture is started by hand and runs for a fixed 20 seconds, so it adds no background drain, and it only reads, never changes charging.

### 🔄 Changed
- Version is now 1.0.42 (build 46).

## [1.0.41] - 2026-05-31

### 🔄 Changed
- Reworked the Logs screen into a Diagnostics report. It used to stream ACC's raw shell execution trace one line at a time, and you could only copy a single line at a time. Now it gathers one readable report: app, device, Android, kernel, ACC version, daemon status, battery, the active config, detected charging switches, and the tail of the daemon log. The text is selectable with Copy, Share, and Refresh buttons, and the full log bundle (dmesg, logcat, config, switch maps) is still one tap away under the menu for deep bug reports.
- The report is gathered once in the background instead of polling continuously, so the screen no longer keeps the CPU busy.
- Version is now 1.0.41 (build 45).

## [1.0.40] - 2026-05-31

### 🛠️ Fixed
- The "Automatically cycle through switches" state could read wrong. The check for a manual switch looked at the whole config, so an apply_on_boot or apply_on_plug command ending in " --" flipped the flag off by mistake. It now reads the charging-switch line only.
- Battery idle support is detected more loosely, so a reworded ACC output line no longer quietly disables the prioritize-idle option.

### 🔄 Changed
- Version is now 1.0.40 (build 44).

## [1.0.39] - 2026-05-31

### 🛠️ Fixed
- Battery health no longer reads "Unknown" on ACC 2025.x. ACC stopped printing a health field, so the app now reads it from the kernel directly and shows Good, Overheat, Cold and the rest again. On a phone without that kernel node, health stays Unknown and everything else keeps working.

### 🔄 Changed
- Version is now 1.0.39 (build 43).

## [1.0.38] - 2026-05-31

A round of cleanup after reading the whole app against the ACC engine it drives. The headline: AccA no longer asks for access to your photos and media, and the config editor stops showing empty quotes and wrong toggle states.

### Removed
- The "Allow AccA to access photos, videos, music, and audio" prompt is gone. The app never read media; the permission was left over from an old storage approach. Profile export and sharing already work through the share sheet and a private file provider, so nothing needs it.

### 🛠️ Fixed
- The accd quick-settings tile now refreshes. It was waiting on a storage permission the app doesn't even declare, so the check always failed and the tile sat stale.
- Charging switch, apply_on_boot and apply_on_plug no longer show a literal `""`. ACC wraps these values in quotes; the app now unwraps them, so an unset switch reads "Automatic" again.
- The Apply on Boot / Apply on Plug toggles reflect whether a command is actually set instead of always showing on, and the plug row is labelled `apply_on_plug` to match ACC's real key.
- Reading the ACC config can no longer crash on any ACC version. Every older parser fell back to safe defaults the way the current one already did, instead of force-unwrapping a missing or empty field.

### 🔄 Changed
- Version is now 1.0.38 (build 42).

## [1.0.37] - 2026-05-31

The Battery card was showing nothing real on ACC 2025.x — capacity stuck at -1%, voltage at 0.000 V, everything else blank or Unknown. This release reads the battery again.

### 🛠️ Fixed
- Battery info now parses ACC 2025.x output. ACC rewrote what `acca -i` prints: the old `CAPACITY=23`, `VOLTAGE_NOW=4100000` style became lowercase `level 23`, `voltage_now 4.10`, and so on. AccA only knew the old style, so every field fell back to its empty default and the dashboard looked dead. It now reads both. Older ACC still works because the old format is tried first.
- Voltage no longer shows as 0.004 V. AccA assumed the raw value was always millivolts and divided by 1000; on a build that already reports volts that turned 4.1 into 0.004. The scale is now picked from the value's magnitude, so it reads right whatever ACC sends.

### 🔄 Changed
- Version is now 1.0.37 (build 41).

## [1.0.36] - 2026-05-31

This fork's first bug-fix release. The main reason it exists: AccA crashed on ACC 2025.x, and now it doesn't.

### 🛠️ Fixed
- Reading the charging config no longer crashes on ACC 2025.x. ACC renamed the `max_temp_pause` setting to `resume_temp`; older AccA force-unwrapped that field while parsing and threw a NullPointerException the moment it met the new config. The parser now reads every field safely and falls back to ACC's own defaults when a key is missing, renamed, or blank. Both the old and new key names are accepted.
- The quick-settings profile tile survives an empty profile list. Tapping it with nothing saved used to index past the end of the list and crash; now it does nothing.
- Boot no longer risks an ANR. The boot receiver ran root shell calls on the main thread, which could hang the system broadcast. That work moved to a background thread.

### 🔄 Changed
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
