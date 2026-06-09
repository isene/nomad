package com.isene.onepage

import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The single home page. A plain FrameLayout whose children are
 * AppWidgetHostViews placed absolutely via leftMargin/topMargin — no grid,
 * no snap, overlap allowed.
 *
 * VIEW mode (default): widgets receive their own touches; the surface only
 * sees empty-space events, where a long-press enters EDIT. Zero drawing, zero
 * layout work when idle — widgets invalidate themselves on RemoteViews pushes
 * and Android composites the wallpaper without involving us.
 *
 * EDIT mode: the surface intercepts all widget-area touches. Drag moves,
 * two-finger pinch resizes, long-press pops Resize/Remove, a bottom chrome
 * bar offers Add widget / Setup / Done. Done persists and leaves.
 */
class HomeSurface(context: Context) : FrameLayout(context) {

    class Entry(val appWidgetId: Int, val provider: String, val view: AppWidgetHostView)

    var widgetHost: AppWidgetHost? = null
    var widgetManager: AppWidgetManager? = null
    var onAddWidget: (() -> Unit)? = null
    var onSetupAgain: (() -> Unit)? = null
    var onPersist: (() -> Unit)? = null

    val entries = mutableListOf<Entry>()
    private var editMode = false

    private val density = resources.displayMetrics.density
    private val minSizePx = (48 * density).roundToInt()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // ---- edit chrome (built once, attached only in EDIT mode) ----

