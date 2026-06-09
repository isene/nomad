# OnePage — minimal Android launcher

Brief for the nomad Claude Code session. Build this as a new app in
the monorepo: `apps/onepage/`, applicationId `com.isene.onepage`.

## Why this exists

The owner uses an Oppo Find X9 Ultra on ColorOS 16. Their preferred
third-party launcher (SmartLauncher) cannot be brought back to the
foreground by the Home button after another app takes over fullscreen,
because SmartLauncher omits `SYSTEM_ALERT_WINDOW` from its manifest
and therefore never appears in ColorOS's "Display over other apps"
allowlist. There is no user-side fix; the launcher has to declare
the permission, and SmartLauncher does not.

OnePage replaces SmartLauncher with the absolute minimum useful
launcher: one screen, no drawer, no app launching (the owner uses
Panels for that), free widget placement with overlap allowed,
wallpaper, and a manifest that makes the ColorOS Home button work.

This is also a chance to nail the prime metric: the launcher is
always alive, so every wasted cycle costs battery for the lifetime
of the device. Aim for true zero-cost idle. No timers, no polling,
no observers, no background services. The launcher draws once on
home press, then sits inert until the user interacts or a widget
pushes an update through `RemoteViews`.

## Where OnePage differs from the rest of nomad

The standard nomad app shape (Rust core + Compose shell + Glance
widget + WorkManager + Syncthing) is wrong for a launcher. OnePage
is almost entirely Android platform-surface code:

- It is a **host of third-party widgets**, not a producer of one. Use
  `AppWidgetHost` + `AppWidgetHostView`, not Glance.
- It has **no Syncthing data flow** in v1. Widget positions are
  device-local UI state.
- It has **no Compose UI** for the home surface. Use traditional
  Views: a custom `FrameLayout` subclass holds the widgets. Compose
  is great but it adds recomposition cost the launcher does not
  need, and `AppWidgetHostView` is a View anyway (wrapping it in
  Compose buys nothing).
- The **Rust core touch is minimal**. A `Layout { widgets:
  Vec<WidgetPos> }` struct with serde-json round-trip in
  `core/src/onepage.rs` is enough; Kotlin handles SAF / SharedPrefs
  I/O. Keeping the format in the Rust core means desktop tools can
  edit a layout file in the future if useful, and keeps the app
  consistent with the rest of the suite.

Compose **is** used for the first-run permission walkthrough Activity
(see "Install-time permission flow" below), because that screen is a
short-lived setup wizard and Compose is fine for that.

## Feature surface (v1)

1. One home page. No paging, no scrolling, no swipe gestures of any
   kind on the home surface itself.
2. No app drawer. No app icons (the owner launches apps via Panels).
3. Free widget placement. No grid, no snap, no measurement passes;
   `(x, y, width, height)` per widget. Overlap allowed and expected.
4. Wallpaper shows through. Render-only, no scrolling-parallax.
5. ColorOS Home button works after first-run setup. Verified.
6. First-run wizard walks the user through every needed permission
   so there is no "after-work". The wizard does not appear again
   after completion.
7. Long-press anywhere on the home surface enters edit mode. Tap
   "Add widget" → standard `ACTION_APPWIDGET_PICK` flow. Drag a
   widget to move; two-finger pinch to resize; tap "Done" to exit
   edit mode and persist.
8. Long-press an existing widget shows a small popup: Resize / Remove.

## Out of scope (do not add in v1)

- App drawer
- Multiple home pages
- Folder support
- Search bar
- Notification badges
- Live wallpapers (the owner uses static)
- Dock
- Recent-apps integration
- Themes or icon packs
- Backup / restore of layout (post-v1; needs the Rust-core layout
  file to be portable first)

If you find yourself implementing any of these, stop and confirm
with the owner first.

## Toolchain notes specific to OnePage

- Same toolchain as the rest of nomad (JDK 17, Android SDK 35,
  NDK 27.x, Kotlin 2.x). No new deps.
- Compose only for the first-run wizard Activity. The home Activity
  uses traditional Views (`FrameLayout` + `AppWidgetHostView`).
- Min SDK: 30 (Android 11). Target SDK: 35.
- No proguard rules needed; the View paths in the home Activity are
  all reflection-free.

## Manifest

