package moviecatalogue.catalogue.common

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Plain unit tests: no Spring context, no database. */
class ValidatorTest {
    private fun errorsOf(block: Validator.() -> Unit): List<FieldError> {
        val v = Validator()
        v.block()
        return assertThrows<ValidationException> { v.throwIfInvalid() }.errors
    }

    @Test
    fun `required text is trimmed and returned`() {
        val v = Validator()
        assertEquals("Night Ferry", v.requiredText("title", "  Night Ferry \n", 200))
        assertDoesNotThrow { v.throwIfInvalid() }
    }

    @Test
    fun `required text that is empty or only whitespace is rejected`() {
        assertEquals(listOf(FieldError("title", "is required")), errorsOf { requiredText("title", "", 200) })
        assertEquals(listOf(FieldError("title", "is required")), errorsOf { requiredText("title", " \t\n ", 200) })
    }

    @Test
    fun `length is checked after trimming, so surrounding spaces do not count`() {
        val v = Validator()
        assertEquals("abc", v.requiredText("title", "   abc   ", 3))
        assertDoesNotThrow { v.throwIfInvalid() }
    }

    @Test
    fun `the maximum is inclusive and one more character is rejected`() {
        assertDoesNotThrow { Validator().apply { requiredText("title", "a".repeat(5), 5) }.throwIfInvalid() }
        assertEquals(
            listOf(FieldError("title", "must be at most 5 characters")),
            errorsOf { requiredText("title", "a".repeat(6), 5) },
        )
    }

    @Test
    fun `length counts code points, so an emoji is one character like in PostgreSQL`() {
        val fiveEmoji = "🎬".repeat(5) // String.length is 10: each emoji is two UTF-16 chars
        assertEquals(10, fiveEmoji.length)
        assertDoesNotThrow { Validator().apply { requiredText("title", fiveEmoji, 5) }.throwIfInvalid() }
        assertEquals(1, errorsOf { requiredText("title", fiveEmoji + "🎬", 5) }.size)
    }

    @Test
    fun `optional text treats null, empty and blank the same way, as null without an error`() {
        val v = Validator()
        assertNull(v.optionalText("synopsis", null, 10))
        assertNull(v.optionalText("synopsis", "", 10))
        assertNull(v.optionalText("synopsis", "   ", 10))
        assertDoesNotThrow { v.throwIfInvalid() }
    }

    @Test
    fun `optional text is trimmed, and too long is rejected`() {
        assertEquals("plot", Validator().optionalText("synopsis", "  plot  ", 10))
        assertEquals(
            listOf(FieldError("synopsis", "must be at most 3 characters")),
            errorsOf { optionalText("synopsis", "four", 3) },
        )
    }

    @Test
    fun `every problem is collected and reported together, in order`() {
        val errors =
            errorsOf {
                requiredText("title", "", 10)
                check(false, "runtimeMinutes", "must be positive")
                check(true, "releaseDate", "never reported")
                reject("genres", "choose at least one")
            }
        assertEquals(listOf("title", "runtimeMinutes", "genres"), errors.map { it.field })
    }

    @Test
    fun `the exception message names each field for logs`() {
        val ex = assertThrows<ValidationException> { Validator().apply { reject("a", "x") }.apply { reject("b", "y") }.throwIfInvalid() }
        assertEquals("a: x; b: y", ex.message)
    }
}
