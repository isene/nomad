<div align="center">

<img src="src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="100" height="100">

# hyperlist

![version](https://img.shields.io/badge/version-0.1.1-3ddc84) ![platform](https://img.shields.io/badge/platform-Android-3ddc84) ![shell](https://img.shields.io/badge/shell-Kotlin%20%2F%20Compose-7f52ff) ![core](https://img.shields.io/badge/core-Rust%20%2F%20UniFFI-f74c00) ![license](https://img.shields.io/badge/license-Unlicense-green) ![Stay Amazing](https://img.shields.io/badge/Stay-Amazing-important)

A general [HyperList](https://isene.org/hyperlist/) editor for the phone — part of the [nomad](../../) mobile suite.

</div>

`com.isene.hyperlist` · pairs with [scribe](https://github.com/isene/scribe)

## What it does

Open and edit any `.hl` file with full HyperList fidelity, mirroring scribe's
HyperList handling on the laptop. Where `tasks` is the fixed 2-level todo,
`hyperlist` is the open-ended outliner.

- **Full syntax highlighting** — operators, qualifiers, references, properties,
  substitutions, tags, comments — a hand-port of the [fe2o3-highlight](https://github.com/isene/highlight) HyperList lexer
- Per-line structured editing model
- Fold / unfold; collapse a subtree to one line
- **Drag-reorder across depth** — move a collapsed item and its children as one
- Auto-renumber numbered siblings
- Path-aware reference resolution (`<Item/Sub/Identifier>`)
- SAF file access to the synced folder

Parser, serializer, highlighter, and transforms live in the Rust core
(`core/src/hyperlist_doc.rs`, `hyperlist_hl.rs`); Kotlin is the Compose shell.

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_NDK_HOME="$HOME/.android-sdk/ndk/27.2.12479018"
./gradlew :apps:hyperlist:assembleRelease
```

APK → `apps/hyperlist/build/outputs/apk/release/`. Sync and sideload.

## License

[Unlicense](https://unlicense.org/) — public domain. Part of [nomad](../../) · [isene.org/nomad](https://isene.org/nomad/)
