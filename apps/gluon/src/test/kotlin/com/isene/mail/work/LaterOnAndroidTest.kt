package com.isene.mail.work

import android.app.AlarmManager
import com.isene.mail.data.Later
import com.isene.mail.data.Outbound
import com.isene.mail.data.Waiting
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The waiting store and its one alarm, run on the laptop by Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LaterOnAndroidTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val alarms = shadowOf(ctx.getSystemService(AlarmManager::class.java))

    private fun mail(id: String, at: Long) =
        Waiting(id, at, Waiting.MAIL, "Plans", "bob@example.com", mapOf("to" to "bob@example.com", "body" to "Hi"))

    @Test
    fun a_forward_keeps_its_attachments_until_it_is_removed() {
        val bytes = byteArrayOf(1, 2, 3)
        Later.save(ctx, mail("w1", 5_000_000_000_000), listOf(Outbound.Attachment("a.pdf", "application/pdf", bytes)))
        val w = Later.list(ctx).single()
        assertEquals(listOf("a.pdf" to "application/pdf"), w.files)
        assertArrayEquals(bytes, Later.attachments(ctx, w).single().bytes)
        Later.remove(ctx, "w1")
        assertEquals(emptyList<Waiting>(), Later.list(ctx))
        assertEquals(0, ctx.filesDir.resolve("later").listFiles().orEmpty().size)
    }

    @Test
    fun one_alarm_rings_for_the_first_message_and_none_when_all_are_gone() {
        val soon = System.currentTimeMillis() + 3_600_000
        Later.save(ctx, mail("w1", soon + 60_000))
        Later.save(ctx, mail("w2", soon))
        LaterAlarm.arm(ctx)
        assertEquals(soon, alarms.peekNextScheduledAlarm()?.triggerAtTime)
        Later.remove(ctx, "w1")
        Later.remove(ctx, "w2")
        LaterAlarm.arm(ctx)
        assertNull(alarms.peekNextScheduledAlarm())
    }

    @Test
    fun a_message_reads_back_as_it_was_written() {
        val w = Waiting(
            "w5", 1_800_000_000_000, Waiting.MAIL, "Plans", "bob@example.com",
            mapOf("from" to "alice@example.com", "to" to "bob@example.com", "body" to "Line one\nLine \"two\""),
            files = listOf("a.pdf" to "application/pdf"), error = "",
        )
        assertEquals(w, Later.read(Later.write(w)))
    }
}
