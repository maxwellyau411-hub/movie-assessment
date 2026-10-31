package moviecatalogue.catalogue.common

import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Plain unit tests: no Spring context, no database. */
class TextTest {
    @Test
    fun `match key ignores case, surrounding spaces and repeated inner whitespace`() {
        assertEquals("the night ferry", Text.matchKey("  The   Night\tFERRY \n"))
        assertEquals(Text.matchKey("Night Ferry"), Text.matchKey("night  ferry"))
    }

    @Test
    fun `match key keeps punctuation and accents, so different titles stay different`() {
        assertEquals("amélie: part 2", Text.matchKey("Amélie: Part 2"))
        assertNotEquals(Text.matchKey("Amelie"), Text.matchKey("Amélie"))
    }

    @Test
    fun `match key is the same in every JVM locale`() {
        // In a Turkish locale a locale-sensitive lowercase turns "I" into a dotless "ı".
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("indigo", Text.matchKey("INDIGO"))
            assertEquals("%indigo%", Text.likeContains("INDIGO"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `search term is trimmed, and blank means no search`() {
        assertEquals("ferry", Text.searchTerm("  ferry  "))
        assertNull(Text.searchTerm(null))
        assertNull(Text.searchTerm(""))
        assertNull(Text.searchTerm("   "))
    }

    @Test
    fun `like pattern is lower case and wraps the term for a contains match`() {
        assertEquals("%night ferry%", Text.likeContains("Night Ferry"))
    }

    @Test
    fun `like wildcards and the escape character typed by a user match literally`() {
        assertEquals("%50\\%%", Text.likeContains("50%"))
        assertEquals("%a\\_b%", Text.likeContains("a_b"))
        assertEquals("%c:\\\\films%", Text.likeContains("C:\\films"))
        assertEquals('\\', Text.LIKE_ESCAPE)
    }
}
