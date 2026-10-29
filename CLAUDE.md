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
  `tools/logo <app>`. All twenty apps have one.

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
- Every widget is clear at first, so the wallpaper shows through, and
  has one setting for the colour behind it: "Widget background", six hex
  digits and how solid (Geir asked for it on 2026-10-13). The app keeps
  it as one ARGB number, 0 for none. A new widget gets the same.
  kastrup's dialog is "Widget colours": it sets the header line's colour
  too (Geir asked on 2026-10-14).
  The corners are square: he asked for that the same day, so no widget
  draws a radius. onepage rounds nothing either.
  - In a `RemoteViews` widget the root has the id
    `@android:id/background` and a white shape, and `refresh`
    lays the colour over it with `setBackgroundTintList`. It sets the
    tint every time, the clear one too: a home screen keeps a widget's
    views, so a colour that is left out stays. tock's
    `WidgetOnAndroidTest` fails when that call is skipped.
  - In a Glance widget the colour goes through the widget's own state,
    as the list does, and `background` is left off when it is 0.
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
- 0.10.1 (2026-10-29): the least indented lines are the categories, so a
  file with sections at no tab reads right; saves put sections at no tab
  and tasks at one, as Geir now keeps todo.hl. vox 0.2.2 shares the fix.
- Since 0.8.0 (Geir asked on 2026-10-14) a widget line has its
  category's colour, and the name in front is off unless chosen. "Widget
  text" sets the size (13 sp at first), the names and a colour per
  category, kept as JSON by name. A category with no colour of its own
  takes one of eight by its place in the list (`categoryColor`), so the
  dialog and the widget must count places in the same full list.
- Glance widget: shows the first ~10 items across categories, tap
  opens the app. No editing from the widget in v1 (keeps the widget
  side trivial; widgets that edit need a hosted Activity hop anyway).
- 0.10.0 (asked 2026-10-27): the widget reads todo.hl again by itself.
  `WidgetTick` is an `RTC` alarm (never `RTC_WAKEUP`) every 10 minutes,
  armed only while a widget is placed: from the receiver's `onUpdate`
  (after a reboot or an update) and from `MainActivity`, cancelled in
  `onDisabled`. It redraws only when the text changed. A sleeping phone
  is left alone; the tick waits until the screen is on.
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
  history, not a missed alarm. Since 0.9.0 (asked 2026-10-16) the item dialog
  has "Add a reminder": a day picker, then a time picker, written with
  the core's `set_stamp`, which keeps a `[ ]` in front. A Compose test of
  the dialog was tried on Robolectric and dropped: every AlertDialog,
  the old ones too, never settles there (bare Text and TextField do).

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
- Desktop sites (0.4.5): `fit()` sets a site's browser name before each
  main-frame load: the phone's own, or a desktop Linux Chrome with the
  same Chrome version. A GET whose name changed is loaded again under
  the new one; `onPageStarted` reloads a page the back key reached.
  `DESKTOP_SCRIPT` (document start) sets the viewport to 1024 wide when
  the name has no "Android"; checked in headless Chromium as a phone.
  The menu's site items sit under "This page ›".
- Start page (0.5.0): a tab on `about:blank` shows `StartPage` over the
  WebView, from `Places.top` and `Places.mostUsed`. The laptop's twin
  is `gaze://home`. A home page set in the settings opens instead.

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
- Each day has a small moon (0.3.8; Geir asked for it on 2026-10-10).
  `OutsideDay.moon` is the outline of the lit part at the day's noon,
  from `moon_outline`: points on a disc of radius 1. `DayRow` fills it on
  a dark disc, on a third line under the day's name. 0.3.8 showed one of
  eight emoji instead, so the day after new moon had a fat crescent, and
  so had the six days after it. `moon_face` still names the widget's
  phase. At a large font the two lines above the moon can fill the 88 dp
  column, and the cells beside it are always three lines tall, so that
  line costs no height. Since 0.3.10 the moon is drawn 8 dp lower than
  it is laid out (`Modifier.offset`): it stood against the dot above
  it. The row keeps its height.
