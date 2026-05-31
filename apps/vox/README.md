<div align="center">

<img src="src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="100" height="100">

# vox

![version](https://img.shields.io/badge/version-0.1.1-3ddc84) ![platform](https://img.shields.io/badge/platform-Android-3ddc84) ![shell](https://img.shields.io/badge/shell-Kotlin%20%2F%20Compose-7f52ff) ![core](https://img.shields.io/badge/core-Rust%20%2F%20UniFFI-f74c00) ![license](https://img.shields.io/badge/license-Unlicense-green) ![Stay Amazing](https://img.shields.io/badge/Stay-Amazing-important)

Voice quick-capture — hold the phone, talk, file it. The pocket version of the laptop `Win+a` VTT. Part of the [nomad](../../) mobile suite.

</div>

`com.isene.vox` · pairs with your laptop VTT (`~/bin/vtt-toggle`)

## What it does

Open the app and it starts recording. Tap to stop, it transcribes through
OpenAI Whisper, then you pick where it lands.

- **Records on launch** — one tap to stop, closest thing to the laptop's
  global hotkey
- **Whisper transcription** (forced English, like the laptop VTT)
- **Editable transcript** before it's written — fix a word, then file it
- **Choose per capture**: **→ Tasks** appends under an `Inbox` category in
  your synced `todo.hl` (feeding scribe and kastrup's z-triage), **→ Notes**
  appends a timestamped entry to a notes file
- **No background work** — the mic and the network only run while you're
  capturing; nothing polls, nothing wakes

The `todo.hl` append reuses the Rust core's hyperlist parser/serializer
(`core/src/hyperlist.rs`), so a voice capture is byte-identical to a typed
one. Recording (MediaRecorder), the Whisper POST (OkHttp), and the SAF
writes stay in Kotlin.

## Setup

1. Open **Settings** (gear icon), paste your **OpenAI API key** (stored on
   the device only, never synced).
2. Pick your **Tasks file** (the synced `todo.hl`) and a **Notes file**.
3. Grant the microphone permission on first record.

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_NDK_HOME="$HOME/.android-sdk/ndk/27.2.12479018"
./gradlew :apps:vox:assembleRelease
```

APK → `apps/vox/build/outputs/apk/release/`. Sync and sideload.

## License

[Unlicense](https://unlicense.org/) — public domain. Part of [nomad](../../) · [isene.org/nomad](https://isene.org/nomad/)
