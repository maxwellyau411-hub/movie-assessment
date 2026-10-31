package moviecatalogue.catalogue.common

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plain unit tests: no Spring context, no database. */
class PagingTest {
    private val scope = listOf("movies", "TITLE_ASC", null)

    private fun afterError(block: () -> Unit): String {
        val error = assertThrows<ValidationException> { block() }.errors.single()
        assertEquals("after", error.field)
        return error.message
    }

    private fun rawCursor(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    @Test
    fun `page size defaults to 20 and an empty cursor means the first page`() {
        assertEquals(PageArgs(20, null), PageArgs.of(null, null))
        assertEquals(PageArgs(20, null), PageArgs.of(null, ""))
    }

    @Test
    fun `page size must be between 1 and 50`() {
        assertEquals(1, PageArgs.of(1, null).first)
        assertEquals(50, PageArgs.of(50, null).first)
        for (size in listOf(0, -1, 51)) {
            val error = assertThrows<ValidationException> { PageArgs.of(size, null) }.errors.single()
            assertEquals(FieldError("first", "must be between 1 and 50"), error)
        }
    }

    @Test
    fun `the extra row read with LIMIT first + 1 only signals a next page and is not returned`() {
        val page = Connection.of(listOf("a", "b", "c"), PageArgs(2, null), scope, "criteria", { listOf(it) }, { it.uppercase() })

        assertEquals(listOf("A", "B"), page.nodes)
        assertTrue(page.pageInfo.hasNextPage)
        assertEquals(page.edges.last().cursor, page.pageInfo.endCursor)
        assertEquals("criteria", page.criteria)
    }

    @Test
    fun `exactly a full page has no next page`() {
        val page = Connection.of(listOf("a", "b"), PageArgs(2, null), scope, Unit, { listOf(it) }, { it })
        assertFalse(page.pageInfo.hasNextPage)
        assertEquals(2, page.nodes.size)
    }

    @Test
    fun `an empty page has no end cursor and no next page`() {
        val page = Connection.of(emptyList<String>(), PageArgs(20, null), scope, Unit, { listOf(it) }, { it })
        assertEquals(PageInfo(hasNextPage = false, endCursor = null), page.pageInfo)
        assertTrue(page.edges.isEmpty())
    }

    @Test
    fun `total count is exact up to the cap and capped above it`() {
        assertEquals(TotalCount(0, capped = false), TotalCount.of(0))
        assertEquals(TotalCount(10_000, capped = false), TotalCount.of(10_000))
        assertEquals(TotalCount(10_000, capped = true), TotalCount.of(10_001))
    }

    @Test
    fun `a cursor round-trips its sort key, including null values`() {
        val key = listOf("night ferry", null, "8f0c4c1e-0000-4000-8000-000000000001")
        assertEquals(key, Cursor.decode(Cursor.encode(scope, key), scope, keySize = 3))
    }

    @Test
    fun `a cursor is URL-safe text`() {
        val cursor = Cursor.encode(scope, listOf("?&/+= ünïcødé"))
        assertTrue(cursor.all { it.isLetterOrDigit() || it == '-' || it == '_' }, cursor)
    }

    @Test
    fun `a cursor from another sort or filter is rejected instead of skipping or repeating rows`() {
        val cursor = Cursor.encode(scope, listOf("a"))
        val message = afterError { Cursor.decode(cursor, listOf("movies", "RELEASE_DATE_NEWEST", null), keySize = 1) }
        assertContains(message, "different list, sort or filter")
    }

    @Test
    fun `malformed cursors are a validation error on after, never a server error`() {
        val notACursor = "is not a valid cursor"
        assertEquals(notACursor, afterError { Cursor.decode("not base64 !!", scope, 1) })
        assertEquals(notACursor, afterError { Cursor.decode(rawCursor("not json"), scope, 1) })
        assertEquals(notACursor, afterError { Cursor.decode(rawCursor("{\"a\":1}"), scope, 1) })
        assertEquals(notACursor, afterError { Cursor.decode(rawCursor("[[\"movies\",\"TITLE_ASC\",null]]"), scope, 1) })
        assertEquals(notACursor, afterError { Cursor.decode(rawCursor("[[\"movies\",\"TITLE_ASC\",null],\"a\"]"), scope, 1) })
    }

    @Test
    fun `a cursor with the wrong number or type of key values is rejected`() {
        assertEquals("is not a valid cursor", afterError { Cursor.decode(Cursor.encode(scope, listOf("a", "b")), scope, keySize = 1) })
        val numberKey = rawCursor("[[\"movies\",\"TITLE_ASC\",null],[42]]")
        assertEquals("is not a valid cursor", afterError { Cursor.decode(numberKey, scope, keySize = 1) })
    }

    @Test
    fun `a stored key value that does not parse is an invalid cursor`() {
        assertEquals(7, Cursor.keyValue { "7".toInt() })
        assertEquals("is not a valid cursor", afterError { Cursor.keyValue { "seven".toInt() } })
    }

    @Test
    fun `a page reads back the cursors it hands out`() {
        val page = Connection.of(listOf("a", "b", "c"), PageArgs(2, null), scope, Unit, { listOf(it) }, { it })
        assertEquals(listOf("b"), Cursor.decode(page.pageInfo.endCursor!!, scope, keySize = 1))
        assertNull(Connection.of(emptyList<String>(), PageArgs(2, null), scope, Unit, { listOf(it) }, { it }).pageInfo.endCursor)
    }
}
