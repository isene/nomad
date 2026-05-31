<div align="center">

<img src="src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="100" height="100">

# amardice

![version](https://img.shields.io/badge/version-0.1.3-3ddc84) ![platform](https://img.shields.io/badge/platform-Android-3ddc84) ![shell](https://img.shields.io/badge/shell-Kotlin%20%2F%20Compose-7f52ff) ![core](https://img.shields.io/badge/core-Rust%20%2F%20UniFFI-f74c00) ![license](https://img.shields.io/badge/license-Unlicense-green) ![Stay Amazing](https://img.shields.io/badge/Stay-Amazing-important)

An O6 dice roller for the [Amar RPG](https://d6gaming.org) — part of the [nomad](../../) mobile suite.

</div>

`com.isene.amardice` · pairs with [amar](https://github.com/isene/amar)

## What it does

Four buttons for Amar's open-ended **O6** d6 system:

- **D6** — a plain six-sided die
- **Skill roll** — O6 with the general skill crit/fumble tables
- **Combat roll** — O6 with the combat crit/fumble tables
- **Fear roll** — O6 + Mental Fortitude vs a Fear DR, with the graded effect
  (miss-by 1 → −1 this round … 5+ → flee; fumble → heart attack)

The result card colour-codes Critical / Fumble / success, shows the open-roll
die trail, and surfaces the recursive "roll twice ±1 mark" trigger. Mental
Fortitude and Fear DR are remembered between rolls.

The O6 cascade and both crit/fumble tables are ported verbatim from the desktop
[amar](https://github.com/isene/amar) TUI into the Rust core
(`core/src/amardice.rs`), so the phone, the TUI, and the
[d6gaming.org](https://d6gaming.org) wiki all agree.

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_NDK_HOME="$HOME/.android-sdk/ndk/27.2.12479018"
./gradlew :apps:amardice:assembleRelease
```

APK → `apps/amardice/build/outputs/apk/release/`. Sync and sideload.

## License

[Unlicense](https://unlicense.org/) — public domain. Part of [nomad](../../) · [isene.org/nomad](https://isene.org/nomad/)
