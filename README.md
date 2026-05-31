# nomad — Fe₂O₃ on the phone

![Kotlin](https://img.shields.io/badge/shell-Kotlin%20%2F%20Compose-7f52ff) ![Rust](https://img.shields.io/badge/core-Rust%20%2F%20UniFFI-f74c00) ![Platform](https://img.shields.io/badge/platform-Android-3ddc84) ![License](https://img.shields.io/badge/license-Unlicense-green) ![Stay Amazing](https://img.shields.io/badge/Stay-Amazing-important)

The mobile half of the [Fe₂O₃ suite](https://github.com/isene/fe2o3). Each app
carries one Fe₂O₃ workflow off the laptop and onto an Android phone, sharing a
single Rust core and syncing data over [Syncthing](https://syncthing.net) — no
Google account, no cloud middleman.

**Landing page:** [isene.org/nomad](https://isene.org/nomad/)

One Cargo workspace and one Gradle multi-project in a single monorepo. The
interesting code lives in **Rust** (`core/`, crate `fe2o3-mobile-core`),
exposed to **Kotlin** through [UniFFI](https://mozilla.github.io/uniffi-rs/);
each app is a thin Compose shell. Anything CPU-bound, data-heavy, or shared
between apps belongs in the core, so the phone and the desktop tools compute
identically.

The reason these are public domain is not that you should install them as-is.
They are released for inspiration: clone the repo, fire up Claude Code, and
prompt the changes that fit *your* phone.

## The apps

| | App | Role | Pairs with |
|---|---|---|---|
| <img src="apps/tasks/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="40"> | [**tasks**](apps/tasks/) | HyperList todo editor + Glance home-screen widget for `~/.tasks/todo.hl` | [scribe](https://github.com/isene/scribe), [kastrup](https://github.com/isene/kastrup) |
| <img src="apps/hyperlist/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="40"> | [**hyperlist**](apps/hyperlist/) | General [HyperList](https://isene.org/hyperlist/) editor — full syntax highlighting, fold, drag-reorder across depth, auto-renumber | [scribe](https://github.com/isene/scribe) |
| <img src="apps/relay/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="40"> | [**relay**](apps/relay/) | Notification gateway: relays WhatsApp / Messenger / Instagram / SMS / Discord (and photos) to kastrup and fires replies — replaces the laptop's Marionette bridge | [kastrup](https://github.com/isene/kastrup) |
| <img src="apps/astro/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="40"> | [**astro**](apps/astro/) | Amateur-astronomy companion: ephemeris, met.no weather + observing conditions, in-the-sky events, APOD, starchart, and a telescope/eyepiece gear catalog | [astro](https://github.com/isene/astro) |
| <img src="apps/watchit/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="40"> | [**watchit**](apps/watchit/) | TMDB movie / series browser — top-rated & popular lists, wish/dump lists, posters, full detail, search-to-add | [watchit](https://github.com/isene/watchit) |
| <img src="apps/amardice/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="40"> | [**amardice**](apps/amardice/) | Amar RPG O6 dice roller: D6, skill, combat (crit/fumble tables), and fear rolls | [amar](https://github.com/isene/amar) |
| <img src="apps/xrpn/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="40"> | [**xrpn**](apps/xrpn/) | Pocket HP-41 RPN scientific calculator — full stack/registers/modes, multi-shift keypad, and a FOCAL program runner | [xrpn](https://github.com/isene/xrpn) |

Each ships as its own signed APK with its own launcher icon, sideloaded from a
Syncthing-synced folder.

## Architecture

```
nomad/
├── core/                       fe2o3-mobile-core (Rust, UniFFI)
│   ├── src/hyperlist*.rs        HyperList model, parser, highlighter
│   ├── src/astro/               ephemeris (orbit), weather, events, gear, images
│   ├── src/watchit/             TMDB models, parsers, filter/sort
│   ├── src/amardice.rs          O6 engine + crit/fumble/fear tables
│   └── src/xrpn/                RPN stack engine, formatter, FOCAL interpreter
├── apps/<name>/                 Kotlin/Compose shells (one Gradle module each)
├── Cargo.toml                   workspace root
├── settings.gradle.kts          Gradle multi-project root
└── gradle/libs.versions.toml    shared Android/Kotlin version catalog
```

- **Rust core + UniFFI** for all logic. Never hand-rolled JNI.
- **Compose** for screens, **Glance** for widgets, **WorkManager** for
  background work (respecting Doze and App Standby).
- **No business logic in Kotlin** that could live in the core.

Design hierarchy, build quirks, and per-app notes live in
[`CLAUDE.md`](./CLAUDE.md).

## Build

```bash
# Rust core — host tests (no emulator needed)
PATH="/usr/bin:$PATH" cargo test -p fe2o3-mobile-core

# An app's signed release APK
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_NDK_HOME="$HOME/.android-sdk/ndk/27.2.12479018"
./gradlew :apps:<name>:assembleRelease
```

cargo-ndk cross-compiles the core to all four Android ABIs; uniffi-bindgen
generates the Kotlin bindings at build time. The APK lands in
`apps/<name>/build/outputs/apk/release/`.

## Sync to phone

Build the signed APK, drop it (and any shared data files) into a
Syncthing-shared folder, and sideload on the phone. Per-app data —
`~/.tasks/todo.hl`, `~/.astro/gear.json`, the relay gateway dir,
`.xrpn` programs — rides the same Syncthing channel, so the phone and the
desktop Fe₂O₃ tools share one source of truth.

## License

[Unlicense](https://unlicense.org/) — public domain. Borrow or steal whatever
you want.

— [Geir Isene](https://isene.com)
