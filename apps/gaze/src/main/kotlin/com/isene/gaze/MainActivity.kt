package com.isene.gaze

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.FileObserver
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import uniffi.fe2o3_mobile_core.AdList
import uniffi.fe2o3_mobile_core.Login
import uniffi.fe2o3_mobile_core.LoginChange
import uniffi.fe2o3_mobile_core.Places
import uniffi.fe2o3_mobile_core.gazeForget
import uniffi.fe2o3_mobile_core.gazeRemember
import uniffi.fe2o3_mobile_core.gazeSiteKey
import uniffi.fe2o3_mobile_core.gazeTabParse
import uniffi.fe2o3_mobile_core.gazeTabText
import uniffi.fe2o3_mobile_core.gazeToUri

enum class Screen { Browser, Tabs, Bookmarks, Passwords, Settings }

/** The most a page may hand over as one file. */
private const val HANDOVER_MAX = 1L shl 30

/** A download a page hands over in pieces. The file is opened by the first piece. */
private class Handover(val tab: Tab, var name: String, val mime: String?) {
    var file: Saver? = null
}

/**
 * A new file in the phone's Downloads, through MediaStore: no permission
 * needed, and a name that is taken gets a number, so nothing is ever
 * written over. The file stays hidden from other apps until [done].
 */
private class Saver(private val resolver: ContentResolver, name: String, mime: String?) {
    private val uri: Uri
    private val out: OutputStream
    private var size = 0L

    init {
        val row = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            // A bare "some file" type would glue .bin to a good name.
            if (mime != null && mime != "application/octet-stream") put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, row) ?: throw IOException("no place in Downloads")
        out = resolver.openOutputStream(uri) ?: run {
            resolver.delete(uri, null, null)
            throw IOException("Downloads will not open")
        }
    }

    fun write(bytes: ByteArray) {
        size += bytes.size
        if (size > HANDOVER_MAX) throw IOException("too large")
        out.write(bytes)
    }

    fun done() {
        out.close()
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    fun drop() {
        runCatching { out.close() }
        runCatching { resolver.delete(uri, null, null) }
    }
}

/** A file name a page suggested, cut down to a bare name. Null when nothing usable is left. */
private fun safeName(name: String): String? =
    name.substringAfterLast('/').substringAfterLast('\\').filter { !it.isISOControl() }.trim().trimStart('.')
        .take(120).takeIf { it.isNotEmpty() }

/**
 * Runs in the page: reads the file behind a blob: address and posts it to
 * the app in pieces of 384 kB, each as base64, the last one marked. The
 * name comes from the page's own download link where there is one.
 */
private fun blobScript(url: String, key: String): String = """
(async (url, key) => {
  const post = (m) => gazeBridge.post(JSON.stringify(Object.assign({t: 'file', k: key}, m)));
  try {
    const link = Array.from(document.querySelectorAll('a[download]')).find((a) => a.href === url);
    const blob = await (await fetch(url)).blob();
    const step = 384 * 1024;
    let at = 0;
    do {
      const bytes = new Uint8Array(await blob.slice(at, at + step).arrayBuffer());
      let text = '';
      for (let i = 0; i < bytes.length; i += 0x8000) text += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
      at += step;
      post({n: link ? link.download : '', d: btoa(text), end: at >= blob.size});
    } while (at < blob.size);
  } catch (e) {
    post({fail: true});
  }
})(${JSONObject.quote(url)}, ${JSONObject.quote(key)});
""".trimIndent()

/** Steven Black's unified hosts list: ads and trackers, public domain. */
private const val HOSTS_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"

/** The Claude app, which takes the page for "Ask Claude". */
private const val CLAUDE_APP = "com.anthropic.claude"
private const val PAGE_MAX = 100_000

/** How the WebView profiles of private tabs are named, with a time after it. */
private const val PRIVATE = "private-"

class MainActivity : ComponentActivity() {
    lateinit var prefs: Prefs
    lateinit var store: Store
    lateinit var vault: Vault
    lateinit var places: Places
    @Volatile var ads: AdList? = null

