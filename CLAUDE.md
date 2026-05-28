# Claude Code Instructions for nomad

This is the single mobile monorepo for the Fe₂O₃ suite. Rust core in
`core/`, Kotlin app shells under `apps/<name>/`. One CC session owns
everything in this tree. Treat it like the rest of the Fe₂O₃ family.

## Design hierarchy (READ FIRST, in priority order)

1. **No wasted CPU cycles.** On a phone this matters more than on a
   laptop. Every wake-up burns battery. Gate every feature so its code
   path is fully cold when not in use. Compare target state to last
   applied state before doing I/O. Skip work whose result is identical
   to what is already on disk or on screen.
2. **Lightning fast.** Cold launch under 300 ms. Compose recomposition
   bounded. Rust transforms return synchronously where possible. No
   network on the UI thread. No SQLite on the UI thread.
3. **More battery life.** Polling and timers are suspect. Prefer
   WorkManager with strict constraints. Prefer SAF `lastModified` to
   filesystem watchers (Android limits user-space `inotify`). Prefer
   one large coalesced write to many small ones.

When in doubt, measure. `adb shell dumpsys batterystats`, `cargo flamegraph`
on the host-side tests, `Tracecompose` for Compose recompositions.

## Architecture decisions

These come from the original mobile-architecture artifact. Do not drift.

- **Rust core + Kotlin shell, monorepo.** One Cargo workspace at the
  root, one Gradle multi-project at the root. Per-app Gradle modules
  under `apps/<name>/`.
- **UniFFI for the FFI boundary.** Never hand-roll JNI. If UniFFI cannot
  express a shape, redesign the shape.
- **Compose for screens. Glance for widgets. WorkManager for background.**
  Widgets render through `RemoteViews`, so all-Rust UI frameworks are
  rejected. Termux-style approaches are rejected. Super-apps are
  rejected.
- **Each app ships as its own APK** with its own launcher icon. Shared
  core is consumed via a Gradle dependency on the local `core` crate
  (cargo-ndk builds the per-ABI `.so` files; UniFFI generates the
  Kotlin bindings).

## Per-app responsibilities

### Rust core (`core/`, crate name `fe2o3-mobile-core`)

- Data models, parsers, serializers, transforms.
- Persistence (SQLite via `rusqlite` when needed). No Android APIs.
- Pure logic, immutable transforms where it fits Compose's diff model.
- Exposes a UniFFI surface. Bindings regenerate on every build.
- Tests run on the host (`cargo test`). No Android emulator needed.

### Kotlin shells (`apps/<name>/`)

- Compose UI for screens.
- Glance widgets that read from the Rust core through a thin read-only
  surface.
- WorkManager for background sync, respecting Doze and App Standby.
- Storage Access Framework, notifications, intents, share targets.
- Lifecycle plumbing.
- No business logic that could live in the core.

## Toolchain

- **JDK 17** (apt: `openjdk-17-jdk-headless`). System default JDK may be
  newer; gradle invokes JDK 17 explicitly via `JAVA_HOME`.
- **Android SDK** at `~/.android-sdk/` (hidden). Platform 35, build-tools 35.0.0.
- **Android NDK** 27.2.12479018 under `~/.android-sdk/ndk/`.
- **Rust** stable, 2021 edition. Targets: `aarch64-linux-android`,
  `armv7-linux-androideabi`, `x86_64-linux-android`, `i686-linux-android`.
- **cargo-ndk** 4.x for the cross-compile.
- **uniffi-bindgen** as a build-time helper (invoked from `core/build.rs`).

### Required env

```bash
export ANDROID_HOME="$HOME/.android-sdk"
export ANDROID_SDK_ROOT="$HOME/.android-sdk"
export ANDROID_NDK_HOME="$HOME/.android-sdk/ndk/27.2.12479018"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
```

### PATH-shadow note

`~/bin/cc` shadows the C compiler some Rust crates need (rusqlite, ring,
etc.). Always prefix `cargo` invocations with `PATH="/usr/bin:$PATH"`
when building the core. Same rule as the rest of Fe₂O₃.

## Per-app notes

### tasks (com.isene.tasks)

- Supplants the existing standalone `tasks` repo (v0.3.0). Same
  applicationId, same signing key at `~/.android/tasks-release.jks`,
  alias `tasks`, valid until 2053. Drop a `key.properties` next to
  `apps/tasks/build.gradle.kts` (gitignored).
- Backwards-compatible data: 2-level hyperlist in `~/.tasks/todo.hl`,
  synced via Syncthing (laptop) ↔ Syncthing-Fork (phone, F-Droid).
  Phone reads through SAF.
- The hyperlist parser, serializer, and transforms live in the Rust
  core (`core/src/hyperlist.rs`). Kotlin handles SAF I/O, Compose UI,
  and the Glance widget.
- Glance widget: shows the first ~10 items across categories, tap
  opens the app. No editing from the widget in v1 (keeps the widget
  side trivial; widgets that edit need a hosted Activity hop anyway).
- The old standalone repo at `/home/geir/Main/G/GIT-isene/tasks/`
  stays untouched until this app reaches parity and ships. Then we
  archive it.

## Anti-patterns (don't drift into these)

- Putting business logic in Kotlin because it's faster to prototype.
- Hand-written JNI when UniFFI would do.
- Pulling in heavy Compose dependencies for trivial features.
- A super-app with internal modes instead of separate APKs.
- Background polling instead of WorkManager-scheduled or push-driven sync.
- All-Rust UI experiments. Widgets require `RemoteViews`.
- Duplicating logic between core and a shell.

## What CC should default to

1. New reusable logic → Rust core, exposed via UniFFI.
2. New screen → Kotlin/Compose in the relevant app.
3. New widget → Glance in the relevant app, reading from the core.
4. If a task could go either side: prefer Rust for anything CPU-bound,
   data-heavy, or shared across apps. Prefer Kotlin only for genuine
   platform-surface work.
5. Keep the Kotlin shell boring. The interesting code lives in the core.

## Verification before claiming a feature done

1. `PATH="/usr/bin:$PATH" cargo test -p fe2o3-mobile-core` passes.
2. `./gradlew :apps:<name>:assembleRelease` succeeds.
3. APK installs in place over the previous version (no signature
   mismatch, no SAF URI loss).
4. Glance widget actually rebuilds and shows current data.
5. WorkManager constraints respect battery: no jobs fire at 100% drain
   in airplane mode.

Cargo build success and Gradle assemble success do not equal behavioural
correctness. Exercise the actual code path on the phone before tagging.
