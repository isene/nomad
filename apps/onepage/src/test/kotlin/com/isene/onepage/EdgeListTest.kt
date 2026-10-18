package com.isene.onepage

import android.content.ComponentName
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The swipe service in and out of Android's list of services that are on. */
@RunWith(RobolectricTestRunner::class)
class EdgeListTest {
    private val me = ComponentName("com.isene.onepage", "com.isene.onepage.EdgeService")
    private val other = "com.example.reader/.Reader"

    @Test
    fun offTakesOutOnlyThisService() {
        // Android may write the short form of the class name.
        assertEquals(other, EdgeService.listed("$other:com.isene.onepage/.EdgeService", me, false))
        assertEquals("", EdgeService.listed(me.flattenToString(), me, false))
        assertEquals("", EdgeService.listed(null, me, false))
    }

    @Test
    fun onPutsItInOnce() {
        assertEquals("$other:${me.flattenToString()}", EdgeService.listed(other, me, true))
        assertEquals(me.flattenToString(), EdgeService.listed(me.flattenToString(), me, true))
        assertEquals(me.flattenToString(), EdgeService.listed("", me, true))
    }
}
