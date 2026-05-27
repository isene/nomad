# nomad

Mobile monorepo for the Fe₂O₃ suite. Rust core, Kotlin shells, one Cargo
workspace and one Gradle multi-project.

## Architecture

One shared Rust crate (`core/`, crate name `fe2o3-mobile-core`) holds the
reusable logic: data models, persistence, parsing, sync, crypto, search.
Exposed to Kotlin through [UniFFI](https://mozilla.github.io/uniffi-rs/),
which auto-generates the bindings. No hand-rolled JNI.

Each app is a thin Kotlin shell under `apps/<name>/`:

- **Compose** for screens
- **Glance** for home-screen widgets
- **WorkManager** for any background work, respecting Doze and App Standby

The Kotlin shell calls into the Rust core through the generated bindings.
Anything CPU-bound, data-heavy, or shared between apps belongs in Rust.

See [`CLAUDE.md`](./CLAUDE.md) for the design hierarchy and per-app notes.

## Layout

```
nomad/
├── core/                       fe2o3-mobile-core (Rust, UniFFI)
│   ├── Cargo.toml
│   └── src/
├── apps/
│   └── tasks/                  com.isene.tasks (Kotlin shell + Glance widget)
│       └── src/
├── Cargo.toml                  workspace root
├── settings.gradle.kts         Gradle multi-project root
├── gradle/libs.versions.toml   shared Kotlin/Android versions catalog
└── README.md
```

## Apps

| App | applicationId | Replaces | Status |
|---|---|---|---|
| `tasks` | `com.isene.tasks` | [isene/tasks](https://github.com/isene/tasks) v0.3.0 | scaffolding |

## Build

```bash
# Rust core (host, for tests)
PATH="/usr/bin:$PATH" cargo test -p fe2o3-mobile-core

# Android APKs (per app)
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :apps:tasks:assembleRelease
```

Output APK lands in `apps/tasks/build/outputs/apk/release/`.

## Sync to phone

The `tasks` app stays compatible with the existing Syncthing flow: phone-side
Syncthing-Fork mirrors `~/.tasks/` and the app picks `todo.hl` via Android's
Storage Access Framework. Sideload the signed APK from the synced folder.

## License

[Unlicense](https://unlicense.org/) — public domain.