    val tabs = mutableStateListOf<Tab>()
    var current by mutableIntStateOf(0)
    var screen by mutableStateOf(Screen.Browser)
    var message by mutableStateOf("")
    var editing by mutableStateOf(false)
    var bookmarked by mutableStateOf(false)
    var unlockOffer by mutableStateOf(false)
    var askMaster by mutableStateOf(false)
    var unlocking by mutableStateOf(false)
    var saveOffer by mutableStateOf<Login?>(null)
    /** The link a finger is held on, while its menu shows. */
    var heldLink by mutableStateOf<String?>(null)
    /** How far a pull past the top of the page has gone, 0 to 1 (1 reloads). */
    var pull by mutableFloatStateOf(0f)
    /** Bumped when the logins change, so the password list redraws. */
    var vaultVersion by mutableIntStateOf(0)

    private lateinit var webHost: FrameLayout
    private lateinit var fullscreen: FrameLayout
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var watcher: FileObserver? = null
    private var timersPaused = false
    private var bookmarksSeen = 0L
    /** What the pages file holds, so an unchanged session is not written again. */
    private var pagesWritten: ByteArray? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    /** The WebView profile the private tabs share, while one of them has a page. */
    private var privateName: String? = null
    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = Prefs(this)
        store = Store(this, prefs)
        vault = Vault(store)
        places = Places(store.readText(store.bookmarks), store.readText(store.history))
        bookmarksSeen = store.bookmarks.lastModified()

