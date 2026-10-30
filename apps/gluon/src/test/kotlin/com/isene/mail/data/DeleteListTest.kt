package com.isene.mail.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** The list kastrup reads to delete mail chosen on the phone. */
class DeleteListTest {
    @Test
    fun a_delete_is_added_and_old_lines_drop_off() {
        val day = 86_400L
        val now = 100 * day
        val old = "<old@example.com>\t${now - 40 * day}\n<kept@example.com>\t${now - day}\n"
        assertEquals(
            "<kept@example.com>\t${now - day}\n<new@example.com>\t$now\n",
            ReadStateRepo.deleteList(old, listOf("<new@example.com>"), now, 30),
        )
    }

    @Test
    fun the_same_mail_twice_is_one_line() {
        val once = ReadStateRepo.deleteList("", listOf("<a@example.com>"), 10, 30)
        assertEquals("<a@example.com>\t20\n", ReadStateRepo.deleteList(once, listOf("<a@example.com>"), 20, 30))
    }
}