    private val chrome: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(0xCC202428.toInt())
        val ph = (12 * density).roundToInt()
        val pv = (4 * density).roundToInt()
        setPadding(ph, pv, ph, pv)
        addView(chromeButton("+ Widget") { onAddWidget?.invoke() })
        addView(chromeButton("Setup") { onSetupAgain?.invoke() })
        addView(chromeButton("Done") { exitEdit() })
    }

    private fun chromeButton(label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = (6 * density).roundToInt()
                marginEnd = (6 * density).roundToInt()
            }
        }

    /** Bottom system-bar inset (3-button nav bar / gesture pill). The window
     *  is edge-to-edge, so the chrome must sit ABOVE this or its buttons land
     *  behind the nav bar and become untappable. Updated by the insets
     *  listener (event-driven, fires only on inset changes). */
    private var bottomInset = 0

    init {
        setOnApplyWindowInsetsListener { _, insets ->
            bottomInset = insets.getInsets(android.view.WindowInsets.Type.systemBars()).bottom
            if (chrome.parent === this) positionChrome()
            insets
        }
    }

    private fun positionChrome() {
        val lp = (chrome.layoutParams as? LayoutParams)
            ?: LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.bottomMargin = bottomInset + (24 * density).roundToInt()
        chrome.layoutParams = lp
    }

    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = 0xAAFFFFFF.toInt()
    }

    // ---- VIEW-mode gesture: long-press on empty space enters EDIT ----

    private val viewModeDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (!editMode) enterEdit()
            }
            override fun onDown(e: MotionEvent): Boolean = true
        },
    )

    // ---- EDIT-mode touch state ----

    private var active: Entry? = null
    private var downOnChrome = false
    private var moved = false
    private var resizing = false
    private var downX = 0f
    private var downY = 0f
    private var startLeft = 0
    private var startTop = 0
    private var startW = 0
    private var startH = 0
    private var startDist = 1f

    private val longPress = Runnable {
        if (moved || resizing) return@Runnable
        val e = active
        if (e != null) {
            active = null
            showWidgetMenu(e)
        } else {
            // Long-press on EMPTY space while editing: toggle edit mode off.
            // Escape hatch so the user can never be trapped in edit mode even
            // if the chrome bar is unreachable for any reason.
            exitEdit()
        }
    }

    // ---- mode transitions ----

    fun isEditMode(): Boolean = editMode

    fun enterEdit() {
        if (editMode) return
        editMode = true
        // Dim the wallpaper: background draws behind the widget children.
        setBackgroundColor(0x66000000)
        addView(chrome)
        positionChrome()
        invalidate()
    }

    fun exitEdit() {
        if (!editMode) return
        editMode = false
        background = null
        removeView(chrome)
        removeCallbacks(longPress)
        active = null
        invalidate()
        onPersist?.invoke()
    }

    // ---- touch routing ----

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!editMode) return false
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downOnChrome = hitsChrome(ev.x, ev.y)
        }
        // Chrome buttons keep their own touches; everything else is ours.
        return !downOnChrome
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!editMode) {
            // Only empty-space events reach here (widgets consume their own).
            viewModeDetector.onTouchEvent(ev)
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                moved = false
                resizing = false
                downX = ev.x
                downY = ev.y
                active = topEntryAt(ev.x, ev.y)
                active?.let { e ->
                    val lp = e.view.layoutParams as LayoutParams
                    startLeft = lp.leftMargin
                    startTop = lp.topMargin
                    startW = lp.width
                    startH = lp.height
                }
                // Posted for widget AND empty space: widget → popup menu,
                // empty space → exit edit mode (see longPress).
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 2) {
                    // Pinch targets whichever widget EITHER finger is on —
                    // with a natural thumb+index pinch the first touch often
                    // lands just outside the widget.
                    if (active == null) {
                        active = topEntryAt(ev.getX(0), ev.getY(0))
                            ?: topEntryAt(ev.getX(1), ev.getY(1))
                    }
                    if (active != null) {
                        removeCallbacks(longPress)
                        resizing = true
                        moved = false
                        startDist = pinchDist(ev).coerceAtLeast(1f)
                        val lp = active!!.view.layoutParams as LayoutParams
                        startW = lp.width
                        startH = lp.height
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val e = active
                if (e == null) {
                    // Empty-space gesture: drifting past slop cancels the
                    // exit-edit long-press (it's a scroll-ish touch, not a hold).
                    if (!moved && (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop)) {
                        moved = true
                        removeCallbacks(longPress)
                    }
                    return true
                }
                if (resizing && ev.pointerCount >= 2) {
                    val scale = pinchDist(ev) / startDist
                    applySize(e, (startW * scale).roundToInt(), (startH * scale).roundToInt())
                } else if (!resizing) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        moved = true
                        removeCallbacks(longPress)
                    }
                    if (moved) {
                        val lp = e.view.layoutParams as LayoutParams
                        lp.leftMargin = startLeft + dx.roundToInt()
                        lp.topMargin = startTop + dy.roundToInt()
                        e.view.layoutParams = lp
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (resizing && ev.pointerCount <= 2) {
                    // Pinch over: commit the size hint, end this gesture's work
                    // (continuing with one finger must not jump-drag).
                    active?.let { commitSizeHint(it) }
                    removeCallbacks(longPress)
                    active = null
                    resizing = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                active = null
                resizing = false
            }
        }
        return true
    }

    private fun hitsChrome(x: Float, y: Float): Boolean {
        if (chrome.parent !== this) return false
        return x >= chrome.left && x <= chrome.right && y >= chrome.top && y <= chrome.bottom
    }

    private fun topEntryAt(x: Float, y: Float): Entry? {
        // Topmost = last in child z-order; walk children back-to-front.
        for (i in childCount - 1 downTo 0) {
            val v = getChildAt(i)
            if (v === chrome) continue
            if (x >= v.left && x <= v.right && y >= v.top && y <= v.bottom) {
                return entries.firstOrNull { it.view === v }
            }
        }
        return null
    }

    private fun pinchDist(ev: MotionEvent): Float =
        hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))

    // ---- widget add / restore / remove / resize ----

    /** Add a freshly picked+configured widget, centered, at its default size. */
    fun addWidget(appWidgetId: Int, info: AppWidgetProviderInfo) {
        val h = widgetHost ?: return
        val view = h.createView(context, appWidgetId, info)
        // minWidth/minHeight are dp; convert with our metrics. No grid, so
        // ignore targetCellWidth/Height entirely. Clamp into the surface.
        val w = (info.minWidth * density).roundToInt()
            .coerceIn(minSizePx, if (width > 0) width else Int.MAX_VALUE)
        val hh = (info.minHeight * density).roundToInt()
            .coerceIn(minSizePx, if (height > 0) height else Int.MAX_VALUE)
        val lp = LayoutParams(w, hh).apply {
            leftMargin = ((if (width > 0) width else w) - w) / 2
            topMargin = ((if (height > 0) height else hh) - hh) / 2
        }
        addView(view, lp)
        val entry = Entry(appWidgetId, info.provider.flattenToString(), view)
        entries.add(entry)
        commitSizeHint(entry)
        if (chrome.parent === this) chrome.bringToFront()
        onPersist?.invoke()
    }

    /** Re-create a widget from the persisted layout. False if its provider is
     *  gone (caller drops the entry from the layout). */
    fun restoreWidget(appWidgetId: Int, provider: String, x: Int, y: Int, w: Int, h: Int): Boolean {
        val host = widgetHost ?: return false
        val info = widgetManager?.getAppWidgetInfo(appWidgetId) ?: return false
        val view = host.createView(context, appWidgetId, info)
        val lp = LayoutParams(w.coerceAtLeast(minSizePx), h.coerceAtLeast(minSizePx)).apply {
            leftMargin = x
            topMargin = y
        }
        addView(view, lp)
        val entry = Entry(appWidgetId, provider, view)
        entries.add(entry)
        commitSizeHint(entry)
        return true
    }

    private fun removeWidget(e: Entry) {
        removeView(e.view)
        entries.remove(e)
        widgetHost?.deleteAppWidgetId(e.appWidgetId)
        invalidate()
        onPersist?.invoke()
    }

    private fun applySize(e: Entry, w: Int, h: Int) {
        val lp = e.view.layoutParams as LayoutParams
        lp.width = w.coerceIn(minSizePx, width.coerceAtLeast(minSizePx))
        lp.height = h.coerceIn(minSizePx, height.coerceAtLeast(minSizePx))
        e.view.layoutParams = lp
        invalidate()
    }

    /** Tell the provider its new size so it can pick the right RemoteViews
     *  layout. Event-driven: only on add/restore/resize-commit. */
    private fun commitSizeHint(e: Entry) {
        val lp = e.view.layoutParams as LayoutParams
        val wDp = (lp.width / density).roundToInt()
        val hDp = (lp.height / density).roundToInt()
        @Suppress("DEPRECATION")
        e.view.updateAppWidgetSize(null, wDp, hDp, wDp, hDp)
    }

    // ---- long-press popup: Resize / Remove ----

    /** Dialogs/popups over a Theme.Wallpaper activity need a real theme. */
    private fun dialogContext(): Context =
        android.view.ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun showWidgetMenu(e: Entry) {
        val menu = PopupMenu(dialogContext(), e.view)
        menu.menu.add("Resize…")
        menu.menu.add("Remove")
        menu.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Remove" -> removeWidget(e)
                "Resize…" -> showResizeDialog(e)
            }
            true
        }
        menu.show()
    }

    private fun showResizeDialog(e: Entry) {
        val lp = e.view.layoutParams as LayoutParams
        val origW = lp.width
        val origH = lp.height
        val pad = (20 * density).roundToInt()

        val dctx = dialogContext()
        fun slider(label: String, max: Int, cur: Int, onChange: (Int) -> Unit): LinearLayout {
            val row = LinearLayout(dctx).apply { orientation = LinearLayout.VERTICAL }
            row.addView(TextView(dctx).apply { text = label })
            row.addView(SeekBar(dctx).apply {
                this.max = max - minSizePx
                progress = (cur - minSizePx).coerceIn(0, this.max)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) onChange(minSizePx + p)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            })
            return row
        }

        val content = LinearLayout(dctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(slider("Width", width.coerceAtLeast(minSizePx), origW) { applySize(e, it, (e.view.layoutParams as LayoutParams).height) })
            addView(slider("Height", height.coerceAtLeast(minSizePx), origH) { applySize(e, (e.view.layoutParams as LayoutParams).width, it) })
        }

        AlertDialog.Builder(dctx)
            .setTitle("Resize widget")
            .setView(content)
            .setPositiveButton("OK") { _, _ -> commitSizeHint(e) }
            .setNegativeButton("Cancel") { _, _ -> applySize(e, origW, origH) }
            .show()
    }

    // ---- edit-mode borders ----

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!editMode) return
        for (e in entries) {
            val v = e.view
            canvas.drawRect(
                v.left.toFloat(), v.top.toFloat(),
                v.right.toFloat(), v.bottom.toFloat(),
                borderPaint,
            )
        }
    }
}