- The widget (`Widget.kt`, since 0.3.0) copies a KWGT design to the
  pixel: `res/layout/clock_widget.xml` has the measured sizes, all in dp.
  - It is plain `RemoteViews`, not Glance. `TextClock` and `AnalogClock`
    move by themselves in the launcher and exist only there, and Glance
    starts a worker for every update.
  - `ClockWidget.refresh` is run by events only: an exact `RTC` alarm on
    the full hour (never `RTC_WAKEUP`), `WidgetEvents` for a new next
    alarm or a set clock, a content-trigger job (`WidgetSound`) for the
    volume, `MainActivity.onStop`, and `OutsideViewModel.load` when a
    fetch brought a forecast (0.3.15). The app shows the old forecast at
    once and may wait 8 s for a position before it fetches. Until
    0.3.15 a look shorter than that left the ring on the old forecast
    until the full hour, and bare when that forecast was past
    `FRESH_MS`. `WidgetEvents` is disabled in the
    manifest and enabled while a widget is placed.
  - The dial and the two hands are vectors of one 92 dp square, because
    `AnalogClock` lays them centre on centre. `refresh` draws the
    weather ring and the rim icons into a bitmap on top.
  - The ring between two hour ticks has the colour of the hour that
    starts there, for the twelve hours from now: blue for rain, grey for
    cloud, light yellow for partly cloudy (0.3.13; Geir asked for it on
    2026-10-12; `F8FA6E` since 0.3.14, not yet judged on a phone),
    yellow for sun (a clear night too). The dial has a thin white
    circle of its own under the ring: a ring that is white all round
    is a ring with no weather on it, see `FRESH_MS`. `outside_dial` in the
    core decides. An hour is rain when one source shows rain for it in
    the app: a wet symbol, or an amount the app prints (0.05 mm or
    more). A dry hour is cloud when the sources together say more than
    "partly cloudy", and partly cloudy when they say more than "fair".
    Until 0.3.3 rain was 0.1 mm as the mean of the
    sources, and the ring was grey for an hour the app showed rain in. `refresh` reads the three cached
    bodies for the widget's place and skips one older than twelve hours.
    That is three file reads and one parse a run. Since 0.3.16 the
    widget fetches for itself, once an hour: when the newest body is
    older than 50 minutes (`STALE_MS`; under an hour so that every
    full-hour run asks), `refresh` asks `RingFetch` (a `JobService`
    with a network condition, hence `ACCESS_NETWORK_STATE`), at most
    once in that time (`Store.asked`). It fetches the three bodies for
    the widget's place and calls `refresh` when one came. Until then
    only the app fetched, and only the place on its screen. Geir said
    yes to the widget's own fetch on 2026-10-13, and asked for one
    every hour ("Fetch it on every hour"; I had proposed every three). Geir asked for the ring on 2026-10-10 (0.3.0 had one blue
    tick for the hour now, 0.3.1 blue ticks for rain).
  - The alarm's icon is green (`ALARM_SOON`) when the alarm rings
    within twelve hours and grey when it rings later (0.3.11; Geir
    asked for it on 2026-10-11). Since 0.3.12 it sits inside the ring
    (`INSIDE`), 27 dp from the middle: on the rim the sunset icon could
    cover it, and Geir asked for a place of its own. The minute hand
    crosses it for two minutes an hour. The first green, 7DBE82, was
    too weak for him. The dial shows twelve hours, so the
    icon's place alone cannot tell the two apart. `refresh` asks for one
    more run at twelve hours before the alarm when that moment is
    before the next full hour. Not tried on a phone by Claude.
  - The widget's look has no settings screen and gets none: Geir asks
    for a change, and it goes into the code. The colour behind it is
    the one choice (`BackRow`, 0.3.19), as in every nomad widget.
  - The one setting is the app each of the three parts opens on a tap
    (`Tap`; Geir asked for it on 2026-10-10, 0.3.3). `Store.tap` keeps
    a "package/activity" under `tap_left`, `tap_clock` and `tap_right`,
    empty for the app that part came with. `TapSheet`, opened from the
    bottom of the screen, picks from `launchable`, which needs the
    `<queries>` entry in the manifest. `refresh` reads the three from
    the prefs it has open already. The widget takes a new choice in
    `MainActivity.onStop`, so nothing refreshes it from the sheet.
  - The dot for a waiting fleet message (0.3.7 to 0.3.23) is gone in
    0.3.24: the fleet app's red notice took its place, and its
    `InboxWatch` asked once a minute on its own.
  - `TextClock` has no week number, so `refresh` writes the week into
    the date pattern as quoted text.
  - A day with no rise reads "up all day" or "down all day" (0.3.18).
    Until then the line was blank, and Geir took the blank for a fault
    when the moon stayed down in a town far north. `orbit::moon_times`
    gives `None` for both cases; the core tells them apart by the side
    of the sky's equator the body is on.
  - `outside_sky` in the core gives the sign, the rise and set lines and
    the lit part of the moon. The lit part is from the angle between sun
    and moon at that hour; `orbit::moon_phase` is a day off near new moon.
  - The place is the one on the app's screen, `Store.last()` (0.3.17;
    Geir chose that on 2026-10-13). With "Current location" that is
    where the app last saw the phone; the widget asks for no position.
    Until 0.3.16 the place was where the app last followed the phone,
    whatever was on screen. That was Claude's choice in 0.3.0 and the
    cause of the ring without colours on 2026-10-12: a searched place
    was on screen, so nothing fetched the forecast for the widget's
    place, and Geir expected the widget to show the place he had
    chosen. Three wrong causes were named first (a pale colour, an
    unopened app, a look too short); ask which place is on screen.
  - The place's name is the last line on the right, in line with the
    times (0.3.17). Geir asked for it "somewhere"; the spot is Claude's
    pick. It is `match_parent` in a `wrap_content` column, so a long
    name gets an ellipsis and never widens the column. Its box ends
    1.3 dp above the row's lower edge: no room for a taller text.
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
- The whole-phone views (Audio, Videos, Documents) ask Android's media
  index (`MediaStore.Files`, by `MEDIA_TYPE`) when opened, and never walk
  the disk. Paths with a hidden folder in them are left out.
