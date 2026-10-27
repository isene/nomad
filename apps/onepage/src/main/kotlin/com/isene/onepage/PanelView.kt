package com.isene.onepage

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt
import uniffi.fe2o3_mobile_core.Panel
import uniffi.fe2o3_mobile_core.Slot
import uniffi.fe2o3_mobile_core.Target
import uniffi.fe2o3_mobile_core.panelAdd
import uniffi.fe2o3_mobile_core.panelFill
import uniffi.fe2o3_mobile_core.panelFolderAdd
import uniffi.fe2o3_mobile_core.panelFolderDrop
import uniffi.fe2o3_mobile_core.panelFolderMove
import uniffi.fe2o3_mobile_core.panelHas
import uniffi.fe2o3_mobile_core.panelIntoFolder
import uniffi.fe2o3_mobile_core.panelPins
import uniffi.fe2o3_mobile_core.panelPut
import uniffi.fe2o3_mobile_core.panelRest
import uniffi.fe2o3_mobile_core.panelRows
import uniffi.fe2o3_mobile_core.panelSwap

/**
 * The app panel: a card of icons along the bottom of the screen, or at one
 * of its sides. A tap starts an app, a shortcut or a call, or opens a
 * folder as a second card. An empty row parts two groups.
 *
 * One folder may be the folder of all other apps. It has no items of its
 * own: a tap lists every app on the phone that is nowhere else in the
 * panel, with names, since those are the apps the user knows least.
 *
 * Holding the panel switches editing on: a plus shows in every free slot,
 * an icon can be dragged to another slot or onto a folder, and a tap on an
 * icon offers the rest. What is changed is saved at once.
 *
 * The view fills its window, so a tap beside the cards is a tap on it, and
 * that steps back: out of a folder, out of editing, then out of the panel.
 */
class PanelView(context: Context, startEditing: Boolean, private val close: () -> Unit) : FrameLayout(context) {

    /** One line of a menu. */
    private class Choice(val label: String, val run: () -> Unit)

    private val density = resources.displayMetrics.density
    private val pitch = dp(PITCH)
    private val iconPx = dp(ICON)
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val launcher: LauncherApps = context.getSystemService(LauncherApps::class.java)
    private val me = Process.myUserHandle()
    private val main = Handler(Looper.getMainLooper())

    /** How many icons wide the panel is, and how wide one slot is. Both
     *  follow from where the panel is (see [settle]). */
    private var cols = 3
    private var cellW = pitch

    /** How tall one slot is: `pitch`, or less when the rows would not fit
     *  the screen (see [render]). */
    private var cellH = pitch

    private var edit = false

    /** The slot that is being moved by taps, until its new place is tapped. */
    private var moving: Int? = null

    /** The slot of the folder that is open. */
    private var folder: Int? = null

    /** The dialog that is up, so it goes when the panel goes. */
    private var shown: Dialog? = null

    /** True once the panel has left the screen. */
    private var closed = false

    /** The slot a dragged icon is over. */
    private var over: View? = null
    private val spot = IntArray(2)

    private val hint = line(FAINT)
    private val placeLine = line(LINK).apply {
        setOnClickListener {
            Prefs.setPanelPlace(context, nextPlace(Prefs.panelPlace(context)))
            settle()
            render()
        }
    }
    private val gapLine = line(LINK).apply {
        setOnClickListener {
            Prefs.setPanelGap(context, !Prefs.panelGap(context))
            render()
        }
    }
    private val anyApp = line(LINK).apply {
        text = "To open the panel from any app: tap here, then switch OnePage on under Accessibility."
        setOnClickListener { openAccessibility() }
    }
    private val grid = GridLayout(context)

