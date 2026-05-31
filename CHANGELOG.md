# Changelog

Notable changes to this fork. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); version numbers match the app's own versionName.

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

[1.0.36]: https://github.com/seyedehsanhadi/AccA/releases/tag/v1.0.36
