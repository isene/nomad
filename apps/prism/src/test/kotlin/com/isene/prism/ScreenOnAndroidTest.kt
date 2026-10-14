package com.isene.prism

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The whole screen fits a phone with large text, with no scrolling: 0.1.0
 * put the hex field and the copy button below the edge (2026-10-14). The
 * height is a 792 dp phone less its two bars. Writes build/screen.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h700dp-xxhdpi")
class ScreenOnAndroidTest {
    @Test fun everything_fits_with_large_text() {
        RuntimeEnvironment.setFontScale(1.3f)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File("build/screen.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val nodes = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) { nodes += n; n.children.forEach(::walk) }
        walk(find(root)!!.semanticsOwner.unmergedRootSemanticsNode)
        val sliders = nodes.filter { SemanticsProperties.ProgressBarRangeInfo in it.config }
        assertEquals(6, sliders.size)
        val copy = nodes.first { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Copy" } == true
        }
        val minimum = 32 * root.resources.displayMetrics.density
        for (n in sliders) {
            val b = n.boundsInWindow
            assertTrue("slider at $b is cut or below ${root.height}", b.bottom <= root.height && b.height >= minimum)
        }
        assertTrue("Copy is below the edge", copy.boundsInWindow.bottom <= root.height)
    }

    private fun find(v: View): ViewRootForTest? = when (v) {
        is ViewRootForTest -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
        else -> null
    }
}
