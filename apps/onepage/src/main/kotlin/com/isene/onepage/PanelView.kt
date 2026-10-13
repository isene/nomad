package com.isene.onepage

import android.app.AlertDialog
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Process
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
import kotlin.math.roundToInt
import uniffi.fe2o3_mobile_core.Panel
import uniffi.fe2o3_mobile_core.Slot
import uniffi.fe2o3_mobile_core.Target
import uniffi.fe2o3_mobile_core.panelFolderAdd
import uniffi.fe2o3_mobile_core.panelFolderDrop
import uniffi.fe2o3_mobile_core.panelPins
import uniffi.fe2o3_mobile_core.panelPut
import uniffi.fe2o3_mobile_core.panelRows
import uniffi.fe2o3_mobile_core.panelSwap

/**
 * The app panel: a card of icons at the right edge of the screen, three to
 * a row. A tap starts an app or a shortcut, or opens a folder as a second
 * card. An empty row parts two groups.
 *
 * Holding the panel switches editing on: a plus shows in every free slot,
 * and a tap on an icon offers to move or remove it. What is changed is
 * saved at once.
 *
 * The view fills its window, so a tap beside the cards is a tap on it, and
 * that steps back: out of a folder, out of editing, then out of the panel.
 */
class PanelView(context: Context, private val close: () -> Unit) : FrameLayout(context) {

    /** One line of a menu. */
    private class Choice(val label: String, val run: () -> Unit)

    private val density = resources.displayMetrics.density
    private val pitch = dp(PITCH)
    private val iconPx = dp(ICON)
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val launcher: LauncherApps = context.getSystemService(LauncherApps::class.java)
    private val me = Process.myUserHandle()

    private var edit = false

    /** The slot that is being moved, until its new place is tapped. */
    private var moving: Int? = null

    /** The slot of the folder that is open. */
    private var folder: Int? = null

    /** The dialog that is up, so it goes when the panel goes. */
    private var shown: Dialog? = null

    /** True once the panel has left the screen. */
    private var closed = false

    private val hint = line(FAINT)
    private val anyApp = line(LINK).apply {
        text = "To open the panel from any app: tap here, then switch OnePage on under Accessibility."
        setOnClickListener { openAccessibility() }
    }
    private val grid = GridLayout(context).apply { columnCount = COLS }

