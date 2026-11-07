package com.isene.mail.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DraftsRepoTest {
    @Test
    fun a_mail_draft_shows_its_subject_and_recipient() {
        val d = DraftsRepo.parse(
            "reply.eml",
            "From: a@example.com\nTo: b@example.com\nCc:\nSubject: Re: The plan\nX-Kastrup-Reply-To: 7\n\nYes.\n",
            5,
        )
        assertEquals(listOf("Mail", "Re: The plan", "b@example.com"), listOf(d.kind, d.title, d.to))
    }

    @Test
    fun a_chat_draft_shows_its_first_line_and_channel() {
        val d = DraftsRepo.parse("note.workspace", "Conv: 1234\nChannel: #team\n\n\nOn my way.\n", 5)
        assertEquals(listOf("Workspace", "On my way.", "#team"), listOf(d.kind, d.title, d.to))
        // No channel line: the conversation id.
        assertEquals("1234", DraftsRepo.parse("x.workspace", "Conv: 1234\n\nHi\n", 5).to)
    }

    @Test
    fun a_file_with_no_known_ending_is_mail_as_kastrup_reads_it() {
        val d = DraftsRepo.parse("draft", "To: b@example.com\n\nBody\n", 5)
        assertEquals(listOf("Mail", "(no subject)"), listOf(d.kind, d.title))
    }

    @Test
    fun a_mail_begun_on_the_phone_reads_back_as_the_laptop_writes_it() {
        val text = DraftsRepo.mailText("me@x", "a@x", "", "Re: Hi", "Yes")
        assertEquals("From: me@x\nTo: a@x\nSubject: Re: Hi\n\nYes\n", text)
        val d = DraftsRepo.parse("phone_1.eml", text, 0)
        assertEquals("Mail", d.kind)
        assertEquals("Re: Hi", d.title)
        assertEquals("a@x", d.to)
    }

    @Test
    fun a_sent_copy_carries_what_the_laptop_threads_by() {
        val text = DraftsRepo.sentText("me@x", "a@x", "", "Re: Hi", "Yes", "<1.2@x>", "q@x", "Sat, 07 Nov 2026 15:00:00 +0100")
        assert(text.contains("Message-ID: <1.2@x>\n"))
        assert(text.contains("In-Reply-To: <q@x>\n"))
        assert(text.endsWith("\n\nYes\n"))
        val d = DraftsRepo.parse("phone_1.eml.filed", text, 0)
        assertEquals("Mail", d.kind)
        assertEquals("Re: Hi", d.title)
    }
}
