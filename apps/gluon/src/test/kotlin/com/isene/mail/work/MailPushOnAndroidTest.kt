package com.isene.mail.work

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import com.isene.gluon.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import uniffi.fe2o3_mobile_core.Message

/** Instant mail's notices and its switch, run on the laptop by
 *  Robolectric. The mails here are made up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MailPushOnAndroidTest {
    private fun mail(id: String, from: String, subject: String) = Message(
        messageId = id, source = "mail", link = "", uid = 1u, account = "alice@example.com",
        folder = "INBOX", from = from, to = "alice@example.com", subject = subject,
        date = 1_800_000_000, raw = "", html = "", hasAttachments = false,
    )

    @Test
    fun only_an_incremental_fetch_rings_and_only_for_new_mail() {
        val old = mail("<1@x>", "Bob <bob@example.com>", "Old")
        val new = mail("<2@x>", "Bob <bob@example.com>", "New")
        assertEquals(listOf(new), MailNotices.arrivals(setOf("<1@x>"), listOf(old, new), incremental = true))
        assertEquals(emptyList<Message>(), MailNotices.arrivals(emptySet(), listOf(old, new), incremental = false))
    }

    @Test
    fun a_notice_per_mail_under_one_summary_and_a_tap_opens_that_mail() {
        val ctx = RuntimeEnvironment.getApplication()
        MailNotices.post(ctx, listOf(mail("<2@x>", "\"Bob Smith\" <bob@example.com>", "The receipt"), mail("<3@x>", "<carol@example.com>", "Hi")))
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val shown = nm.activeNotifications.map { it.notification }
        assertEquals(3, shown.size)
        val bob = shown.first { it.extras.getCharSequence(Notification.EXTRA_TITLE) == "Bob Smith" }
        assertEquals("The receipt", bob.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("carol@example.com", shown.first { it.extras.getCharSequence(Notification.EXTRA_TEXT) == "Hi" }
            .extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val tap = shadowOf(bob.contentIntent).savedIntent
        assertEquals(
            listOf<Any?>(MainActivity::class.java.name, "com.isene.gluon.Mail", "<2@x>"),
            listOf(tap.component!!.className, tap.action, tap.getStringExtra(MailNotices.EXTRA_MAIL)),
        )
        assertTrue("one summary", shown.single { it.flags and Notification.FLAG_GROUP_SUMMARY != 0 }.group == bob.group)
        MailNotices.clear(ctx)
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test
    fun the_widget_and_the_notices_take_the_first_three_views() {
        val s = com.isene.mail.data.Settings(RuntimeEnvironment.getApplication())
        val from = { account: String -> mail("<$account>", "Bob <bob@example.com>", "Hi").copy(account = account) }
        assertEquals(listOf(""), com.isene.mail.data.Scope.home(s))
        s.viewsText = "One | mail:a@example.com\nTwo | mail:b@example.com\nThree | mail:c@example.com\nFour | mail:d@example.com"
        val home = com.isene.mail.data.Scope.home(s)
        assertEquals(listOf("view:One", "view:Two", "view:Three"), home)
        val at = listOf("a", "c", "d").map { com.isene.mail.data.Scope.atHome(from("$it@example.com"), home, s) }
        assertEquals(listOf(true, true, false), at)
    }

    @Test
    fun the_switch_off_starts_nothing() {
        val ctx = RuntimeEnvironment.getApplication()
        MailPush.sync(ctx)
        assertNull(shadowOf(ctx).nextStartedService)
    }

    @Test
    fun the_manifest_declares_the_service_and_what_it_needs() {
        val ctx = RuntimeEnvironment.getApplication()
        val pm = ctx.packageManager
        val info = pm.getServiceInfo(ComponentName(ctx, MailPush::class.java), 0)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, info.foregroundServiceType)
        assertTrue(!info.exported)
        val asked = pm.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions!!.toSet()
        for (p in listOf("FOREGROUND_SERVICE", "FOREGROUND_SERVICE_SPECIAL_USE", "ACCESS_NETWORK_STATE", "WAKE_LOCK")) {
            assertTrue(p, "android.permission.$p" in asked)
        }
        val boot = pm.queryBroadcastReceivers(Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(ctx.packageName), 0)
        assertTrue(boot.any { it.activityInfo.name == MailPushBoot::class.java.name })
    }
}
