<div align="center">

<img src="logo.svg" width="120" height="120" alt="OnePage logo">

# OnePage

**One screen. Your widgets, placed freely. Nothing else.**

![version](https://img.shields.io/badge/version-0.3.0-3ddc84) ![platform](https://img.shields.io/badge/platform-Android%2011%2B-3ddc84) ![shell](https://img.shields.io/badge/shell-Kotlin%20%2F%20Views-7f52ff) ![core](https://img.shields.io/badge/core-Rust%20%2F%20UniFFI-f74c00) ![idle](https://img.shields.io/badge/idle-zero%20cost-22c55e) ![license](https://img.shields.io/badge/license-Unlicense-green) ![Stay Amazing](https://img.shields.io/badge/Stay-Amazing-important)

A minimal home launcher — part of the [nomad](../../) mobile suite.

</div>

`com.isene.onepage`

## Why

Some launchers (e.g. SmartLauncher) omit `SYSTEM_ALERT_WINDOW` from their
manifest, so ColorOS never lists them under **Display over other apps** and the
Home button can't bring them back over a fullscreen app. There is no user-side
fix. OnePage declares the permission, walks you through granting it once, and
ships its own way home for when ColorOS refuses to cooperate at all.

It also does as close to nothing as a launcher can — no drawer, no icons, no
background work — so it costs essentially zero battery while it sits there being
your home screen.

## What it does

- **One home page.** No drawer, no paging, no dock, no search bar, no app icons
  (launch apps with your gesture tool of choice).
- **Free widget placement** — absolute `(x, y, w, h)`, no grid, overlap allowed.
- **Edit mode** — long-press empty space:
  - drag a widget to move it,
  - two-finger pinch to resize (width and height independently, no aspect lock),
  - long-press a widget to remove it,
  - **+ Widget** lists *every* installed widget (not just the system ones),
  - **Done** / Back / long-press-empty all exit and save.
- **Floating home button** — a small translucent pill, bottom-center over every
  app, that jumps straight to OnePage. It sidesteps the documented
  ColorOS/OxygenOS bug where the real Home button won't return to a third-party
  launcher from a fullscreen app.
- **First-run wizard requests every permission up front** — default-launcher
  role, overlay (the Home-button fix), battery-optimization exemption, and the
  manual ColorOS auto-start step — each verified with auto-advance, so there's
  no after-install settings trawl. Re-run any time: edit mode → **Setup**.
- **Wallpaper shows through** (system-composited, zero cost).

## Battery posture

The launcher is always alive, so the idle path is empty by design:

- **No services doing work, no timers, no polling, no observers.** The one
  foreground service runs zero code after start — it exists only to exempt the
  process from the OEM app-freeze (the Home-button root cause).
- **No drawing or layout when idle.** Widgets invalidate themselves on their own
  `RemoteViews` pushes; the surface draws borders only in edit mode.
- **Persistence is one atomic write** on edit-done / add / remove — never per
  drag-tick.
- The floating pill is one inert composited layer; it spends cycles only when
  tapped.

## Architecture

Mostly Android platform-surface code — a launcher hosts third-party widgets, it
doesn't produce one, so the usual nomad shape (Compose + Glance) doesn't fit:

- **Home surface**: a traditional `FrameLayout` (`HomeSurface`) holding
  `AppWidgetHostView` children. `AppWidgetHostView` is a `View`, so wrapping it
  in Compose buys nothing and adds recomposition cost an always-alive process
  shouldn't pay.
- **Compose**: only the one-shot first-run wizard (`WizardActivity`).
- **Rust core** (`core/src/onepage.rs`): owns the layout-file format
  (`Layout { version, widgets: [WidgetPos] }`, serde-json over UniFFI), so a
  desktop tool could read/write a layout later. Kotlin owns all I/O.

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_NDK_HOME="$HOME/.android-sdk/ndk/27.2.12479018"
./gradlew :apps:onepage:assembleRelease
```

APK → `apps/onepage/build/outputs/apk/release/`. Sync and sideload.

## First run

1. Install and open OnePage once.
2. The wizard grants: default-home role → overlay → battery exemption → (manual)
   ColorOS auto-start. Follow it through; everything is verified and auto-advances.
3. Long-press the wallpaper → **+ Widget** → pick one → it appears centered.
   Drag to move, pinch to resize, **Done** to save.
4. The floating pill takes you home from any app.

> On ColorOS/OxygenOS the hardware/3-button Home action into third-party
> launchers is an OEM bug with no real fix. The floating pill is the reliable
> way home; gesture navigation + locking OnePage's process help too.

## License

[Unlicense](https://unlicense.org/) — public domain. Part of [nomad](../../) · [isene.org/nomad](https://isene.org/nomad/)
