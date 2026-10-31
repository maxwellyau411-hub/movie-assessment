package moviecatalogue.catalogue.graphql

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.IntValue
import graphql.language.StringValue
import graphql.schema.CoercingParseLiteralException
import graphql.schema.CoercingParseValueException
import graphql.schema.CoercingSerializeException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlin.test.assertEquals

/** Plain unit tests of the Date and DateTime scalars: no Spring context. */
class GraphQlScalarsTest {
    private val context = GraphQLContext.getDefault()
    private val date = GraphQlScalars.DATE.coercing
    private val dateTime = GraphQlScalars.DATE_TIME.coercing

    private fun parseDate(input: Any) = date.parseValue(input, context, Locale.ROOT)

    private fun parseDateTime(input: Any) = dateTime.parseValue(input, context, Locale.ROOT)

    @Test
    fun `Date accepts an ISO calendar date from variables and from the query text`() {
        assertEquals(LocalDate.of(2026, 10, 25), parseDate("2026-10-25"))
        assertEquals(
            LocalDate.of(2024, 2, 29),
            date.parseLiteral(StringValue("2024-02-29"), CoercedVariables.emptyVariables(), context, Locale.ROOT),
        )
    }

    @Test
    fun `Date rejects impossible dates, other formats and non-strings with a readable message`() {
        for (input in listOf("2026-02-30", "2025-02-29", "25/10/2026", "2026-10-25T00:00:00Z", "", " 2026-10-25")) {
            val ex = assertThrows<CoercingParseValueException>(input) { parseDate(input) }
            assertEquals("Expected an ISO date such as 2026-10-25", ex.message)
        }
        assertThrows<CoercingParseValueException> { parseDate(20261025) }
        assertThrows<CoercingParseLiteralException> {
            date.parseLiteral(IntValue(BigInteger.ONE), CoercedVariables.emptyVariables(), context, Locale.ROOT)
        }
    }

    @Test
    fun `DateTime accepts a UTC instant and rejects a time without a zone`() {
        assertEquals(Instant.parse("2026-10-25T08:30:00Z"), parseDateTime("2026-10-25T08:30:00Z"))
        assertThrows<CoercingParseValueException> { parseDateTime("2026-10-25T08:30:00") }
        assertThrows<CoercingParseValueException> { parseDateTime("2026-10-25") }
    }

    @Test
    fun `values are written as ISO text, and a wrong type is a server-side bug, not silent output`() {
        assertEquals("2026-10-25", date.serialize(LocalDate.of(2026, 10, 25), context, Locale.ROOT))
        assertEquals("2026-10-25T08:30:00Z", dateTime.serialize(Instant.parse("2026-10-25T08:30:00Z"), context, Locale.ROOT))
        assertThrows<CoercingSerializeException> { date.serialize("2026-10-25", context, Locale.ROOT) }
        assertThrows<CoercingSerializeException> { dateTime.serialize(LocalDate.of(2026, 10, 25), context, Locale.ROOT) }
    }
}
