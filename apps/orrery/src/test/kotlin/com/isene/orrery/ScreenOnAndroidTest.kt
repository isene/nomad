package com.isene.orrery

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
 * The app on Android's own code, on the laptop, with the Rust core built
 * for this machine: the dial and the words below it appear for Oslo.
 * Writes build/orrery.png to look at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1700dp-xxhdpi")
class ScreenOnAndroidTest {
    private lateinit var root: View

    @Test fun the_dial_and_the_words() {
        weather()
        root = Robolectric.buildActivity(MainActivity::class.java).setup().get().window.decorView
        // The sky is computed off the main thread; give it time to come back.
        repeat(40) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
        }
        shot("orrery")
        for (head in listOf("NEXT", "TONIGHT", "MOON", "PLANETS", "AHEAD", "PLACE")) assertTrue(head, has(head))
        assertTrue(has("Oslo (the default)"))
        assertTrue(nodes().any { n -> texts(n).any { it.startsWith("Jupiter") } })
        assertTrue(nodes().any { n -> texts(n).any { it.startsWith("Outer ring") } })
    }

    /** A made-up forecast for Oslo in the cache file, so the outer ring has weather to show. */
    private fun weather() {
        val hour = System.currentTimeMillis() / 3_600_000 * 3600
        val kinds = "RRRCCCCPPPSSSSSSSSPPCCRR".map { mapOf('R' to "RAIN", 'C' to "CLOUD", 'P' to "PARTLY", 'S' to "SUN")[it] }
        val hours = (0 until 30).joinToString(",") { k ->
            """{"at":${hour + k * 3600},"cover":${(k * 37) % 100},"sky":"${kinds[k % kinds.size]}"}"""
        }
        File(RuntimeEnvironment.getApplication().filesDir, "clouds.json")
            .writeText("""{"lat":${OSLO.lat},"lon":${OSLO.lon},"hours":[$hours]}""")
    }

    private fun nodes(): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) { all += n; n.children.forEach(::walk) }
        walk(find(root)!!.semanticsOwner.unmergedRootSemanticsNode)
        return all
    }

    private fun texts(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()

    private fun has(text: String) = nodes().any { text in texts(it) }

    private fun shot(name: String) {
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File("build/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun find(v: View): ViewRootForTest? = when (v) {
        is ViewRootForTest -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
        else -> null
    }
}