Critical pieces. The full manifest will be larger; these are the ones
that decide whether OnePage works at all.

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
          package="com.isene.onepage">

  <!-- The line that makes the ColorOS Home button work. -->
  <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>

  <!-- AppWidget hosting. BIND_APPWIDGET is signature-level for
       system launchers; standard launchers use the user-consent
       ACTION_APPWIDGET_BIND intent flow instead, which is fine. -->
  <uses-permission android:name="android.permission.BIND_APPWIDGET"
                   tools:ignore="ProtectedPermissions"/>

  <!-- Battery optimization exception so ColorOS doesn't phantom-kill us. -->
  <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>

  <!-- Wallpaper read so we can show it through; this is normal-level. -->
  <uses-permission android:name="android.permission.SET_WALLPAPER"/>

  <application
      android:label="OnePage"
      android:icon="@mipmap/ic_launcher"
      android:theme="@style/Theme.OnePage">

    <!-- HOME activity -->
    <activity android:name=".HomeActivity"
              android:launchMode="singleTask"
              android:stateNotNeeded="true"
              android:excludeFromRecents="true"
              android:exported="true"
              android:configChanges="keyboardHidden|orientation|screenSize|screenLayout|navigation|uiMode">
      <intent-filter>
        <action   android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.HOME"/>
        <category android:name="android.intent.category.DEFAULT"/>
      </intent-filter>
    </activity>

    <!-- First-run wizard. Launched on every boot until completed. -->
    <activity android:name=".WizardActivity"
              android:exported="false"
              android:theme="@style/Theme.OnePage.Wizard"/>

  </application>
</manifest>
```

The theme `Theme.OnePage` must include `<item
name="android:windowShowWallpaper">true</item>` and a transparent
root background, so the system wallpaper draws through automatically.
No custom wallpaper drawing needed.

## Install-time permission flow (WizardActivity)

The first time `HomeActivity` resolves, it checks the `setup_done`
preference. If false, it launches `WizardActivity` (Compose) which
walks the user through five screens in order. Each screen has a
"Skip" button (because some steps cannot be programmatically
verified on ColorOS), but the recommended flow grants everything.

1. **Welcome** — one line on what OnePage does + a Next button.

2. **Set as default home launcher.** Tap the button → fire
   `Intent(Settings.ACTION_HOME_SETTINGS)` on Android 14+, fall
   back to `Intent.createChooser(Intent(ACTION_MAIN).addCategory(
   CATEGORY_HOME), null)` for older Android. The wizard polls (no,
   actually it observes via `OnResume` lifecycle, no polling — when
   the wizard's Activity comes back into focus, re-check whether
   OnePage is the default home launcher and advance if yes).

3. **Grant "Display over other apps".** Tap the button → fire
   `Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
   Uri.parse("package:" + packageName))`. On resume, check
   `Settings.canDrawOverlays(context)` and advance if true. This is
   the ColorOS Home-button fix and is the most important step.

4. **Battery optimization exception.** Tap the button → fire
   `Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
   Uri.parse("package:" + packageName))`. On resume, check
   `PowerManager.isIgnoringBatteryOptimizations(packageName)` and
   advance if true.

5. **ColorOS auto-start (manual).** ColorOS has a vendor-specific
   "Auto-start manager" that no public API can talk to. Show
   text-only instructions: "Open Settings → Battery → Battery usage
   → OnePage → Allow background activity → Allow auto-launch."
   Include a button that fires the intent
   `Intent.ACTION_APPLICATION_DETAILS_SETTINGS` for OnePage to
   shortcut to that page. Just a Next button to advance; the user
   must self-attest this is done.

6. **Done.** Save `setup_done = true`. Finish the wizard. The
   HomeActivity is now functional.

Crucially: the wizard does NOT request anything via
`ActivityCompat.requestPermissions(...)` because every permission
OnePage needs is either install-time (auto-granted) or a "special
permission" (handled via Settings intents above). There are no
runtime permission dialogs.

Re-running the wizard: tap-and-hold the empty home surface → "Edit"
menu → "Setup again" menu item. Manual only.

## HomeActivity

```kotlin
class HomeActivity : AppCompatActivity() {

