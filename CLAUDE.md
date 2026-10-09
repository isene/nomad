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
- **Every app's launcher icon is one kind of drawing**: the suite's dark
  disc and rust ring, with a few large shapes inside. An app with a twin
  in the Fe₂O₃ suite carries a simple drawing of the twin's logo, so the
  two are known as one thing; the suite's own logo is too busy at the size
  of an icon. An app with no twin (fresh, hyperlist, onepage, outside, ref,
  relay, tasks, vox) gets a drawing of what it does. Draw
  `apps/<app>/logo.svg` by the rules at the top of `tools/logo`, then run
  `tools/logo <app>`. All eighteen apps have one.

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

- **JDK 21** (`/usr/lib/jvm/java-21-openjdk-amd64`; JDK 17 is gone from
  this laptop). Gradle needs it named in `JAVA_HOME`; the apps still
  compile for Java 17.
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
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
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
- **Reminders.** An item stamped the HyperList way — `2026-07-27 12.08:
  Call Alice`, period between hours and minutes — fires a notification at
  that time. Parsing, listing and the spoken-sentence parser live in
  `core/src/reminder.rs`; the Kotlin side only converts a civil `Stamp` to
  epoch millis with the device `ZoneId` and drives AlarmManager. Alarms
  are re-armed on load, on save, after a reboot, and on the exported
  `com.isene.tasks.action.RESCAN` broadcast that vox sends after filing a
  spoken reminder. `[x]` items never fire, and a stamp in the past is
  history, not a missed alarm.

### gaze (com.isene.gaze)

- A browser around Android's WebView, paired with the laptop's gaze.
  Signed with the tasks key, like fresh.
- The synced folder (default `Documents/gaze`) is the laptop's
  `~/.gaze/sync/`: `passwords` (the laptop's sealed file, byte for byte),
  `bookmarks`, and `tabs/to-phone/`, `tabs/to-laptop/` (one file per tab,
  URL, a tab, the title). Both sides read a changed file again before
  they write it, so neither loses the other's login or bookmark.
- `core/src/gaze.rs` holds the formats, the ad list and the suggestions.
  "Ask Claude" shares the page's text to the Claude app
  (`com.anthropic.claude`), so the user's own plan answers and no API key
  lives on the phone. 0.1 had a Messages API client; 0.2 removed it.
- Downloads: a web address goes to Android's DownloadManager. A `blob:`
  address can only be read by its page, so `blobScript` runs there and
  posts the bytes through `gazeBridge` in pieces. Every page can call
  that bridge, so `filePiece` takes a piece only under a key that
  `fromBlob` made for a download the page itself started, and only from
  that tab. `Saver` writes through MediaStore and never over a file.

### outside (com.isene.outside)

- Three weather forecasts for one place, in columns: 0 = Yr (MET Norway),
  1 = Storm (TV 2), 2 = GFS (NOAA, through Open-Meteo). Signed with the
  tasks key.
- `core/src/outside.rs` builds the three requests, parses the bodies and
  lays out days and hours. Kotlin fetches, caches the raw bodies in
  `cacheDir/forecasts/<lat>_<lon>/` and draws.
- Storm is TV 2's own page server (GraphQL, POST), not a public API. It
  can change without notice; the app must keep showing the other two
  columns when it fails. `outside_usable` guards the cache, so an error
  body never replaces a good forecast.
- Warnings are a fourth body, `alerts`, from MET Norway's `metalerts`
  (point query, English). `outside_requests` leaves its URL empty outside
  a box around Norway, and the shell then skips the request.
- Fetches happen in `onResume` only, and only for bodies older than 30
  minutes (10 on pull-to-refresh). No WorkManager, no widget.
- The phone's position is rounded to two decimals before any request.
  The manifest asks for coarse location only.
- The phone hands the core a `Tz`: the spot's offset now and the one
  change inside the forecast. Search hits carry a zone name; a followed
  position uses the phone's own zone.

### pointer (com.isene.pointer)

- A file manager after the laptop's pointer. Signed with the tasks key.
  All-files access (`MANAGE_EXTERNAL_STORAGE`) and no INTERNET
  permission; keep it that way.
- `core/src/pointer.rs` is the one core module that touches files
  (`std::fs`). Its tests run on real folders. Three promises it must
  keep: nothing is overwritten (a taken name gets a number), nothing is
  deleted outright except by "empty the trash", and a failed or stopped
  copy leaves no half file.
- The trash is `<volume>/.pointer-trash/<id>/<item>` plus `<id>.info`
  (where it was, and when). A delete is a rename on the same volume.
- A move between volumes is a copy with `sync_all` per file, then a
  count of files and bytes, then the removal of the source.
- One batch runs at a time. The shell calls `pointer_job_start` before
  each one (undo too), or an old cancel stops the new batch at once.
- Kotlin filters the loaded list while the user types; only "search the
  folders below" walks the disk. The folder is read again on resume only
  when its change time moved (one `stat`).
- A file goes to another app through a FileProvider (`root-path`
  `storage/`, and `cache-path` `archive/` for files copied out of
  archives), one grant per open or share.
- An archive (zip, jar, tar, tgz, tar.gz) is a folder: the path
  `<archive file>/<path inside>` is on no disk, and `packed()` finds the
  archive on the way to it. Archives are read only. `clean()` drops every
  entry with `..` in its path, and tar links are skipped, so nothing
  unpacks outside its target folder. The list of the last archive is kept
  in memory until the file's size or time changes.
- `ShareActivity` is exported ("Save to folder" in the share sheet). It
  must never write before the user taps a folder, so no Direct Share
  shortcuts and no target folder taken from an intent. It reads
  `content://` links only and refuses the app's own provider. The file
  name goes through `pointer_safe_name`.
- Tabs live in `UiState.tabs`, one back-trail each, at most eight, saved
  in `onStop`. The marks row is dragged into order (`sh.calvin.reorderable`)
  and written once, when the drag ends.
- A text file is shown in Android's own `TextView` inside a `ScrollView`
  (through `AndroidView`), so text can be marked and copied. Keep it:
  Compose's `SelectionContainer` over a list of lines copies them with no
  line ends between, and forgets the lines that scrolled away.
- Search inside files (`pointer_grep`) reads text files up to 8 MiB and
  only when the user asks. Video thumbnails come from `coil-video`, one
  frame per visible row.
- Record names are plain (`Entry`, `Mark`, `Kind`, `Undo`, `Done`). All
  core modules share one Kotlin package, so a new module must not reuse
  them.

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
2. `./gradlew :apps:<name>:assembleRelease` succeeds. One app per run:
   the apps share one Rust build folder, so gradle refuses two together.
3. APK installs in place over the previous version (no signature
   mismatch, no SAF URI loss).
4. Glance widget actually rebuilds and shows current data.
5. WorkManager constraints respect battery: no jobs fire at 100% drain
   in airplane mode.

Cargo build success and Gradle assemble success do not equal behavioural
correctness. Exercise the actual code path on the phone before tagging.