    private val box = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(PAD)
        setPadding(p, p, p, p)
        addView(hint)
        addView(grid)
        addView(placeLine)
        addView(gapLine)
        addView(anyApp)
        // A tap on the card itself goes nowhere, so it does not close it.
        setOnClickListener { if (folder != null) back() }
        setOnLongClickListener {
            toggleEdit()
            true
        }
    }

    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        addView(box)
    }

    private val folderTitle = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        maxLines = 1
    }
    private val folderGrid = GridLayout(context).apply { columnCount = FOLDER_COLS }
    private val folderHint = line(FAINT).apply { text = "Drag an icon to move it. Tap one for more." }

    private val folderBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(CARD)
            cornerRadius = dp(RADIUS).toFloat()
        }
        val p = dp(PAD)
        setPadding(p, p, p, p)
        minimumWidth = pitch * FOLDER_COLS + 2 * p
        val plus = TextView(context).apply {
            text = "+"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
            contentDescription = "Add apps to the folder"
            setOnClickListener { addToFolder() }
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val side = (pitch - iconPx) / 2
            setPadding(side, 0, side, dp(6))
            addView(folderTitle, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(plus, LinearLayout.LayoutParams(dp(32), dp(32)))
        }
        addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wrap))
        // As wide as the icons, so the line folds and the card stays narrow.
        addView(folderHint, LinearLayout.LayoutParams(pitch * FOLDER_COLS, wrap))
        addView(folderGrid)
        setOnClickListener { }
        visibility = View.GONE
    }

    init {
        setOnClickListener { back() }
        addView(scroll, LayoutParams(wrap, wrap))
        addView(folderBox, LayoutParams(wrap, wrap, Gravity.CENTER))
        Icons.retry()
        // An empty panel starts in editing, so there is a plus to tap.
        edit = startEditing || now().slots.isEmpty()
        settle()
        render()
        box.alpha = 0f
        box.translationY = dp(24).toFloat()
        box.animate().alpha(1f).translationY(0f).setDuration(120).start()
    }

    /** Put the card where the user wants it: at the bottom of the left
     *  half of the screen, or three icons wide at a side. */
    private fun settle() {
        val place = Prefs.panelPlace(context)
        val r = dp(RADIUS).toFloat()
        // The short side of the screen, so the card also fits a phone on its side.
        val metrics = resources.displayMetrics
        val short = min(metrics.widthPixels, metrics.heightPixels)
        val room = (if (place == BOTTOM) short / 2 else short) - 2 * dp(PAD)
        cols = columns(place, room, pitch)
        cellW = if (place == BOTTOM) room / cols else pitch
        grid.removeAllViews()
        grid.columnCount = cols
        // Round towards the screen, square against its edge.
        box.background = GradientDrawable().apply {
            setColor(CARD)
            cornerRadii = when (place) {
                BOTTOM -> floatArrayOf(0f, 0f, r, r, 0f, 0f, 0f, 0f)
                LEFT -> floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                else -> floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }
        }
        scroll.layoutParams = LayoutParams(
            wrap, wrap,
            when (place) {
                BOTTOM -> Gravity.BOTTOM or Gravity.START
                LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
                else -> Gravity.END or Gravity.CENTER_VERTICAL
            },
        )
        for (v in listOf(hint, placeLine, gapLine, anyApp)) v.layoutParams = LinearLayout.LayoutParams(cellW * cols, wrap)
        val at = when (place) {
            BOTTOM -> "at the bottom left"
            LEFT -> "at the left"
            else -> "at the right"
        }
        placeLine.text = "The panel is $at. Tap here to move it."
    }

    // ---- stepping back ----

    /** Out of the folder, out of a move, out of editing, out of the panel. */
    fun back() {
        when {
            folder != null -> folder = null
            moving != null -> moving = null
            edit && now().slots.isNotEmpty() -> edit = false
            else -> {
                close()
                return
            }
        }
        render()
    }

    /** The window has the keys while the panel is up, and Back is one. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) back()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDetachedFromWindow() {
        closed = true
        shown?.dismiss()
        shown = null
        super.onDetachedFromWindow()
    }

    private fun toggleEdit() {
        edit = !edit || now().slots.isEmpty()
        moving = null
        render()
    }

    // ---- drawing ----

    private fun render() {
        val panel = now()
        val used = panelRows(panel, cols.toUInt()).toInt()
        val gap = Prefs.panelGap(context)
        // While editing there is room below for a new group after a gap.
        val rows = if (edit) used + SPARE else if (gap) used + 1 else used
        // All of the panel fits on the screen. Editing may scroll, with
        // its spare rows and lines of help.
        cellH = slotHeight(pitch, roomHigh() - 2 * dp(PAD), if (gap) used + 1 else used)
        val wanted = ArrayList<Target>()

        hint.visibility = if (edit) View.VISIBLE else View.GONE
        hint.text = if (moving != null) "Tap where it should go."
        else "Drag an icon to move it. Tap + to add, tap an icon for more. Back when done."
        placeLine.visibility = if (edit) View.VISIBLE else View.GONE
        gapLine.visibility = if (edit) View.VISIBLE else View.GONE
        gapLine.text = if (gap) "The panel ends with an empty row. Tap here to take it away."
        else "The panel ends at its last icons. Tap here to add an empty row below them."
        anyApp.visibility = if (edit && !EdgeService.on) View.VISIBLE else View.GONE

        over = null
        grid.removeAllViews()
        for (i in 0 until rows * cols) {
            grid.addView(cell(panel.slots.getOrNull(i) ?: Slot.Empty, i, wanted), cellW, cellH)
        }

        val at = folder
        val open = at?.let { panel.slots.getOrNull(it) as? Slot.Folder }
        folderGrid.removeAllViews()
        if (at == null || open == null) {
            folder = null
            folderBox.visibility = View.GONE
        } else {
            folderTitle.text = open.name
            folderHint.visibility = if (edit) View.VISIBLE else View.GONE
            open.items.forEachIndexed { n, target ->
                val v = icon(target, pitch, pitch, wanted)
                v.background = ripple()
                // As in the panel: a tap starts it and holding switches
                // editing on. While editing, a drag moves it and a tap
                // asks what else to do with it.
                v.setOnClickListener { anchor ->
                    if (!edit) start(target)
                    else menu(
                        anchor,
                        *listOfNotNull(
                            Choice("Out to the panel") { outOfFolder(at, n) },
                            quietChoice(target),
                            Choice("Remove") { dropFromFolder(at, n) },
                        ).toTypedArray(),
                    )
                }
                v.setOnLongClickListener {
                    if (!edit) toggleEdit()
                    true
                }
                if (edit) drag(v, n, folderGrid)
                folderGrid.addView(v, pitch, pitch)
            }
            folderBox.visibility = View.VISIBLE
        }
        scroll.alpha = if (open != null) 0.35f else 1f

        // The first time an icon is shown it is drawn off this thread, and
        // the panel is laid out again when the batch is done.
        Icons.load(context, wanted, iconPx) { if (!closed) render() }
    }

    private fun cell(slot: Slot, i: Int, wanted: MutableList<Target>): View {
        val v: View = when (slot) {
            is Slot.Empty -> if (edit) sign("+", FAINT, 20f) else View(context)
            is Slot.One -> icon(slot.target, cellW, cellH, wanted)
            is Slot.Folder -> folderSign(slot)
        }
        // A free slot outside editing takes no touch: it is part of the card.
        if (slot !is Slot.Empty || edit) {
            v.background = ripple()
            v.setOnClickListener { tap(i, it) }
            // Holding switches editing on. While editing, holding an icon
            // is the start of a drag, so it must not switch editing off.
            v.setOnLongClickListener {
                if (!edit) toggleEdit()
                true
            }
        }
        if (edit && slot !is Slot.Empty) drag(v, i, grid)
        if (i == moving) v.alpha = 0.35f
        return v
    }

    private fun icon(target: Target, width: Int, height: Int, wanted: MutableList<Target>): ImageView = ImageView(context).apply {
        val side = iconPx * height / pitch
        setPadding((width - side) / 2, (height - side) / 2, (width - side) / 2, (height - side) / 2)
        val bitmap = Icons.cached(target)
        if (bitmap != null) {
            setImageBitmap(bitmap)
        } else {
            // Not drawn yet, or gone from the phone.
            if (!Icons.isGone(target)) wanted.add(target)
            setImageDrawable(
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(1), 0x55FFFFFF)
                },
            )
        }
    }

    /** A folder in the panel: the sign the user chose for it, or the first
     *  letter of its name. */
    private fun folderSign(slot: Slot.Folder): View {
        val drawn = if (slot.icon.isEmpty()) null else Signs.drawable(context, slot.icon, Color.WHITE)
        if (drawn == null) return sign(face(slot.name), Color.WHITE, 22f)
        return ImageView(context).apply {
            val side = dp(SIGN) * cellH / pitch
            setPadding((cellW - side) / 2, (cellH - side) / 2, (cellW - side) / 2, (cellH - side) / 2)
            setImageDrawable(drawn)
            contentDescription = slot.name
        }
    }

    private fun sign(what: String, color: Int, size: Float): TextView = TextView(context).apply {
        text = what
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        gravity = Gravity.CENTER
        includeFontPadding = false
    }

    private fun line(color: Int): TextView = TextView(context).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        val side = (pitch - iconPx) / 2
        setPadding(side, dp(2), side, dp(6))
    }

    /** The height of the screen between the bars at top and bottom, where
     *  the panel's window is. */
    private fun roomHigh(): Int {
        val m = context.getSystemService(WindowManager::class.java).currentWindowMetrics
        val bars = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        return m.bounds.height() - bars.top - bars.bottom
    }

    private fun ripple(): Drawable = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null)

    private fun dp(n: Int): Int = (n * density).roundToInt()

    // ---- dragging ----

    /** While editing, an icon follows the finger and lands in the slot it
     *  is let go over. A finger that stays put is a tap, as before.
     *
     *  `on` is where the icon lives: the panel's `grid`, or `folderGrid`
     *  for an icon of the open folder. */
    @SuppressLint("ClickableViewAccessibility")
    private fun drag(v: View, from: Int, on: GridLayout) {
        val drag = Drag(slop.toFloat())
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    drag.down(ev.rawX, ev.rawY)
                    // The card must not scroll away under a drag.
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    // The panel's own icons lie still under an open folder,
                    // and while one of them is being moved by taps.
                    val free = on === folderGrid || (moving == null && folder == null)
                    if (free && drag.move(ev.rawX, ev.rawY)) {
                        v.cancelLongPress()
                        v.isPressed = false
                        // Above its neighbours, and a little larger.
                        v.translationZ = dp(8).toFloat()
                        v.scaleX = 1.15f
                        v.scaleY = 1.15f
                    }
                    if (drag.on) {
                        v.translationX = drag.dx(ev.rawX)
                        v.translationY = drag.dy(ev.rawY)
                        mark(slotUnder(ev, on)?.let { on.getChildAt(it) })
                    }
                    drag.on
                }
                // The drag ends before the drop, which draws the panel
                // again. Android cancels the touch of the icon that drawing
                // removes, and the cancel below must find no drag by then.
                MotionEvent.ACTION_UP -> if (drag.end()) {
                    drop(from, slotUnder(ev, on), on)
                    true
                } else {
                    false
                }
                // Taken away mid-drag: the icon goes back, but only after
                // Android is done with it. Never draw the panel from here.
                MotionEvent.ACTION_CANCEL -> {
                    if (drag.end()) post { if (!closed) render() }
                    false
                }
                else -> false
            }
        }
    }

    /** The slot of `on` under the finger. In a folder that may be a free
     *  place after its last icon. */
    private fun slotUnder(ev: MotionEvent, on: GridLayout): Int? {
        on.getLocationOnScreen(spot)
        val n = on.columnCount
        val (width, height) = if (on === grid) cellW to cellH else pitch to pitch
        return slotAt(ev.rawX - spot[0], ev.rawY - spot[1], width, height, n, (on.childCount + n - 1) / n)
    }

    /** Frame the slot the dragged icon would land in. */
    private fun mark(next: View?) {
        if (next === over) return
        over?.foreground = null
        next?.foreground = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), 0x66FFFFFF)
        }
        over = next
    }

    /** In the panel an icon dropped on a folder goes into it, and anywhere
     *  else the two slots change places, of which one may be free. In a
     *  folder the icon takes the place it lands on and the others close up. */
    private fun drop(from: Int, to: Int?, on: GridLayout) {
        over = null
        if (to == null || to == from) {
            render()
            return
        }
        val panel = now()
        val at = folder
        val into = panel.slots.getOrNull(from) is Slot.One && panel.slots.getOrNull(to) is Slot.Folder
        save(
            when {
                on === folderGrid ->
                    if (at == null) panel else panelFolderMove(panel, at.toUInt(), from.toUInt(), to.toUInt())
                into -> panelIntoFolder(panel, from.toUInt(), to.toUInt())
                else -> panelSwap(panel, from.toUInt(), to.toUInt())
            },
        )
    }

    // ---- taps ----

    private fun tap(i: Int, v: View) {
        if (folder != null) {
            back()
            return
        }
        val from = moving
        if (from != null) {
            moving = null
            save(panelSwap(now(), from.toUInt(), i.toUInt()))
            return
        }
        val slot = now().slots.getOrNull(i) ?: Slot.Empty
        when {
            edit && slot is Slot.Empty -> menu(
                v,
                *listOfNotNull(
                    Choice("Apps") { pickApps { save(panelFill(now(), i.toUInt(), it)) } },
                    Choice("Folder") { newFolder(i, rest = false) },
                    Choice("Call") { pickContact(i) },
                    // One folder of all other apps is enough.
                    if (now().slots.any { it is Slot.Folder && it.rest }) null
                    else Choice("Other apps") { newFolder(i, rest = true) },
                ).toTypedArray(),
            )
            edit && slot is Slot.Folder -> menu(
                v,
                Choice("Open") { open(i) },
                Choice("Icon") { pickSign(slot.name) { setSign(i, it) } },
                Choice("Rename") { askName(slot.name) { rename(i, it) } },
                Choice("Move") { move(i) },
                Choice("Remove") {
                    if (slot.items.isEmpty()) remove(i)
                    else confirm("Remove the folder ${slot.name} with the ${slot.items.size} in it?") { remove(i) }
                },
            )
            edit -> menu(
                v,
                *listOfNotNull(
                    Choice("Move") { move(i) },
                    quietChoice((slot as? Slot.One)?.target),
                    Choice("Remove") { remove(i) },
                ).toTypedArray(),
            )
            slot is Slot.One -> start(slot.target)
            slot is Slot.Folder -> open(i)
        }
    }

    private fun open(i: Int) {
        val slot = now().slots.getOrNull(i) as? Slot.Folder ?: return
        if (slot.rest) {
            showRest(slot.name)
            return
        }
        folder = i
        render()
    }

    /** The folder of all other apps: every app on the phone that is nowhere
     *  else in the panel, to start one from. The list is made when it is
     *  asked for, so an app installed a minute ago is in it. */
    private fun showRest(name: String) {
        val all = allApps(launcher)
        val left = panelRest(now(), all.map { it.second.component }).toSet()
        val apps = all.filter { it.second.component in left }
        if (apps.isEmpty()) say("Every app is in the panel already.")
        else show(appDialog(context, name, apps, iconPx) { start(it) })
    }

    private fun move(i: Int) {
        moving = i
        render()
    }

    /** A new folder gets its name first, then its sign. `rest` makes it
     *  the folder of all other apps. */
    private fun newFolder(i: Int, rest: Boolean) = askName(if (rest) "Other apps" else "") { name ->
        pickSign(name) { icon -> save(panelPut(now(), i.toUInt(), Slot.Folder(name, icon, emptyList(), rest))) }
    }

    private fun rename(i: Int, name: String) {
        val old = now().slots.getOrNull(i) as? Slot.Folder ?: return
        save(panelPut(now(), i.toUInt(), Slot.Folder(name, old.icon, old.items, old.rest)))
    }

    private fun setSign(i: Int, icon: String) {
        val old = now().slots.getOrNull(i) as? Slot.Folder ?: return
        save(panelPut(now(), i.toUInt(), Slot.Folder(old.name, icon, old.items, old.rest)))
    }

    private fun remove(i: Int) {
        val before = now()
        val gone = when (val slot = before.slots.getOrNull(i)) {
            is Slot.One -> listOf(slot.target)
            is Slot.Folder -> slot.items
            else -> emptyList()
        }
        save(panelPut(before, i.toUInt(), Slot.Empty))
        letGo(gone)
    }

    private fun addToFolder() {
        val at = folder ?: return
        pickApps { apps -> save(apps.fold(now()) { panel, app -> panelFolderAdd(panel, at.toUInt(), app) }) }
    }

    private fun dropFromFolder(at: Int, n: Int) {
        val gone = (now().slots.getOrNull(at) as? Slot.Folder)?.items?.getOrNull(n) ?: return
        save(panelFolderDrop(now(), at.toUInt(), n.toUInt()))
        letGo(listOf(gone))
    }

    private fun outOfFolder(at: Int, n: Int) {
        val item = (now().slots.getOrNull(at) as? Slot.Folder)?.items?.getOrNull(n) ?: return
        save(panelAdd(panelFolderDrop(now(), at.toUInt(), n.toUInt()), item))
    }

    /** Tidy up after what left the panel. Android keeps a shortcut only
     *  while the home app says it is in use, so its app gets the list of
     *  those left. A contact's picture is deleted with its last icon. */
    private fun letGo(gone: List<Target>) {
        val panel = now()
        for (pkg in gone.filterIsInstance<Target.Shortcut>().map { it.pkg }.distinct()) {
            try {
                launcher.pinShortcuts(pkg, panelPins(panel, pkg), me)
            } catch (_: Exception) {
            }
        }
        for (call in gone.filterIsInstance<Target.Call>()) {
            if (panelHas(panel, call)) continue
            File(context.filesDir, faceFile(call.number)).delete()
            Icons.forget(call)
        }
    }

    private fun start(target: Target) {
        try {
            when (target) {
                is Target.App -> {
                    val app = ComponentName.unflattenFromString(target.component)
                    if (app != null && turnedOffFor(app.packageName)) startOnceOff(app, 40)
                    else launcher.startMainActivity(app, me, null, null)
                }
                is Target.Shortcut -> launcher.startShortcut(target.pkg, target.id, null, null, me)
                is Target.Call -> {
                    // With no leave to call, the dialler opens with the number in it.
                    val may = context.checkSelfPermission(Manifest.permission.CALL_PHONE) ==
                        PackageManager.PERMISSION_GRANTED
                    context.startActivity(
                        Intent(if (may) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", target.number, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            close()
        } catch (_: Exception) {
            say("That one is gone from the phone. Hold the panel to remove it.")
        }
    }

    /** An app marked in its menu starts with the swipe strip's service
     *  off. The home page turns it on again. */
    private fun turnedOffFor(pkg: String): Boolean {
        if (!EdgeService.on || pkg !in Prefs.quiet(context) || !EdgeService.turn(context, false)) return false
        Prefs.setEdgePaused(context, true)
        return true
    }

    /** Android lets go of the service a moment after it is turned off. The
     *  app starts once it has, or after a second. */
    private fun startOnceOff(app: ComponentName, tries: Int) {
        if (EdgeService.on && tries > 0) {
            main.postDelayed({ startOnceOff(app, tries - 1) }, 25)
            return
        }
        try {
            launcher.startMainActivity(app, me, null, null)
        } catch (_: Exception) {
        }
    }

    /** For an app: whether it starts with the swipe strip off, for an app
     *  that refuses to run beside an accessibility service. */
    private fun quietChoice(target: Target?): Choice? {
        val pkg = (target as? Target.App)?.let { ComponentName.unflattenFromString(it.component)?.packageName }
            ?: return null
        if (pkg in Prefs.quiet(context)) return Choice("Keep swipe on in it") { Prefs.setQuiet(context, pkg, false) }
        return Choice("Swipe off while it runs") {
            Prefs.setQuiet(context, pkg, true)
            if (!EdgeService.mayTurn(context)) tell(
                "OnePage needs leave to turn its swipe off and on. Give it once from a computer, " +
                    "with USB debugging on, by installing OnePage again with all its permissions:" +
                    "\n\nadb install -r -g onepage.apk",
            )
        }
    }

    /** The phone's own contact list is an app of its own, so the panel
     *  steps aside for it and comes back when a number is picked. */
    private fun pickContact(i: Int) {
        try {
            context.startActivity(
                Intent(context, ContactActivity::class.java)
                    .putExtra(ContactActivity.AT, i)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            close()
        } catch (_: Exception) {
            say("The contact list did not open.")
        }
    }

    private fun openAccessibility() {
        try {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            close()
        } catch (_: Exception) {
            say("Open Settings, then Accessibility, and switch OnePage on.")
        }
    }

    // ---- menus and dialogs ----

    private fun now(): Panel = PanelStore.get(context)

    private fun save(next: Panel) {
        PanelStore.set(context, next)
        render()
    }

    private fun say(what: String) = Toast.makeText(context.applicationContext, what, Toast.LENGTH_LONG).show()

    private fun menu(anchor: View, vararg choices: Choice) {
        val popup = PopupMenu(context, anchor)
        choices.forEachIndexed { n, choice -> popup.menu.add(0, n, n, choice.label) }
        popup.setOnMenuItemClickListener { item ->
            choices[item.itemId].run()
            true
        }
        popup.show()
    }

    private fun pickApps(then: (List<Target>) -> Unit) = show(appsDialog(context, allApps(launcher), iconPx, then))

    private fun pickSign(name: String, then: (String) -> Unit) = show(signsDialog(context, name, then))

    private fun askName(old: String, then: (String) -> Unit) {
        val field = EditText(context).apply {
            setText(old)
            setSelection(old.length)
            setHint("Name")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            requestFocus()
        }
        val frame = FrameLayout(context).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(field)
        }
        show(
            AlertDialog.Builder(context)
                .setTitle("Folder")
                .setView(frame)
                .setPositiveButton("OK") { _, _ ->
                    val name = field.text.toString().trim()
                    if (name.isNotEmpty()) then(name)
                }
                .setNegativeButton("Cancel", null)
                .create(),
            keyboard = true,
        )
    }

    private fun tell(what: String) = show(
        AlertDialog.Builder(context).setMessage(what).setPositiveButton("OK", null).create(),
    )

    private fun confirm(question: String, then: () -> Unit) = show(
        AlertDialog.Builder(context)
            .setMessage(question)
            .setPositiveButton("Remove") { _, _ -> then() }
            .setNegativeButton("Cancel", null)
            .create(),
    )

    /** A dialog over the panel must be the panel's kind of window, or it
     *  comes up behind it. */
    private fun show(dialog: AlertDialog, keyboard: Boolean = false) {
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        if (keyboard) dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        try {
            dialog.show()
            shown = dialog
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val FOLDER_COLS = 4

        /** Free rows shown below the last icon while editing. */
        const val SPARE = 2

        // In dp: from one icon to the next, the icon itself, a folder's
        // sign, the card's rim and its corner.
        const val PITCH = 46
        const val ICON = 36
        const val SIGN = 26
        const val PAD = 10
        const val RADIUS = 24

        val CARD = 0xFF171B26.toInt()
        val FAINT = 0xFF8A90A4.toInt()
        val LINK = 0xFF8AB4F8.toInt()
    }
}