- Search inside files (`pointer_grep`) reads text files up to 8 MiB and
  only when the user asks. Video thumbnails come from `coil-video`, one
  frame per visible row.
- Record names are plain (`Entry`, `Mark`, `Kind`, `Undo`, `Done`). All
  core modules share one Kotlin package, so a new module must not reuse
  them.

### onepage (com.isene.onepage)

- The home screen: one page of widgets, and since 0.5.0 an app panel.
  Plain Views and no Compose, except the setup wizard.
- Where the panel comes up is a setting the user asked for (`Prefs.panelPlace`):
  along the bottom above the button bar (the default), or at the right or the
  left edge. The line that changes it is the last one in the panel's edit mode.
  The number of columns follows from the place, so the file stores slots only.
- An empty row below the last icons is a setting too (`Prefs.panelGap`), switched by
  the line under that one. Geir asked for it on 2026-10-26, so no unused icon
  has to pad the bottom row.
- The panel is a window of its own (`PanelWindow`,
  `TYPE_APPLICATION_OVERLAY`), so it comes up over any app, and the app
  below keeps running. It is built on open and dropped on close. Keep it
  that way: no view, receiver or thread may outlive a closed panel.
- Two things open it, both only from the bottom of the left half of the
  screen (Geir's ask on 2026-10-27; a swipe from the right half must do
  nothing). A swipe up on the empty home page that starts within 64 dp of
  the button bar (`HomeSurface.onSwipeUp`, `opensPanel` in `Rules.kt`), and
  a swipe up on a 14 dp strip along the left half of the bottom edge
  (`EdgeService`). Only a window from an accessibility
  service may lie over the button bar, and that is the one reason
  `EdgeService` is one. It must go on asking for no events and no window
  content (`res/xml/edge_service.xml`).
