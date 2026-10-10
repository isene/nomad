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
- The tab list is reordered by hold and drag (0.4.2): the gesture sits on
  the list, a held tab is drawn `shift` pixels from its row, and
  `moveTab` keeps `current` on the tab that is on screen. Each `Tab` has
  an `id` as its list key. Not tried on a phone by Claude.

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
  minutes (10 on pull-to-refresh). No WorkManager.
- The widget (`Widget.kt`, since 0.3.0) copies a KWGT design to the
  pixel: `res/layout/clock_widget.xml` has the measured sizes, all in dp.
  - It is plain `RemoteViews`, not Glance. `TextClock` and `AnalogClock`
    move by themselves in the launcher and exist only there, and Glance
    starts a worker for every update.
  - `ClockWidget.refresh` is run by events only: an exact `RTC` alarm on
    the full hour (never `RTC_WAKEUP`), `WidgetEvents` for a new next
    alarm or a set clock, a content-trigger job (`WidgetSound`) for the
    volume, and `MainActivity.onStop`. `WidgetEvents` is disabled in the
    manifest and enabled while a widget is placed.
  - The dial and the two hands are vectors of one 92 dp square, because
    `AnalogClock` lays them centre on centre. `refresh` draws the
    weather ring and the rim icons into a bitmap on top.
  - The ring between two hour ticks has the colour of the hour that
    starts there, for the twelve hours from now: blue for rain, grey for
    cloud, yellow for sun (a clear night too). `outside_dial` in the
    core decides. An hour is rain when one source shows rain for it in
    the app: a wet symbol, or an amount the app prints (0.05 mm or
    more). A dry hour is cloud when the sources together say more than
    "partly cloudy". Until 0.3.3 rain was 0.1 mm as the mean of the
    sources, and the ring was grey for an hour the app showed rain in. `refresh` reads the three cached
    bodies for `Store.here()` and skips one older than twelve hours.
    That is three file reads and one parse a run; the widget never
    fetches. Geir asked for the ring on 2026-10-10 (0.3.0 had one blue
    tick for the hour now, 0.3.1 blue ticks for rain).
  - The widget's look has no settings screen and gets none: Geir asks
    for a change, and it goes into the code.
  - The one setting is the app each of the three parts opens on a tap
    (`Tap`; Geir asked for it on 2026-10-10, 0.3.3). `Store.tap` keeps
    a "package/activity" under `tap_left`, `tap_clock` and `tap_right`,
    empty for the app that part came with. `TapSheet`, opened from the
    bottom of the screen, picks from `launchable`, which needs the
    `<queries>` entry in the manifest. `refresh` reads the three from
    the prefs it has open already. The widget takes a new choice in
    `MainActivity.onStop`, so nothing refreshes it from the sheet.
  - A dot shows that a message waits (0.3.5; Geir asked for it on
    2026-10-10). `waiting` lists the folder in `Store.inbox()` once a
    `refresh` and stops at the first name that ends in `.msg`. With no
    folder chosen it returns at once. `marks` draws the dot 5 dp in from
    the top right corner of the clock's square. That corner is 56 dp
    from the middle and the rim icons reach 46 dp, so nothing meets it.
    `InboxRow` at the bottom of the screen takes the folder with the
    system's picker and keeps the leave to read it; a tap while a folder
    is set forgets it. The folder is the phone's copy of the messages
    that wait for Claude on the phone, brought there by Syncthing. The
    dot changes only when the widget is redrawn.
  - `TextClock` has no week number, so `refresh` writes the week into
    the date pattern as quoted text.
  - `outside_sky` in the core gives the sign, the rise and set lines and
    the lit part of the moon. The lit part is from the angle between sun
    and moon at that hour; `orbit::moon_phase` is a day off near new moon.
  - The place is `Store.here()`: where the app last saw the phone. The
    widget asks for no position and uses no network.
  - Not tried on a phone by Claude. The drawings were laid over the
    user's screenshot on the laptop and match within two pixels.
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

## Publishing an app

The download buttons on the landing page point at one GitHub release,
tag `apk`, with one file per app: `<app>.apk`. The link never changes,
so the file is replaced for every new version:

```bash
tools/publish <app>      # build, check, upload
tools/publish --check <app>   # build and check only
tools/publish --list     # what is published, and which version
```

- Publish a version after Geir has run it on his phone, never before.
- The script refuses an APK that names a folder of this machine. The
  build files keep such names out of the Rust library
  (`--remap-path-prefix`); keep that when adding an app.
- A new app needs a button in the `#get` grid of `docs/index.html` and
  a "Download APK" link on its card.
- outside is private. Geir dropped it as a public app on 2026-10-10: no
  download, no card on the page, no row in a listing here or in the
  fe2o3 repo. `tools/publish` refuses it; `--check outside` still
  builds it for his phone.
