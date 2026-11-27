package com.isene.mail.data

import android.net.Uri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** A file picked on the compose screen, read the way a send reads it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PickedFileOnAndroidTest {
    @Test
    fun a_picked_file_keeps_its_name_and_bytes() {
        val f = File.createTempFile("report", ".pdf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val a = Attachments.picked(RuntimeEnvironment.getApplication(), Uri.fromFile(f))!!
        assertEquals(f.name, a.filename)
        assertArrayEquals(byteArrayOf(1, 2, 3), a.bytes)
    }

    @Test
    fun a_file_that_is_gone_gives_null() {
        val gone = Uri.fromFile(File("/nonexistent/gone.pdf"))
        assertNull(Attachments.picked(RuntimeEnvironment.getApplication(), gone))
    }
}