    private lateinit var host: AppWidgetHost
    private lateinit var widgetManager: AppWidgetManager
    private lateinit var surface: HomeSurface          // custom FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Prefs.setupDone(this)) {
            startActivity(Intent(this, WizardActivity::class.java))
            finish()
            return
        }

        widgetManager = AppWidgetManager.getInstance(this)
        host = AppWidgetHost(this, HOST_ID)

        surface = HomeSurface(this).apply {
            host = this@HomeActivity.host
            widgetManager = this@HomeActivity.widgetManager
        }
        setContentView(surface)

        LayoutStore.load(this).widgets.forEach { surface.restoreWidget(it) }
    }

    override fun onStart() { super.onStart(); host.startListening() }
    override fun onStop()  { host.stopListening();  super.onStop() }

    override fun onBackPressed() { /* swallow; home should not back-out */ }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PICK_WIDGET -> if (resultCode == RESULT_OK) surface.onWidgetPicked(data)
            REQ_CONFIG_WIDGET -> if (resultCode == RESULT_OK) surface.onWidgetConfigured(data)
                                 else surface.cancelPendingWidget()
        }
    }

    companion object {
        const val HOST_ID = 1024
        const val REQ_PICK_WIDGET = 1
        const val REQ_CONFIG_WIDGET = 2
    }
}
```

## HomeSurface (the home View)

A subclass of `FrameLayout`. Each widget is an `AppWidgetHostView`
added as a child with `FrameLayout.LayoutParams(w, h)` and
`leftMargin/topMargin` set absolutely.

Two modes: VIEW (default) and EDIT.

**VIEW mode:**
- Children receive touch events normally (widgets are interactive).
- Long-press on empty space (anywhere not occupied by a widget) →
  enter EDIT mode. Use a `GestureDetector` on the surface itself,
  but call `requestDisallowInterceptTouchEvent(false)` on children
  so widgets keep their own touches.

**EDIT mode:**
- Visual: dim the wallpaper slightly, draw a thin border around
  each widget, show floating buttons: [+ Add widget] and [Done].
- Drag any widget to move (single-finger drag updates
  margins live and is committed on touch-up).
- Two-finger pinch on a widget resizes (commit on touch-up).
- Long-press a widget → small popup: Resize / Remove.
- Tap Done → leave EDIT mode and call `LayoutStore.save(...)`.

Adding a widget:
1. User taps "+ Add widget" in EDIT mode.
2. `appWidgetId = host.allocateAppWidgetId()`.
3. Start `Intent(ACTION_APPWIDGET_PICK)` with
   `EXTRA_APPWIDGET_ID = appWidgetId` for `REQ_PICK_WIDGET`.
4. In `onActivityResult`: read `EXTRA_APPWIDGET_ID`, fetch
   `widgetManager.getAppWidgetInfo(appWidgetId)`.
5. If the provider needs binding (no permission yet), launch
   `Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)` for user consent.
6. If the provider declares a configuration Activity, launch it for
   `REQ_CONFIG_WIDGET`. Otherwise immediately call
   `addWidgetView(appWidgetId, providerInfo)`.

The `addWidgetView` path:
```kotlin
fun addWidgetView(appWidgetId: Int, providerInfo: AppWidgetProviderInfo) {
    val view = host.createView(context, appWidgetId, providerInfo)
    val (w, h) = defaultSizeFor(providerInfo)
    val lp = FrameLayout.LayoutParams(w, h).apply {
        leftMargin = (width / 2) - (w / 2)
        topMargin  = (height / 2) - (h / 2)
    }
    addView(view, lp)
    persistPending(appWidgetId, lp)
}
```

`defaultSizeFor` reads `providerInfo.minWidth/minHeight` (which are
dp at install time and need px conversion via the launcher's display
metrics). Don't trust `targetCellWidth/Height` because they assume a
grid; OnePage has no grid.

## Persistence

Two layers:

1. **SharedPreferences** holds `setup_done: Boolean` and the
   `HOST_ID` (constant, but stored so we never re-allocate widgets
   if HOST_ID ever moves).

2. **Layout file** in app-private storage at
   `filesDir/layout.json`, format from the Rust core:

   ```json
   {
     "version": 1,
     "widgets": [
       { "appWidgetId": 17, "provider": "com.android.calendar/.CalWidget",
         "x": 40, "y": 200, "w": 800, "h": 600 }
     ]
   }
   ```

   On every Edit→Done transition: call `LayoutStore.save()` which
   serializes the in-memory list via the Rust core (`onepage::Layout`)
   and writes atomically (write to `.layout.json.tmp` then rename).

   On `HomeActivity.onCreate`: call `LayoutStore.load()`, then
   for each entry: call `host.createView(context, entry.appWidgetId,
   widgetManager.getAppWidgetInfo(entry.appWidgetId))` and add to
   the surface at the persisted x/y/w/h. If the widget provider has
   since been uninstalled (`getAppWidgetInfo` returns null), drop the
   entry from the layout silently (next save persists the drop).

## Rust core touch

`core/src/onepage.rs`:

```rust
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Layout {
    pub version: u32,
    pub widgets: Vec<WidgetPos>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct WidgetPos {
    pub app_widget_id: i32,
    pub provider: String,   // ComponentName as flat string
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
}

impl Layout {
    pub fn empty() -> Self { Self { version: 1, widgets: vec![] } }
    pub fn parse(json: &str) -> Result<Self, serde_json::Error> { serde_json::from_str(json) }
    pub fn serialize(&self) -> String { serde_json::to_string_pretty(self).unwrap() }
}
```

UniFFI surface in `core/src/lib.rs`:
```
namespace fe2o3_mobile_core {
    Layout onepage_parse([ByRef] string json);
    string onepage_serialize([ByRef] Layout layout);
    Layout onepage_empty();
}
```

That is the entire core involvement. Everything else is Kotlin /
Android platform.

## Battery posture

This launcher is always alive. Every cycle matters.

- **No timers, no polling, no observers.** The owner's CLAUDE.md
  reinforces this; nomad's CLAUDE.md elevates it.
- **AppWidgetHost is event-driven.** Don't call `updateAppWidget`
  ourselves; the third-party widget providers push via `RemoteViews`.
  `host.startListening()` is the only subscribe point. Stop it in
  `onStop()`.
- **No `onWindowFocusChanged` work.** The launcher's window receives
  focus often (every home press, every dialog dismiss). Don't put
  any code in that callback beyond `super.onWindowFocusChanged(...)`.
- **No layout passes when idle.** The custom `FrameLayout` should
  `setWillNotDraw(true)` in VIEW mode (no canvas work; widgets draw
  themselves). Only re-enable drawing in EDIT mode where we draw the
  per-widget borders.
- **Wallpaper through-render is free.** Android composites the
  wallpaper without involving us at all.
- **Persist via atomic write only on Edit→Done.** Never on each
  drag-tick. The in-memory list is the source of truth during edit.
- **No background services.** No `Service`, no `JobScheduler`, no
  `WorkManager`. The launcher does no background work.

## Verification before tagging v1

1. `PATH="/usr/bin:$PATH" cargo test -p fe2o3-mobile-core` passes
   (layout round-trip tests).
2. `./gradlew :apps:onepage:assembleRelease` succeeds.
3. APK installs on the Oppo Find X9 Ultra, owner sets it as default
   home launcher via wizard.
4. Owner force-closes some other app fullscreen, presses physical
   Home, OnePage comes back. This is the acceptance test.
5. Add 3 distinct widgets (calendar, clock, weather) at overlapping
   positions, exit edit mode, reboot the phone. After unlock, all
   three widgets reappear in their saved positions and are live.
6. Run `adb shell dumpsys batterystats --reset`, leave phone idle on
   OnePage for 1 hour, run `adb shell dumpsys batterystats | grep -A
   3 com.isene.onepage`. Wakelock time should be effectively zero.
7. Drag a widget, verify CPU goes high during the drag and drops to
   zero within ~50 ms of touch-up.

## Stretch goals (post-v1, do not start without owner approval)

- Backup / restore via the Syncthing-synced `~/.onepage/layout.json`
  on the laptop. Requires the layout file to be portable (the
  appWidgetId is device-local; would need to re-bind on import).
- Per-widget rotation lock (some widgets look bad in landscape).
- Custom border / shadow per widget.
- Edit-mode undo (Ctrl+Z equivalent).

## Naming

Name: **OnePage**.
applicationId: `com.isene.onepage`.
Module path: `apps/onepage/`.
APK: `apps/onepage/build/outputs/apk/release/onepage-release.apk`.

Fits the suite's single-word style (`tile`, `bolt`, `bare`, `glass`,
`drain`, `scribe`, `tock`). Says exactly what it does.
