package com.isene.mail.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** A feed redirect stays on https, since Android refuses plain http.
 *  The redirects are the ones WordPress and FeedBurner send. */
class FeedRedirectTest {
    @Test
    fun a_redirect_to_http_goes_over_https() {
        assertEquals(
            "https://example.org/feed/",
            FeedRepo.nextHop("https://example.wordpress.com/feed/", "http://example.org/feed/"),
        )
        assertEquals(
            "https://www.example.com/feed?cat=-1",
            FeedRepo.nextHop("https://feeds.example.com/x", "http://www.example.com/feed?cat=-1"),
        )
    }

    @Test
    fun a_relative_redirect_stays_on_the_same_site() {
        assertEquals("https://example.org/rss.xml", FeedRepo.nextHop("https://example.org/feed", "/rss.xml"))
    }
}
