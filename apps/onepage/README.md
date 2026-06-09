<div align="center">

<img src="src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="100" height="100">

# OnePage

![version](https://img.shields.io/badge/version-0.1.0-3ddc84) ![platform](https://img.shields.io/badge/platform-Android-3ddc84) ![shell](https://img.shields.io/badge/shell-Kotlin%20%2F%20Views-7f52ff) ![core](https://img.shields.io/badge/core-Rust%20%2F%20UniFFI-f74c00) ![license](https://img.shields.io/badge/license-Unlicense-green) ![Stay Amazing](https://img.shields.io/badge/Stay-Amazing-important)

A minimal home launcher: one page, your widgets, nothing else — part of the [nomad](../../) mobile suite.

</div>

`com.isene.onepage`

## Why

Some launchers (e.g. SmartLauncher) omit `SYSTEM_ALERT_WINDOW` from their
manifest, so ColorOS never lists them under "Display over other apps" and the
Home button can't bring them back over a fullscreen app. There is no user-side
fix. OnePage declares the permission, walks you through granting it once, and
otherwise does as close to nothing as a launcher can.

## What it does

- **One home page.** No drawer, no paging, no dock, no search bar, no icons
  (apps launch via your gesture tool of choice).
- **Free widget placement** — absolute (x, y, w, h), no grid, overlap allowed.
  Long-press empty space → edit mode: drag to move, pinch to resize,
  long-press a widget for Resize/Remove, "+ Widget" to add.
- **Wallpaper shows through** (system-composited, zero cost).
- **First-run wizard requests every permission up front** — default-launcher
  role, overlay (the ColorOS Home-button fix), battery-optimization exemption,
  and the manual ColorOS auto-start step — each verified with auto-advance, so
  there is no after-install settings trawl. Re-run any time: edit mode → Setup.
- **True zero-cost idle.** No services, no timers, no polling, no observers.
  The launcher draws on interaction and widget pushes only; persistence is one
  atomic write on edit-done/add/remove.

Layout format lives in the Rust core (`core/src/onepage.rs`); the home surface
is traditional Views (`AppWidgetHost` children are Views; Compose is used only
for the one-shot wizard).

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_NDK_HOME="$HOME/.android-sdk/ndk/27.2.12479018"
./gradlew :apps:onepage:assembleRelease
```

APK → `apps/onepage/build/outputs/apk/release/`. Sync and sideload.

## License

[Unlicense](https://unlicense.org/) — public domain. Part of [nomad](../../) · [isene.org/nomad](https://isene.org/nomad/)
