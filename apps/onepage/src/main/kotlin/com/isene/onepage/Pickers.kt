package com.isene.onepage

import android.app.AlertDialog
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.CheckedTextView
import android.widget.GridView
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import uniffi.fe2o3_mobile_core.Target

// The two lists the app panel asks the user to choose from. Each is built
// when it is asked for and leaves nothing behind when it closes.

/** The colour of plain text in a dialog, dark or light as the phone has it. */
private fun textColor(ctx: Context): Int {
    val a = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
    return a.getColor(0, Color.WHITE).also { a.recycle() }
}

/**
 * Every app with an icon, by name, each with its icon and a box to tick.
 * "Add" hands back the ticked ones, in the order they were ticked.
 *
 * An icon is drawn when the list scrolls to it, on one thread, and all of
 * them are dropped when the list closes.
 */
fun appsDialog(context: Context, launcher: LauncherApps, iconPx: Int, then: (List<Target>) -> Unit): AlertDialog {
    val builder = AlertDialog.Builder(context)
    val ctx = builder.context
    val density = ctx.resources.displayMetrics.density
    fun dp(n: Int) = (n * density).roundToInt()
    val color = textColor(ctx)
    val tick = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.listChoiceIndicatorMultiple))
        .let { a -> a.getResourceId(0, 0).also { a.recycle() } }
    val apps: List<Pair<String, Target>> = try {
        launcher.getActivityList(null, Process.myUserHandle())
            .map { it.label.toString() to Target.App(it.componentName.flattenToString()) }
            .sortedBy { it.first.lowercase() }
    } catch (_: Exception) {
        emptyList()
    }

    val icons = HashMap<Int, Drawable>()
    val asked = HashSet<Int>()
    val pool = Executors.newSingleThreadExecutor()
    val main = Handler(Looper.getMainLooper())

    val rows = object : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(n: Int) = apps[n]
        override fun getItemId(n: Int) = n.toLong()

        override fun getView(n: Int, old: View?, parent: ViewGroup): View {
            val row = old as? CheckedTextView ?: CheckedTextView(ctx).apply {
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(52)
                setPadding(dp(20), 0, dp(16), 0)
                compoundDrawablePadding = dp(16)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                if (tick != 0) setCheckMarkDrawable(tick)
            }
            row.text = apps[n].first
            row.tag = n
            // Room for the icon from the start, so the name does not jump.
            val shown = icons[n] ?: ColorDrawable(Color.TRANSPARENT).apply { setBounds(0, 0, iconPx, iconPx) }
            row.setCompoundDrawablesRelative(shown, null, null, null)
            if (n !in icons && asked.add(n)) {
                try {
                    pool.execute {
                        val bitmap = Icons.drawNow(ctx, apps[n].second, iconPx) ?: return@execute
                        main.post {
                            val icon = BitmapDrawable(ctx.resources, bitmap).apply { setBounds(0, 0, iconPx, iconPx) }
                            icons[n] = icon
                            if (row.tag == n) row.setCompoundDrawablesRelative(icon, null, null, null)
                        }
                    }
                } catch (_: Exception) {
                    // The list has closed.
                }
            }
            return row
        }
    }

    val order = ArrayList<Int>()
    val list = ListView(ctx).apply {
        choiceMode = ListView.CHOICE_MODE_MULTIPLE
        isFastScrollEnabled = true
        adapter = rows
    }
    // The list has ticked or unticked the row by now.
    list.setOnItemClickListener { _, _, n, _ -> if (list.isItemChecked(n)) order.add(n) else order.remove(n) }

    val dialog = builder
        .setTitle("Apps")
        .setView(list)
        .setPositiveButton("Add") { _, _ -> if (order.isNotEmpty()) then(order.map { apps[it].second }) }
        .setNegativeButton("Cancel", null)
        .create()
    dialog.setOnDismissListener {
        pool.shutdownNow()
        icons.clear()
    }
    return dialog
}

/**
 * The signs a folder can have, to tap one. The first choice is no sign:
 * the letter the folder's name starts with. `then` gets the sign's name,
 * or "" for the letter.
 */
fun signsDialog(context: Context, name: String, then: (String) -> Unit): AlertDialog {
    val builder = AlertDialog.Builder(context)
    val ctx = builder.context
    val density = ctx.resources.displayMetrics.density
    fun dp(n: Int) = (n * density).roundToInt()
    val color = textColor(ctx)
    val names = listOf("") + Signs.all(ctx).keys
    val cell = AbsListView.LayoutParams.MATCH_PARENT to dp(48)

    val grid = GridView(ctx).apply {
        numColumns = 6
        setPadding(dp(12), dp(12), dp(12), 0)
        adapter = object : BaseAdapter() {
            override fun getCount() = names.size
            override fun getItem(n: Int) = names[n]
            override fun getItemId(n: Int) = n.toLong()

            // The letter is a text and the signs are drawings, so neither
            // is handed the other's view to use again.
            override fun getViewTypeCount() = 2
            override fun getItemViewType(n: Int) = if (n == 0) 0 else 1

            override fun getView(n: Int, old: View?, parent: ViewGroup): View {
                if (n == 0) {
                    return TextView(ctx).apply {
                        text = face(name)
                        setTextColor(color)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                        gravity = Gravity.CENTER
                        layoutParams = AbsListView.LayoutParams(cell.first, cell.second)
                    }
                }
                val v = old as? ImageView ?: ImageView(ctx).apply {
                    layoutParams = AbsListView.LayoutParams(cell.first, cell.second)
                    setPadding(0, dp(11), 0, dp(11))
                }
                v.setImageDrawable(Signs.drawable(ctx, names[n], color))
                v.contentDescription = names[n]
                return v
            }
        }
    }
    val dialog = builder.setTitle("Icon").setView(grid).setNegativeButton("Cancel", null).create()
    grid.setOnItemClickListener { _, _, n, _ ->
        then(names[n])
        dialog.dismiss()
    }
    return dialog
}