        webHost = FrameLayout(this)
        fullscreen = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }
        setContentView(FrameLayout(this).apply {
            addView(ComposeView(this@MainActivity).apply { setContent { GazeApp(this@MainActivity) } })
            addView(fullscreen)
        })

        dropPrivateLeftovers()
        restoreSession()
        if (!handle(intent)) {
            if (tabs.isEmpty()) newBlankTab() else show(current)
        }
        loadAds()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
        lifecycleScope.launch(Dispatchers.IO) {
            val folded = places.historyToRewrite()
            if (folded.isNotEmpty()) runCatching { store.write(store.history, folded.toByteArray()) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        if (timersPaused) {
            tabs.firstNotNullOfOrNull { it.web }?.resumeTimers()
            timersPaused = false
        }
        current()?.web?.onResume()
        reloadBookmarksIfChanged()
        receiveTabs()
        watch()
    }

    // Out of sight, every page stops: no scripts, no timers, no wakes.
    override fun onPause() {
        current()?.web?.onPause()
        tabs.firstNotNullOfOrNull { it.web }?.let {
            it.pauseTimers()
            timersPaused = true
        }
        watcher?.stopWatching()
        watcher = null
        saveSession()
        super.onPause()
    }

    fun webHostView(): FrameLayout = webHost

    fun say(text: String) {
        message = text
    }

    // ---------- tabs ----------

    fun current(): Tab? = tabs.getOrNull(current)

    fun show(i: Int) {
        if (tabs.isEmpty()) return
        current = i.coerceIn(0, tabs.lastIndex)
        val tab = tabs[current]
        val web = tab.web ?: (newWebView(this, tab) ?: return dropTab(tab)).also { w ->
            tab.web = w
            darken(w, tab.url)
            // The pages the tab came through in the last run come back with
            // it, so the back key walks them. Without them it opens its page.
            val kept = tab.kept
            tab.kept = null
            if ((kept == null || !w.restorePages(kept)) && tab.url != "about:blank") w.loadUrl(tab.url)
        }
        tabs.forEach { t -> if (t !== tab) t.web?.onPause() }
        webHost.removeAllViews()
        (web.parent as? ViewGroup)?.removeView(web)
        webHost.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        web.onResume()
        bookmarked = places.isBookmarked(tab.url)
        unlockOffer = false
        saveOffer = null
    }

    fun newTab(url: String, title: String = "") {
        tabs.add(Tab(url, title))
        show(tabs.lastIndex)
    }

    fun newBlankTab() {
        newTab("about:blank")
        editing = true
    }

    // ---------- private tabs ----------

    /** WebView can keep a second set of cookies apart since its version 110 or so. */
    private fun privateTabsWork(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    private fun tooOld() = say("This phone's WebView is too old for private tabs")

    fun newPrivateTab() {
        if (!privateTabsWork()) return tooOld()
        tabs.add(Tab("about:blank", "", private = true))
        show(tabs.lastIndex)
        editing = true
    }

    /**
     * The profile the private tabs share: their cookies, cache and site
     * data. It is made for the first private page, under a name no earlier
     * one had, so a new round of private tabs starts as a stranger.
     */
    fun privateProfile(): String = privateName ?: "$PRIVATE${System.currentTimeMillis()}".also {
        ProfileStore.getInstance().getOrCreateProfile(it)
        privateName = it
    }

    /** With the last private tab go its cookies, cache and site data. */
    private fun endPrivate() {
        val name = privateName ?: return
        privateName = null
        // WebView lets go of a profile once its last page is gone, one beat later.
        webHost.post { runCatching { ProfileStore.getInstance().deleteProfile(name) } }
    }

    /** Private pages that Android cut short left their profile on disk. It goes now. */
    private fun dropPrivateLeftovers() {
        if (!privateTabsWork()) return
        runCatching {
            val all = ProfileStore.getInstance()
            all.allProfileNames.filter { it.startsWith(PRIVATE) }.forEach { all.deleteProfile(it) }
        }
    }

    /** A private tab that got no profile of its own is closed, never loaded. */
    private fun dropTab(tab: Tab) {
        tabs.remove(tab)
        say("Could not open a private tab")
        if (tabs.isEmpty()) newBlankTab() else show(current)
    }

    // ---------- a link held down ----------

    fun holdLink(url: String) {
        if (url.isNotEmpty() && !url.startsWith("javascript:")) heldLink = url
    }

    /**
     * A tab behind this one: the page you are reading stays. It loads when
     * you go to it. From a private tab it is private too.
     */
    fun openBehind(url: String, private: Boolean = current()?.private == true) {
        if (private && !privateTabsWork()) return tooOld()
        tabs.add(Tab(url, "", private))
        say(if (private) "Opened in a private tab" else "Opened in a new tab")
    }

    /** Android shows what was copied by itself. */
    fun copyLink(url: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", url))
    }

    fun closeTab(i: Int) {
        if (i !in tabs.indices) return
        val t = tabs.removeAt(i)
        dropHandovers(t)
        t.web?.let {
            webHost.removeView(it)
            it.destroy()
        }
        if (t.private && tabs.none { it.private }) endPrivate()
        if (i < current) current--
        if (tabs.isEmpty()) newBlankTab() else show(current)
    }

    /** Go where the address line says, in this tab. */
    fun open(input: String) {
        val url = gazeToUri(input, prefs.search)
        val tab = current() ?: return newTab(url)
        tab.url = url
        val web = tab.web ?: return show(current)
        darken(web, url)
        web.loadUrl(url)
    }

    private fun saveSession() {
        // Private tabs are in no file. The current tab is the kept one at or before it.
        val kept = tabs.filter { !it.private }
        val at = (tabs.take(current + 1).count { !it.private } - 1).coerceAtLeast(0)
        val text = buildString {
            appendLine(at)
            kept.forEach { append(gazeTabText(it.url, it.title)) }
        }
        runCatching { store.write(store.session, text.toByteArray()) }
        savePages(kept)
    }

    /**
     * The pages each tab came through, beside the session. Android stops
     * an app it cannot see whenever it wants the memory; without this the
     * tabs came back with one page each, and the back key left gaze.
     */
    private fun savePages(kept: List<Tab>) {
        val out = ByteArrayOutputStream()
        runCatching {
            DataOutputStream(out).use { d ->
                d.writeInt(kept.size)
                kept.forEach { t ->
                    val url = t.url.toByteArray()
                    val pages = t.web?.pages() ?: t.kept
                    d.writeInt(url.size)
                    d.write(url)
                    d.writeInt(pages?.size ?: 0)
                    pages?.let { d.write(it) }
                }
            }
        }.onFailure { return }
        val bytes = out.toByteArray()
        if (bytes.contentEquals(pagesWritten)) return
        runCatching { store.write(store.pages, bytes) }.onSuccess { pagesWritten = bytes }
    }

    private fun restoreSession() {
        val lines = store.readText(store.session).lines()
        lines.drop(1).mapNotNull { gazeTabParse(it) }.forEach { tabs.add(Tab(it.url, it.title)) }
        current = (lines.firstOrNull()?.toIntOrNull() ?: 0).coerceIn(0, maxOf(0, tabs.lastIndex))
        restorePages()
    }

    /** Each tab gets its pages back, when the file is about these tabs. */
    private fun restorePages() {
        runCatching {
            DataInputStream(store.pages.inputStream().buffered()).use { d ->
                if (d.readInt() != tabs.size) return
                tabs.forEach { t ->
                    val url = String(ByteArray(d.readInt()).also { d.readFully(it) })
                    val pages = ByteArray(d.readInt()).also { d.readFully(it) }
                    if (url == t.url && pages.isNotEmpty()) t.kept = pages
                }
            }
        }
    }

    /** A link from another app, or text shared to gaze. True when it opened a tab. */
    private fun handle(intent: Intent?): Boolean {
        val url = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                Regex("https?://\\S+").find(text)?.value ?: gazeToUri(text, prefs.search)
            }
            else -> null
        } ?: return false
        setIntent(Intent())
        newTab(url)
        return true
    }

    private fun back() {
        val web = current()?.web
        when {
            fullscreen.visibility == View.VISIBLE -> hideFullscreen(fromPage = false)
            screen != Screen.Browser -> screen = Screen.Browser
            editing -> editing = false
            web != null && web.canGoBack() -> web.goBack()
            else -> moveTaskToBack(true)
        }
    }

    // ---------- pages ----------

    /** Dark pages as the site's choice or the default says. */
    fun darken(web: WebView, url: String) {
        val on = prefs.darkFor(gazeSiteKey(url))
        if (web.settings.isAlgorithmicDarkeningAllowed != on) web.settings.isAlgorithmicDarkeningAllowed = on
    }

    fun toggleDark() {
        val tab = current() ?: return
        val site = gazeSiteKey(tab.url)
        val on = !prefs.darkFor(site)
        prefs.setDark(site, on)
        tab.web?.let {
            darken(it, tab.url)
            it.reload()
        }
        say(if (on) "Dark pages here" else "Light pages here")
    }

    fun pageFinished(tab: Tab, web: WebView, url: String) {
        tab.url = url
        if (tab === current()) bookmarked = places.isBookmarked(url)
        // A private tab is in no history, and a login is filled only when asked.
        if (tab.private) return
        val line = places.visit(url, tab.title)
        if (line.isNotEmpty()) runCatching { store.history.appendText(line) }
        if (url.startsWith("http")) offerLogin(tab, web, url)
    }

    /** Fill the login used last on this site, or offer to unlock the passwords. */
    fun offerLogin(tab: Tab, web: WebView, url: String) {
        if (vault.unlocked) {
            vault.refresh()
            val l = vault.forSite(url).firstOrNull() ?: return
            web.evaluateJavascript(
                "window.__gaze && __gaze.fill(${JSONObject.quote(l.username)}, ${JSONObject.quote(l.password)})", null)
        } else if (store.passwords.exists()) {
            web.evaluateJavascript("window.__gaze ? __gaze.hasPasswordField() : false") { r ->
                if (r == "true" && tab === current()) unlockOffer = true
            }
        }
    }

    fun fillLogin() {
        val tab = current() ?: return
        val web = tab.web ?: return
        if (!vault.unlocked) {
            askMaster = true
            return
        }
        vault.refresh()
        if (vault.forSite(tab.url).isEmpty()) say("No saved login for this site") else offerLogin(tab, web, tab.url)
    }

    /** A message from the page script. */
    fun fromPage(tab: Tab, json: String) {
        val m = runCatching { JSONObject(json) }.getOrNull() ?: return
        // Nothing from a private tab is offered for saving.
        if (m.optString("t") == "file") return filePiece(tab, m)
        if (m.optString("t") != "login" || !vault.unlocked || tab.private) return
        // The site is the tab's own address, never what the page claims.
        val page = Uri.parse(tab.web?.url ?: return)
        val login = Login("${page.scheme}://${page.authority}", m.optString("username"), m.optString("password"), 0uL)
        if (login.password.isEmpty()) return
        if (gazeRemember(vault.logins, login).change != LoginChange.SAME && tab === current()) saveOffer = login
    }

    fun saveOffered() {
        val l = saveOffer ?: return
        saveOffer = null
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching { vault.change { gazeRemember(it, l).logins } }.getOrElse { writeProblem() }
            }
            vaultVersion++
            say(err ?: "Password saved")
        }
    }

    fun forget(l: Login) {
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching { vault.change { gazeForget(it, l.origin, l.username) } }.getOrElse { writeProblem() }
            }
            vaultVersion++
            say(err ?: "Forgot ${l.username} on ${l.origin}")
        }
    }

    fun unlock(master: String) {
        unlocking = true
        lifecycleScope.launch {
            val fresh = !store.passwords.exists()
            val err = withContext(Dispatchers.IO) { runCatching { vault.unlock(master) }.getOrElse { it.message } }
            unlocking = false
            if (err != null) return@launch say(err)
            askMaster = false
            unlockOffer = false
            vaultVersion++
            say(if (fresh) "A new password store: it is written with the first login you save" else "${vault.logins.size} logins")
            current()?.let { t -> t.web?.let { if (t.url.startsWith("http")) offerLogin(t, it, t.url) } }
        }
    }

    fun lock() {
        vault.lock()
        vaultVersion++
        say("Passwords locked")
    }

    private fun writeProblem(): String =
        if (store.canReachSync()) "Could not write to ${prefs.syncDir}" else "gaze may not reach ${prefs.syncDir}: allow file access in the settings"

    // ---------- bookmarks ----------

    fun toggleBookmark() {
        val t = current() ?: return
        if (!t.url.startsWith("http")) return
        val (url, title) = t.url to t.title
        lifecycleScope.launch {
            val on = !bookmarked
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    val now = store.readText(store.bookmarks)
                    val text = if (on) places.bookmark(now, url, title) else places.unbookmark(now, url)
                    store.write(store.bookmarks, text.toByteArray())
                    bookmarksSeen = store.bookmarks.lastModified()
                    null
                }.getOrElse { writeProblem() }
            }
            if (err != null) return@launch say(err)
            bookmarked = on
            say(if (on) "Bookmarked" else "Bookmark removed")
        }
    }

    private fun reloadBookmarksIfChanged() {
        val m = store.bookmarks.lastModified()
        if (m == bookmarksSeen) return
        bookmarksSeen = m
        places.setBookmarks(store.readText(store.bookmarks))
        current()?.let { bookmarked = places.isBookmarked(it.url) }
    }

    // ---------- tabs sent between the phone and the laptop ----------

    fun sendToLaptop() {
        val t = current() ?: return
        if (!t.url.startsWith("http")) return say("Nothing to send")
        val (url, title) = t.url to t.title
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) { runCatching { store.send(url, title); null }.getOrElse { writeProblem() } }
            say(err ?: "Sent to the laptop")
        }
    }

    /** The page to another app, through Android's share sheet. */
    fun share() {
        val t = current() ?: return
        if (!t.url.startsWith("http")) return say("Nothing to share")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, t.url)
            .putExtra(Intent.EXTRA_TITLE, t.title)
        startActivity(chooser(send))
    }

    /** Android's share list, without gaze itself in it. */
    private fun chooser(send: Intent): Intent = Intent.createChooser(send, null)
        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, MainActivity::class.java)))

    private fun receiveTabs() {
        if (!store.canReachSync()) return
        lifecycleScope.launch {
            val got = withContext(Dispatchers.IO) { runCatching { store.received() }.getOrDefault(emptyList()) }
            got.forEach { newTab(it.url, it.title) }
            if (got.isNotEmpty()) say(if (got.size == 1) "A tab from the laptop" else "${got.size} tabs from the laptop")
        }
    }

    /** While gaze is on screen, a tab from the laptop opens as it lands. */
    private fun watch() {
        if (watcher != null || !store.canReachSync()) return
        val dir = store.toPhone
        dir.mkdirs()
        watcher = object : FileObserver(dir, CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && !path.startsWith(".")) runOnUiThread { receiveTabs() }
            }
        }.also { it.startWatching() }
    }

    // ---------- Claude ----------

    /**
     * The page to the Claude app, through Android's share: its title, its
     * address and its text. The question is asked there, on your own plan.
     */
    fun askClaude() {
        val tab = current() ?: return
        val web = tab.web ?: return
        if (!tab.url.startsWith("http")) return say("Nothing to ask about")
        web.evaluateJavascript("document.body ? document.body.innerText : ''") { json ->
            val text = runCatching { JSONTokener(json).nextValue() as? String }.getOrNull() ?: ""
            // An intent carries at most about a megabyte, two bytes a character.
            if (text.length > PAGE_MAX) say("A long page: Claude gets the first 100,000 characters")
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "${tab.title}\n${tab.url}\n\n${text.take(PAGE_MAX)}")
                .putExtra(Intent.EXTRA_TITLE, tab.title)
            try {
                startActivity(Intent(send).setPackage(CLAUDE_APP))
            } catch (e: ActivityNotFoundException) {
                startActivity(chooser(send))
            }
        }
    }

    // ---------- ads ----------

    fun loadAds(fetch: Boolean = false) {
        if (!prefs.adblock) {
            ads = null
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            if (fetch || !store.hosts.exists()) {
                val got = runCatching { fetch(HOSTS_URL) }
                got.onSuccess { runCatching { store.write(store.hosts, it) } }
                if (got.isFailure) {
                    withContext(Dispatchers.Main) { say("Could not fetch the ad list") }
                    if (!store.hosts.exists()) return@launch
                }
            }
            val list = AdList(store.hosts.readText())
            ads = list
            if (fetch) withContext(Dispatchers.Main) { say("The ad list holds ${list.size()} domains") }
        }
    }

    private fun fetch(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    // ---------- what the WebView hands over ----------

    /** A link WebView cannot show: mail, phone, another app. */
    fun openElsewhere(uri: Uri) {
        val intent = if (uri.scheme == "intent") {
            runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return
        } else {
            Intent(Intent.ACTION_VIEW, uri)
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (fallback != null) open(fallback) else say("No app opens ${uri.scheme}: links")
        } catch (e: Exception) {
            say("Could not open that link")
        }
    }

    // ---------- downloads ----------

    /**
     * A download the page asked for, or one you asked for with "Download
     * link" or "Download page". A web address goes to Android's download
     * manager. A file the page made itself (a recording, an export) has a
     * blob: or data: address that only the page can read, so the page
     * hands its bytes over.
     */
    fun download(tab: Tab, url: String, agent: String, disposition: String?, mime: String?) {
        val name = URLUtil.guessFileName(url, disposition, mime)
        when {
            url.startsWith("http") -> fromWeb(tab, url, agent, name, mime)
            url.startsWith("blob:") -> fromBlob(tab, url, name, mime)
            url.startsWith("data:") -> fromData(url, name, mime)
            else -> say("gaze cannot save this kind of download")
        }
    }

    /** "Download link" on a held link and "Download page" in the menu: a sound file plays when tapped, this saves it. */
    fun downloadUrl(url: String?) {
        val tab = current() ?: return
        if (url.isNullOrEmpty() || url == "about:blank") return say("Nothing to download here")
        download(tab, url, tab.web?.settings?.userAgentString.orEmpty(), null, null)
    }

    private fun fromWeb(tab: Tab, url: String, agent: String, name: String, mime: String?) {
        val req = DownloadManager.Request(Uri.parse(url))
            .setMimeType(mime)
            .addRequestHeader("User-Agent", agent)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        // A private tab's download carries the private cookies, never yours.
        val cookies = if (tab.private) privateName?.let { ProfileStore.getInstance().getProfile(it)?.cookieManager }
                      else CookieManager.getInstance()
        cookies?.getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
        runCatching { getSystemService(DownloadManager::class.java).enqueue(req) }
            .onSuccess { say("Downloading $name") }
            .onFailure { say("Could not download $name") }
    }

    /** Downloads a page is handing over, by the key each one got from this app. */
    private val handovers = HashMap<String, Handover>()

    /**
     * Ask the page for the bytes behind a blob: address. The script reads
     * them and posts them in pieces under a key made here, for this one
     * download. [filePiece] takes nothing without that key.
     */
    private fun fromBlob(tab: Tab, url: String, name: String, mime: String?) {
        val web = tab.web ?: return
        val key = java.util.UUID.randomUUID().toString()
        handovers[key] = Handover(tab, name, mime)
        say("Downloading $name")
        web.evaluateJavascript(blobScript(url, key), null)
    }

    /** One piece of a file a page hands over. Called for every "file" message from a page. */
    private fun filePiece(tab: Tab, m: JSONObject) {
        val key = m.optString("k")
        val h = handovers[key] ?: return
        if (h.tab !== tab) return
        fun drop() {
            handovers.remove(key)
            h.file?.drop()
            say("Could not download ${h.name}")
        }
        if (m.optBoolean("fail")) return drop()
        runCatching {
            val file = h.file ?: run {
                // The name the page gave its link, where it gave one.
                safeName(m.optString("n"))?.let { h.name = it }
                Saver(contentResolver, h.name, h.mime).also { h.file = it }
            }
            file.write(Base64.decode(m.optString("d"), Base64.DEFAULT))
            if (m.optBoolean("end")) {
                file.done()
                handovers.remove(key)
                say("Saved ${h.name} in Downloads")
            }
        }.onFailure { drop() }
    }

    /** A data: address has the file in the address itself. */
    private fun fromData(url: String, name: String, mime: String?) {
        runCatching {
            val comma = url.indexOf(',')
            require(comma > 0)
            val head = url.substring(5, comma)
            val body = url.substring(comma + 1)
            val bytes = if (head.endsWith(";base64")) Base64.decode(body, Base64.DEFAULT) else Uri.decode(body).toByteArray()
            val file = Saver(contentResolver, name, mime ?: head.substringBefore(';').ifEmpty { null })
            runCatching { file.write(bytes); file.done() }.onFailure { file.drop() }.getOrThrow()
        }.onSuccess { say("Saved $name in Downloads") }
            .onFailure { say("Could not download $name") }
    }

    /** A page that closes takes its unfinished downloads with it. */
    private fun dropHandovers(tab: Tab) {
        handovers.values.filter { it.tab === tab }.forEach { it.file?.drop() }
        handovers.values.removeAll { it.tab === tab }
    }

    fun chooseFile(cb: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams) {
        fileCallback?.onReceiveValue(null)
        fileCallback = cb
        try {
            pickFile.launch(params.createIntent())
        } catch (e: ActivityNotFoundException) {
            fileCallback = null
            cb.onReceiveValue(null)
        }
    }

    fun showFullscreen(view: View, cb: WebChromeClient.CustomViewCallback) {
        hideFullscreen(fromPage = false)
        customCallback = cb
        fullscreen.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        fullscreen.visibility = View.VISIBLE
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    fun hideFullscreen(fromPage: Boolean) {
        if (fullscreen.visibility != View.VISIBLE) return
        fullscreen.removeAllViews()
        fullscreen.visibility = View.GONE
        window.insetsController?.show(WindowInsets.Type.systemBars())
        val cb = customCallback
        customCallback = null
        if (!fromPage) cb?.onCustomViewHidden()
    }

    fun askFileAccess() {
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
    }
}
