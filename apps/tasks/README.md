<div align="center">

<img src="src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="100" height="100">

# tasks

![version](https://img.shields.io/badge/version-0.4.3-3ddc84) ![platform](https://img.shields.io/badge/platform-Android-3ddc84) ![shell](https://img.shields.io/badge/shell-Kotlin%20%2F%20Compose-7f52ff) ![core](https://img.shields.io/badge/core-Rust%20%2F%20UniFFI-f74c00) ![license](https://img.shields.io/badge/license-Unlicense-green) ![Stay Amazing](https://img.shields.io/badge/Stay-Amazing-important)

Phone editor and home-screen widget for the HyperList todo list — part of the [nomad](../../) mobile suite.

</div>

`com.isene.tasks` · pairs with [scribe](https://github.com/isene/scribe) & [kastrup](https://github.com/isene/kastrup)

## What it does

Edits the same `~/.tasks/todo.hl` 2-level [HyperList](https://isene.org/hyperlist/)
that scribe edits on the laptop and kastrup's `z` triage appends to. The list
rides over [Syncthing](https://syncthing.net), so a change on either device
shows up on the other — no Google account, no cloud middleman.

- Two-level hyperlist (categories → items), parsed and serialized by the Rust core
- Add / edit / complete / delete items; categories collapse
- **Glance home-screen widget** showing the first items across categories
- Storage Access Framework: point it at the synced `~/.tasks/` folder
- About screen; Material 3; adaptive icon

The hyperlist parser, serializer, and transforms live in the shared Rust core
(`core/src/hyperlist.rs`); Kotlin owns SAF I/O, the Compose UI, and the widget.

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_NDK_HOME="$HOME/.android-sdk/ndk/27.2.12479018"
./gradlew :apps:tasks:assembleRelease
```

APK → `apps/tasks/build/outputs/apk/release/`. Drop it in the synced folder and sideload.

## License

[Unlicense](https://unlicense.org/) — public domain. Part of [nomad](../../) · [isene.org/nomad](https://isene.org/nomad/)
