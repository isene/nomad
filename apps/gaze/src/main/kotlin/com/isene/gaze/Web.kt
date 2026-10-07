package com.isene.gaze

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebViewCompat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * One tab. Its WebView is made when the tab is first shown. A private tab
 * is in no history and no file, and keeps its cookies apart from the rest.
 */
class Tab(url: String, title: String, val private: Boolean = false) {
    var url by mutableStateOf(url)
    var title by mutableStateOf(title)
    var progress by mutableIntStateOf(100)
    var web: WebView? = null
    /** The pages the tab came through in the last run, until its WebView is made. */
    var kept: ByteArray? = null
}

/**
 * The pages this WebView came through, as bytes for a file: what it keeps
 * for "back" and "forward". Null when it has been nowhere.
 */
fun WebView.pages(): ByteArray? {
    val state = Bundle()
    if (saveState(state) == null) return null
    val out = ByteArrayOutputStream()
    DataOutputStream(out).use { d ->
        for (key in state.keySet()) {
            val bytes = state.getByteArray(key) ?: continue
            d.writeUTF(key)
            d.writeInt(bytes.size)
            d.write(bytes)
        }
    }
    return out.toByteArray().takeIf { it.isNotEmpty() }
}

/**
 * Give a new WebView the pages a tab came through; it opens the last one.
 * False when the bytes are of no use.
 */
fun WebView.restorePages(saved: ByteArray): Boolean = runCatching {
    val state = Bundle()
    DataInputStream(saved.inputStream()).use { d ->
        while (d.available() > 0) {
            val key = d.readUTF()
            state.putByteArray(key, ByteArray(d.readInt()).also { d.readFully(it) })
        }
    }
    restoreState(state) != null
}.getOrDefault(false)

/**
 * A WebView that reloads when you pull down past the top of the page.
 * The pull counts only when the drag starts at the top and the page has
 * no scrolling left, so a map or a scrolling panel keeps its drag.
 */
@SuppressLint("ViewConstructor")
class PageView(private val a: MainActivity, private val tab: Tab) : WebView(a) {
    private val far = 120 * resources.displayMetrics.density

    /** In a private tab the keyboard is asked to learn nothing of what is typed. */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
        super.onCreateInputConnection(outAttrs).also {
            if (tab.private) outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
    private var atTop = false
    private var past = false
    private var from = -1f

    override fun onOverScrolled(x: Int, y: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(x, y, clampedX, clampedY)
        if (atTop && clampedY && y == 0) past = true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                atTop = scrollY == 0
                past = false
                from = -1f
            }
            MotionEvent.ACTION_MOVE -> if (past) {
                if (from < 0) from = e.y
                a.pull = ((e.y - from) / far).coerceIn(0f, 1f)
            }
            MotionEvent.ACTION_UP -> {
                if (a.pull >= 1f) reload()
                stop()
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> stop()
        }
        return super.onTouchEvent(e)
    }

    private fun stop() {
        atTop = false
        past = false
        a.pull = 0f
    }
}

/**
 * The WebView of a tab. Null for a private tab that could not get its own
 * profile: such a tab must not load at all, or it would use your cookies.
 */
@SuppressLint("SetJavaScriptEnabled")
fun newWebView(a: MainActivity, tab: Tab): WebView? {
    val view = PageView(a, tab)
    if (tab.private) {
        // WebView takes this only before anything else is done with the view.
        if (runCatching { WebViewCompat.setProfile(view, a.privateProfile()) }.isFailure) {
            view.destroy()
            return null
        }
        // Android's own autofill neither fills nor saves in a private tab.
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    return view.apply { setUp(a, tab) }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.setUp(a: MainActivity, tab: Tab) {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        useWideViewPort = true
        loadWithOverviewMode = true
        builtInZoomControls = true
        displayZoomControls = false
        mediaPlaybackRequiresUserGesture = true
        // A link to a new window opens in this tab.
        setSupportMultipleWindows(false)
    }
    addJavascriptInterface(Bridge(a, tab), "gazeBridge")

    // Holding a link asks what to do with it. Holding anything else keeps
    // WebView's own answer: marking text.
    setOnLongClickListener {
        val type = hitTestResult.type
        if (type != WebView.HitTestResult.SRC_ANCHOR_TYPE && type != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            return@setOnLongClickListener false
        }
        // The address comes back as a message, also for a picture inside a link.
        requestFocusNodeHref(Handler(Looper.getMainLooper()) { m ->
            a.holdLink(m.data.getString("url").orEmpty())
            true
        }.obtainMessage())
        true
    }

    webViewClient = object : WebViewClient() {
        // Everything a page pulls in is checked against the ad list. The
        // page you asked for never is: a password reset can arrive
        // through a click tracker on the list.
        override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
            if (req.isForMainFrame) return null
            val host = req.url.host ?: return null
            val ads = a.ads ?: return null
            if (!ads.blocks(host)) return null
            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }

        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean =
            when (req.url.scheme) {
                "http", "https", "about", "data", "blob", "javascript" -> {
                    if (req.isForMainFrame) a.darken(view, req.url.toString())
                    false
                }
                else -> {
                    a.openElsewhere(req.url)
                    true
                }
            }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            tab.url = url
            a.darken(view, url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            view.evaluateJavascript(PAGE_SCRIPT, null)
            a.pageFinished(tab, view, url)
        }
    }

    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, p: Int) {
            tab.progress = p
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            tab.title = title ?: ""
        }

        override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            a.chooseFile(cb, params)
            return true
        }

        override fun onShowCustomView(view: View, cb: CustomViewCallback) = a.showFullscreen(view, cb)
        override fun onHideCustomView() = a.hideFullscreen(fromPage = true)
    }

    setDownloadListener { url, agent, disposition, mime, _ -> a.download(tab, url, agent, disposition, mime) }
}

/** What the page script says reaches the app here, on a WebView thread. */
class Bridge(private val a: MainActivity, private val tab: Tab) {
    @JavascriptInterface
    fun post(json: String) {
        a.runOnUiThread { a.fromPage(tab, json) }
    }
}