- Bank apps refuse to run while an accessibility service is on (his
  bank app ran once he turned OnePage's off). An app marked "Swipe off
  while it runs" in the panel's edit menu (`Prefs.quiet`) starts with
  `EdgeService` turned off in Android's list, and `HomeActivity.onStart`
  turns it on again (`Prefs.edgePaused`). Turning it on and off needs
  WRITE_SECURE_SETTINGS, granted once with `adb install -r -g` (ColorOS
  16 refuses `pm grant` and has no "Disable permission monitoring"). Turning the component
  off instead would not do: Android then takes the service out of its
  list for good (`updateServicesLocked` in AccessibilityManagerService).
- The panel's file is `filesDir/panel.json`. Its format and every edit
  (put, swap, add, folders, the shortcut list for Android) are in
  `core/src/onepage.rs`, with tests. Kotlin draws and starts things.
- A shortcut (a chat, a web page) arrives through `PinActivity`, which
  takes Android's pin request. Only the default home app may show or
  start a shortcut, so the panel cannot move to an app of its own.
- A call icon (`Target::Call`) comes from `ContactActivity`: the phone's own
  contact list picks one number, and the contact's picture is copied to
  `filesDir/face_<digits>.png`. `CALL_PHONE` and `READ_CONTACTS` are asked
  for there and may both be refused; keep both ways working.
- A folder's sign is a name from `assets/signs.txt`: 96 Lucide line icons as
  outlines. `tools/onepage-signs <lucide icons dir>` writes that file; add a
  sign by adding its name to the list in the script. `Signs.kt` reads the
  file on first use.
- The lists (`Pickers.kt`: apps to add, apps to start, the signs) are
  dialogs that keep nothing after they close.
- A row of a list is never kept for later. A `ListView` finds its height
  with one row and then shows that row as the first, so the row a
  `getView` call handed out may show another item when its picture is
  ready. Find the row by its tag among the list's children at that moment
  (`AppRows.showing`). 0.7.0 showed one icon and eleven empty rows.
- One folder may be the folder of all other apps (`rest` on
  `Slot::Folder`, "Other apps" under **+**). It stores no items:
  `panel_rest` gives the apps that are nowhere else in the panel, and the
  list is made each time the folder is tapped. Nothing watches for new
  apps. It opens as a list with names, never as a card of bare icons.
- The user fills the panel on the phone. His apps and contacts never go
  into the code, a default file or a test.
- Dialogs over the panel need `window.setType(TYPE_APPLICATION_OVERLAY)`
  or they come up behind it.
- Never draw the panel again from a touch that Android cancels. Removing
  a view makes Android cancel its touch at once, so a redraw from that
  cancel runs inside the first one and leaves holes in the grid. 0.6.0
  lost the panel at the end of every drag that way. `Drag` in `Rules.kt`
  ends the drag before the drop, and a cancel posts its redraw.
- One drag serves both grids: `drag(v, from, on)` takes the panel's `grid`
  or the open folder's `folderGrid`. In the panel a drop makes two slots
  change places. In a folder `panel_folder_move` puts the icon where it
  lands and the others close up. The folder card follows the panel: holding
  an icon switches editing on, and while editing a tap gives its menu.
- `Rules.kt` has what can be tested with no phone: the swipe rule, the
  number of columns, the slot under a finger, the reader of the signs file.
  The tests run on the laptop: `:apps:onepage:testReleaseUnitTest`.
- `PanelOnAndroidTest` runs the panel and its lists on Android's own view
  code, on the laptop, through Robolectric. It is for faults that come
  from what Android does with a view: a touch it cancels, a list row it
  shows again for another item. A new fault of that kind gets a test
  there, and the test must fail on the old code before it counts.
  - The same Gradle task runs it, in about eight seconds. The task also
    builds the Rust core for the laptop (`cargoBuildHost`), since the
    panel calls into it.
  - The first run on a machine fetches about 390 MB. Do it on Wi-Fi.
  - Run it before every APK that goes to the phone.
- Not seen on a phone by Claude: there is no emulator here. Say so after
  every change to the panel.

### tock (com.isene.tock)

- A calendar after the laptop's tock. Signed with the tasks key. Kotlin
  only, like fresh: every event comes from Android's own calendar store
  (`CalendarContract`), which the phone's accounts keep in sync with the
  laptop on or off. No INTERNET permission, no tokens; keep it that way.
  A Teams meeting goes out as a `teams.microsoft.com/l/meeting/new` link
  that the Teams app opens (`teamsLink`); tock writes nothing for it.
- The day and colour rules are in `Days.kt`, which touches no Android
  class. Their tests run on the laptop:
  `./gradlew :apps:tock:testReleaseUnitTest`. An all-day event is
  midnight to midnight UTC in the store, so its day is read in UTC and
  never in the phone's zone.
- `LocalDate` is not `Comparable<LocalDate>` in Kotlin's eyes: `maxOf`,
  `a..b` and `sortedMapOf` do not take it. Use `isBefore` and a `TreeMap`.
- Colours: `Settings` keeps a terminal colour number (0 to 255) per
  calendar, the number laptop tock has in `calendars.color`. No account
  name may be written into the code or an APK: the numbers come in by
  hand or from a text file the user picks (`laptopColors`).
- The widget is plain `RemoteViews` with a list handed over in one piece
  (`RemoteCollectionItems`, no service). `AgendaWidget.refresh` is run
  by events only: a job Android runs when the calendar store changed
  (`CalendarWatch`, it asks again each time), a plain `RTC` alarm at the
  end of the next event or at midnight, and `WidgetEvents`, which is
  switched off until a widget is placed.
- Reminders: the calendar store keeps the alarms and sends
  `EVENT_REMINDER`. `Reminder` shows the notice and marks the alert as
  fired, or the store sends it again.
- A repeating event is shown and can be deleted (one time through
  `CONTENT_EXCEPTION_URI`, or all). A change is to one time
  (`Draft.instance` holds its start; `Phone.save` writes an exception),
  or to that time and the later ones (`Draft.later`). The later ones
  split the series as Android's own calendar app does: the old rule gets
  an UNTIL a second before that time (a COUNT is shared out), and one
  batch inserts a new series with the guests. From the first time the
  series is updated in place. Not handled, as in Android's app: a time
  changed alone after the cut stays and shows beside the new series.
- The phone can leave an account unfetched for hours: an Outlook
  invitation was missing for over three hours. So `resume()` asks the
  accounts to fetch (`Phone.sync`, the call "Sync now" makes), at most
  once in 15 minutes (`fetchDue`). The widget asks for nothing; a timer
  for that was not built.

### prism (com.isene.prism)

- The colour picker of the laptop's prism. Signed with the tasks key.
  Kotlin only, like fresh: `Color.kt` copies the colour rules of prism's
  `src/main.rs` line by line and touches no Android class. A rule changed
  on one side is changed on the other. Tests:
  `./gradlew :apps:prism:testReleaseUnitTest`.
- No permissions. The two colours, the chosen one and the format are one
  line in the prefs, written in `onStop` and only when it changed.
- The state is RGB, as on the laptop, so dragging S or V to 0 forgets
  the hue.
- The screen fits without scrolling (0.1.1; Geir asked on 2026-10-14):
  the sample text takes the height that is left, and the hex field is
  near the top, above the keyboard. `ScreenOnAndroidTest` lays it out on
  Android's own code at 360 by 700 dp with text at 130 % and fails when
  a slider or Copy falls off; it writes `build/screen.png` to look at.

### mail (com.isene.mail, the kastrup app)

- 0.17.0: Laptop drafts (menu). `DraftsRepo` reads `drafts/` of the
  shared kastrup-read folder through the SAF tree. Send moves the file to
  `send/`, created as `application/octet-stream` so the provider keeps
  the name; laptop kastrup 0.3.90 queues it in `scheduled`, due now.
- The laptop's `~/.kastrup/drafts` is a link to `~/.kastrup/sync/drafts`.
- Tests are plain JUnit (`DraftsRepoTest`, the draft parser).
- 0.18.0 (asked 2026-10-27): the widget takes in the relay chats and the
  laptop's read marks by itself, as opening the app does, through
  `WidgetTick` (the same 10-minute `RTC` alarm as tasks) and
  `SyncEngine.summarize`. It fetches nothing; new mail still comes with
  the fetch. Build mail and tasks in separate Gradle runs: one run
  with both fails on the shared Rust core's outputs.
- 0.19.0 (asked 2026-10-29, step 1 of replacing the Gmail app): Enter in
  the search field runs `ImapRepo.search`, Gmail's `X-GM-RAW` over All
  Mail (found by its `\All` mark, since its name follows the account's
  language), sent raw through `IMAPFolder.doCommand`. A hit keeps its
  folder, so `body` opens it there. Attachments get Share (`ACTION_SEND`).
  Next steps, in his order: new mail at once (IDLE in a foreground
  service), web pictures after one tap, read/archive/delete back to the
  laptop through the shared folder, compose from all five addresses,
  threads. He uses no labels, snooze or undo send.

### fleet (com.isene.fleet)

- Rings the phone when a Claude Code session on the laptop finishes.
  Signed with the tasks key, Kotlin only. The server side is fleet's
  `server/fleet-mcp.py`: `GET <address>?done=SINCE&wait=SECS` answers
  `{"events": [{"tag", "t", "kind", "eta", "line"}]}`, the turns after
  SINCE, and keeps the request open up to 80 s while there are none.
- The address is the fleet connector's, a secret: never print it, log
  it or put it in a test. The app has `allowBackup=false`.
- Three switches. kind "eta" rings with "ETA work done" or "Every
  finished turn", kind "turn" only with the second. All off: `Watch` is
  stopped, nothing runs.
- "Messages for the phone" (0.3.0) adds `&phone=N` to the same request,
  N the count the red notice shows. The server answers early when its
  count differs, with `waiting` and `line` (the newest message's first
  line). The notice (id 2, colour 0xFFE8795A) buzzes when the count
  grows, goes at 0, and a tap hands "Check" to the Claude app's share
  target (`Watch.claude`; the `<queries>` entry lets it see the app).
- `Watch` is a foreground service with one thread and one open request.
  The first request is `done=0&wait=0` and only sets the cursor. On a
  fault it waits 30 s, doubling to 10 min, or until a network comes.
- It asks with the screen off, on purpose, and asks once for the
  battery-optimization exemption. Cost: one
  request every 80 s, about 45 radio wakes an hour.
- One notice per session tag, `setOnlyAlertOnce`, so a turn the style
  hook sends back does not buzz twice.
- 0.1.0 crashed on Save: `registerDefaultNetworkCallback` needs
  ACCESS_NETWORK_STATE, which Robolectric does not check. A test now
  checks the manifest lists every permission the watch needs.
- The screen shows "Address saved: <host>" (never the path) and
  `Watch.status`, a Compose state the loop sets on a change.
- Tests: `Done.kt` (answer, which turns ring, notices) and
  `WatchOnAndroidTest`, the real loop against a socket on 127.0.0.1.
- 0.2.0 shows the laptop fleet's session list (`Board.kt`). The laptop
  fleet writes `sessions.json` to the relay when a state changes;
  `GET <address>?sessions=STAMP&wait=80` answers once its file time
  differs from STAMP (-1 for the first ask). Asked only between
  onResume and onPause, never with the screen off.
- A long press on a session opens a message box. Send POSTs the
  server's `message_session` tool (JSON-RPC `tools/call`) to the same
  address; the server's reply shows in a toast.
- Rows show the time of last activity, not an age, so nothing redraws
  on a timer. `BoardOnAndroidTest` covers the list and the message.
- A tap on a turn's notice opens the list at that session: the intent
  carries `Watch.SESSION`, `Board.jump` scrolls to the row once it is
  laid out, and `Board.marked` tints it until the screen is left.
- 0.4.0: the list is compact (`current()`: YOURS, working, CAPPED show
  more), and a tap opens `Answer.kt`. The laptop's fleet hook writes
  each finished answer through the HyperList display hook to
  `relay/answer-<tag>.txt` (SGR colours, no wrapping); the server adds
  its colourless start to each session as `answer` and serves it whole
  at `GET <address>?answer=TAG`. `colours()` turns SGR into spans and
  darkens them on a light screen. Yes/No/Done/Rec (0.4.2) make `reply()`, sent by
  `message_session`. A notice tap now opens the answer, with the list
  behind it scrolled to the session.
- laptop fleet 0.3.62 wakes a session for any message from the phone
  (`*-phone.msg`), » or not, even in the focused window.
- 0.4.1: kind `ask` (laptop fleet 0.3.63): Claude Code's Notification
  hook, matcher `permission_prompt`, runs `fleet --hook`, which writes
  `done-<tag>.json` with the tool call read from the transcript tail.
  The phone rings it with either switch, orange, alerting over the
  session's last notice.
- 0.4.3: `ask` in the session list (laptop fleet 0.3.64, from the
  hook's mark in `~/.fleet/ask/`), shown in the row and the answer
  screen. Answering the dialog from the phone was refused by the
  auto-mode safety check (2026-10-24); he chose allow rules instead.
- 0.4.4: the weekly use above the list (`usage` in sessions.json, laptop
  fleet 0.3.66, from the statusline's usage cache). The answer screen's
  column takes `imePadding()`, which alone did not keep a field in sight.
- 0.4.5: `Modifier.inSight(text)` in Answer.kt brings a field (and the
  Send button) into view on focus, on each keyboard height and on each
  text change. No Robolectric test reaches the keyboard; he checks it on
  the phone. ⋮ beside the title has About with the version.
- 0.4.7: a sent message's answered items (`answers()`, the laptop
  hook's rule) leave the list at once. `Answered` keeps them out until
  the session ends a turn after the message, or the list drops them.
  The laptop hides nothing for a phone message: its hook sees only the
  wake prompt "Check fleet messages".
- 0.4.8: Drop beside an item calls the server's `drop_item` tool (laptop
  fleet 0.3.68), which writes `relay/.drop/<tag>.<num>` with the item's
  text. fleet deletes it while the number still names that text.
- 0.4.9: About links to isene.org/nomad. The first version on the
  download page (published 2026-10-26).
- To look at a screen without a phone: a Robolectric test with
  `@GraphicsMode(NATIVE)`, qualifiers like `w411dp-h891dp-night-xxhdpi`,
  `buildActivity(...).setup()`, then `window.decorView.drawToBitmap()`
  to a PNG.

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
- It also needs two entries in the fe2o3 repo: a card under "Companion
  apps" in `docs/index.html`, and a row in the companion table of
  `README.md`. tock went out on 2026-10-13 without them.
- A feature that changes what an app is goes into all four texts: the
  card and the row here, the card and the row in fe2o3.
- outside is private. Geir dropped it as a public app on 2026-10-10: no
  download, no card on the page, no row in a listing here or in the
  fe2o3 repo. `tools/publish` refuses it; `--check outside` still
  builds it for his phone.
