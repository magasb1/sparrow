package app.sparrow.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollLinkTest {
    private val pin = "WRHsuhH8v7gFjgyJO9sJsu-YVvzCoLJVbDBrDchD7VY"

    @Test
    fun parsesTheBackendFormat() {
        // As written by sparrowd (keys sorted, values URL-encoded).
        val link = EnrollLink.parse("sparrow://enroll?fp=$pin&t=abc_-123&u=https%3A%2F%2F192.168.1.10%3A8443")
        assertEquals(EnrollLink("https://192.168.1.10:8443", "abc_-123", pin), link)
        assertFalse(link.insecure)
    }

    @Test
    fun pinIsOptionalAndHttpIsFlagged() {
        val link = EnrollLink.parse("  sparrow://enroll?u=http%3A%2F%2Flab%3A8443%2F&t=x \n")
        assertNull(link.pin)
        assertEquals("http://lab:8443", link.serverUrl)
        assertTrue(link.insecure)
    }

    @Test
    fun rejectsBadLinks() {
        listOf(
            "srt://host:9000",
            "sparrow://other?u=https%3A%2F%2Fh&t=x",
            "sparrow://enroll?t=x",
            "sparrow://enroll?u=ftp%3A%2F%2Fh&t=x",
            "sparrow://enroll?u=https%3A%2F%2Fh",
            "sparrow://enroll?u=https%3A%2F%2Fh&t=x&fp=short",
            "not a link at all",
        ).forEach { text ->
            assertThrows(text, EnrollLink.ParseException::class.java) { EnrollLink.parse(text) }
        }
    }
}
