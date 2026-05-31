<div align="center">

<img src="src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="100" height="100">

# astro

![version](https://img.shields.io/badge/version-0.1.3-3ddc84) ![platform](https://img.shields.io/badge/platform-Android-3ddc84) ![shell](https://img.shields.io/badge/shell-Kotlin%20%2F%20Compose-7f52ff) ![core](https://img.shields.io/badge/core-Rust%20%2F%20UniFFI-f74c00) ![license](https://img.shields.io/badge/license-Unlicense-green) ![Stay Amazing](https://img.shields.io/badge/Stay-Amazing-important)

Amateur-astronomy companion — full parity with the Fe₂O₃ [astro](https://github.com/isene/astro) TUI. Part of the [nomad](../../) mobile suite.

</div>

`com.isene.astro` · pairs with [astro](https://github.com/isene/astro)

## What it does

Everything the desktop astro panel gives you, in your pocket at the telescope.

**Sky mode**
- Ephemeris table — rise / transit / set, with up-now highlighting
- Moon phase, sun & moon times, tonight summary, visible planets
- met.no weather + per-day **observing-condition** colours (good / fair / poor)
- in-the-sky.org events, NASA APOD, and a generated starchart (Coil-loaded)
- GPS location (`LocationManager`, no Google Play Services) + manual override

**Gear mode**
- Telescope / eyepiece / misc catalog with live optics maths
- Exit-pupil suitability bands; cross-mode "recommended eyepiece for tonight"
- `gear.json` is shared with desktop astro over [Syncthing](https://syncthing.net)
  (the desktop `~/.astro/gear.json` is a symlink into the synced folder)

Ephemeris wraps the dependency-free [orbit](https://github.com/isene/orbit)
crate; all parsing, optics, and condition logic live in the Rust core
(`core/src/astro/`). Network GETs stay in Kotlin (OkHttp) so the `.so` stays
free of TLS deps.

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_NDK_HOME="$HOME/.android-sdk/ndk/27.2.12479018"
./gradlew :apps:astro:assembleRelease
```

APK → `apps/astro/build/outputs/apk/release/`. Sync and sideload; grant the
location permission and pick the synced `gear.json` in Settings.

## License

[Unlicense](https://unlicense.org/) — public domain. Part of [nomad](../../) · [isene.org/nomad](https://isene.org/nomad/)
