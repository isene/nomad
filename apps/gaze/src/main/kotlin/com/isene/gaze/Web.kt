package com.isene.gaze

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayInputStream
import uniffi.fe2o3_mobile_core.Chat

/** One tab. Its WebView is made when the tab is first shown. */
class Tab(url: String, title: String) {
    var url by mutableStateOf(url)
    var title by mutableStateOf(title)
    var progress by mutableIntStateOf(100)
    var web: WebView? = null

    /** The talk with Claude about the page, and the page it was about. */
    var chat: Chat? = null
    var chatUrl = ""
    val talk = mutableStateListOf<Said>()
}

/** One line of the talk with Claude. */
class Said(val question: Boolean, text: String) {
    var text by mutableStateOf(text)
    var error by mutableStateOf(false)
}

@SuppressLint("SetJavaScriptEnabled")
fun newWebView(a: MainActivity, tab: Tab): WebView = WebView(a).apply {
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

    setDownloadListener { url, agent, disposition, mime, _ -> a.download(url, agent, disposition, mime) }
}

/** What the page script says reaches the app here, on a WebView thread. */
class Bridge(private val a: MainActivity, private val tab: Tab) {
    @JavascriptInterface
    fun post(json: String) {
        a.runOnUiThread { a.fromPage(tab, json) }
    }
}
