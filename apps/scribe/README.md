<div align="center">

<img src="src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="100" height="100">

# scribe

![version](https://img.shields.io/badge/version-0.1.1-3ddc84) ![platform](https://img.shields.io/badge/platform-Android-3ddc84) ![shell](https://img.shields.io/badge/shell-Kotlin%20%2F%20Compose-7f52ff) ![license](https://img.shields.io/badge/license-Unlicense-green) ![Stay Amazing](https://img.shields.io/badge/Stay-Amazing-important)

A distraction-free notes pad for the phone — the touch companion to the Fe₂O₃ [scribe](https://github.com/isene/scribe) editor. Part of the [nomad](../../) mobile suite.

</div>

`com.isene.scribe` · pairs with [scribe](https://github.com/isene/scribe)

## What it does

Point it at a notes folder (Syncthing-shared with your writing tree) and
edit, no chrome in the way.

- Lists the `.md` / `.hl` / `.txt` files in the folder, newest first
- Full-screen monospace editor — just the text
- Auto-saves on back and when the app is backgrounded
- New note with one tap (`+`)
- Files live in a SAF folder you grant once; nothing is copied, nothing
  leaves the device

Deliberately plain: pure Kotlin/Compose, no Rust core, no background work.
The same files open in desktop scribe over Syncthing.

## Setup

1. Tap the folder icon and choose your notes folder (the synced writing dir).
2. Tap a file to edit, or `+` to create one.

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew :apps:scribe:assembleRelease
```

APK → `apps/scribe/build/outputs/apk/release/`. Sync and sideload.

## License

[Unlicense](https://unlicense.org/) — public domain. Part of [nomad](../../) · [isene.org/nomad](https://isene.org/nomad/)