    private val box = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val r = dp(RADIUS).toFloat()
        // Round towards the screen, square against its edge.
        background = GradientDrawable().apply {
            setColor(CARD)
            cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        }
        val p = dp(PAD)
        setPadding(p, p, p, p)
        addView(hint, LinearLayout.LayoutParams(pitch * COLS, wrap))
        addView(grid)
        addView(anyApp, LinearLayout.LayoutParams(pitch * COLS, wrap))
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
            contentDescription = "Add an app to the folder"
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
        addView(folderGrid)
        setOnClickListener { }
        visibility = View.GONE
    }

    init {
        setOnClickListener { back() }
        addView(scroll, LayoutParams(wrap, wrap, Gravity.END or Gravity.CENTER_VERTICAL))
        addView(folderBox, LayoutParams(wrap, wrap, Gravity.CENTER))
        Icons.retry()
        // An empty panel starts in editing, so there is a plus to tap.
        edit = now().slots.isEmpty()
        render()
        box.alpha = 0f
        box.translationY = dp(24).toFloat()
        box.animate().alpha(1f).translationY(0f).setDuration(120).start()
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
        val used = panelRows(panel, COLS.toUInt()).toInt()
        // While editing there is room below for a new group after a gap.
        val rows = if (edit) used + SPARE else used
        val wanted = ArrayList<Target>()

        hint.visibility = if (edit) View.VISIBLE else View.GONE
        hint.text = if (moving != null) "Tap where it should go."
        else "Tap + to add. Tap an icon to move or remove it. Back when done."
        anyApp.visibility = if (edit && !EdgeService.on) View.VISIBLE else View.GONE

        grid.removeAllViews()
        for (i in 0 until rows * COLS) {
            grid.addView(cell(panel.slots.getOrNull(i) ?: Slot.Empty, i, wanted), pitch, pitch)
        }

        val at = folder
        val open = at?.let { panel.slots.getOrNull(it) as? Slot.Folder }
        folderGrid.removeAllViews()
        if (at == null || open == null) {
            folder = null
            folderBox.visibility = View.GONE
        } else {
            folderTitle.text = open.name
            open.items.forEachIndexed { n, target ->
                val v = icon(target, wanted)
                v.background = ripple()
                v.setOnClickListener { start(target) }
                v.setOnLongClickListener { anchor ->
                    menu(anchor, Choice("Remove") { dropFromFolder(at, n) })
                    true
                }
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
            is Slot.One -> icon(slot.target, wanted)
            is Slot.Folder -> sign(face(slot.name), Color.WHITE, 22f)
        }
        // A free slot outside editing takes no touch: it is part of the card.
        if (slot !is Slot.Empty || edit) {
            v.background = ripple()
            v.setOnClickListener { tap(i, it) }
            v.setOnLongClickListener {
                toggleEdit()
                true
            }
        }
        if (i == moving) v.alpha = 0.35f
        return v
    }

    private fun icon(target: Target, wanted: MutableList<Target>): ImageView = ImageView(context).apply {
        val pad = (pitch - iconPx) / 2
        setPadding(pad, pad, pad, pad)
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

    private fun ripple(): Drawable = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null)

    private fun dp(n: Int): Int = (n * density).roundToInt()

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
                Choice("App") { pickApp { save(panelPut(now(), i.toUInt(), Slot.One(it))) } },
                Choice("Folder") { askName("") { save(panelPut(now(), i.toUInt(), Slot.Folder(it, emptyList()))) } },
            )
            edit && slot is Slot.Folder -> menu(
                v,
                Choice("Open") { open(i) },
                Choice("Move") { move(i) },
                Choice("Rename") { askName(slot.name) { rename(i, it) } },
                Choice("Remove") {
                    if (slot.items.isEmpty()) remove(i)
                    else confirm("Remove the folder ${slot.name} with the ${slot.items.size} in it?") { remove(i) }
                },
            )
            edit -> menu(v, Choice("Move") { move(i) }, Choice("Remove") { remove(i) })
            slot is Slot.One -> start(slot.target)
            slot is Slot.Folder -> open(i)
        }
    }

    private fun open(i: Int) {
        folder = i
        render()
    }

    private fun move(i: Int) {
        moving = i
        render()
    }

    private fun rename(i: Int, name: String) {
        val old = now().slots.getOrNull(i) as? Slot.Folder ?: return
        save(panelPut(now(), i.toUInt(), Slot.Folder(name, old.items)))
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
        pickApp { save(panelFolderAdd(now(), at.toUInt(), it)) }
    }

    private fun dropFromFolder(at: Int, n: Int) {
        val gone = (now().slots.getOrNull(at) as? Slot.Folder)?.items?.getOrNull(n) ?: return
        save(panelFolderDrop(now(), at.toUInt(), n.toUInt()))
        letGo(listOf(gone))
    }

    /** Android keeps a shortcut only while the home app says it is in use.
     *  So when one leaves the panel, its app gets the list of those left. */
    private fun letGo(gone: List<Target>) {
        val panel = now()
        for (pkg in gone.filterIsInstance<Target.Shortcut>().map { it.pkg }.distinct()) {
            try {
                launcher.pinShortcuts(pkg, panelPins(panel, pkg), me)
            } catch (_: Exception) {
            }
        }
    }

    private fun start(target: Target) {
        try {
            when (target) {
                is Target.App ->
                    launcher.startMainActivity(ComponentName.unflattenFromString(target.component), me, null, null)
                is Target.Shortcut -> launcher.startShortcut(target.pkg, target.id, null, null, me)
            }
            close()
        } catch (_: Exception) {
            say("That one is gone from the phone. Hold the panel to remove it.")
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

    /** Every app with an icon, by name. */
    private fun pickApp(then: (Target) -> Unit) {
        val apps = try {
            launcher.getActivityList(null, me)
                .map { it.label.toString() to it.componentName.flattenToString() }
                .sortedBy { it.first.lowercase() }
        } catch (_: Exception) {
            emptyList()
        }
        show(
            AlertDialog.Builder(context)
                .setTitle("App")
                .setItems(apps.map { it.first }.toTypedArray()) { _, n -> then(Target.App(apps[n].second)) }
                .setNegativeButton("Cancel", null),
        )
    }

    private fun askName(old: String, then: (String) -> Unit) {
        val field = EditText(context).apply {
            setText(old)
            setSelection(old.length)
            hint = "Name"
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
                .setMessage("The panel shows its first letter, or the emoji it starts with.")
                .setView(frame)
                .setPositiveButton("OK") { _, _ ->
                    val name = field.text.toString().trim()
                    if (name.isNotEmpty()) then(name)
                }
                .setNegativeButton("Cancel", null),
            keyboard = true,
        )
    }

    private fun confirm(question: String, then: () -> Unit) = show(
        AlertDialog.Builder(context)
            .setMessage(question)
            .setPositiveButton("Remove") { _, _ -> then() }
            .setNegativeButton("Cancel", null),
    )

    /** A dialog over the panel must be the panel's kind of window, or it
     *  comes up behind it. */
    private fun show(builder: AlertDialog.Builder, keyboard: Boolean = false) {
        val dialog = builder.create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        if (keyboard) dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        try {
            dialog.show()
            shown = dialog
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val COLS = 3
        const val FOLDER_COLS = 4

        /** Free rows shown below the last icon while editing. */
        const val SPARE = 2

        // In dp: from one icon to the next, the icon itself, the card's
        // rim and its corner.
        const val PITCH = 46
        const val ICON = 36
        const val PAD = 10
        const val RADIUS = 24

        val CARD = 0xFF171B26.toInt()
        val FAINT = 0xFF8A90A4.toInt()
        val LINK = 0xFF8AB4F8.toInt()
    }
}
