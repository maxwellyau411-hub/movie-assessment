package moviecatalogue.catalogue.graphql

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.StringValue
import graphql.language.Value
import graphql.schema.Coercing
import graphql.schema.CoercingParseLiteralException
import graphql.schema.CoercingParseValueException
import graphql.schema.CoercingSerializeException
import graphql.schema.GraphQLScalarType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.graphql.execution.RuntimeWiringConfigurer
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale

/** `Date` (ISO calendar date) and `DateTime` (ISO UTC instant), written here instead of adding a scalar library. */
@Configuration(proxyBeanMethods = false)
class GraphQlScalars {
    @Bean
    fun scalarWiring() = RuntimeWiringConfigurer { it.scalar(DATE).scalar(DATE_TIME) }

    private class IsoScalar<T : Any>(
        private val type: Class<T>,
        private val parse: (String) -> T,
        private val expected: String,
    ) : Coercing<T, String> {
        override fun serialize(
            dataFetcherResult: Any,
            graphQLContext: GraphQLContext,
            locale: Locale,
        ): String =
            if (type.isInstance(dataFetcherResult)) {
                dataFetcherResult.toString()
            } else {
                throw CoercingSerializeException("Expected ${type.simpleName}")
            }

        override fun parseValue(
            input: Any,
            graphQLContext: GraphQLContext,
            locale: Locale,
        ): T = (input as? String)?.let(::parseOrNull) ?: throw CoercingParseValueException("Expected $expected")

        override fun parseLiteral(
            input: Value<*>,
            variables: CoercedVariables,
            graphQLContext: GraphQLContext,
            locale: Locale,
        ): T = (input as? StringValue)?.value?.let(::parseOrNull) ?: throw CoercingParseLiteralException("Expected $expected")

        private fun parseOrNull(text: String): T? =
            try {
                parse(text)
            } catch (_: DateTimeParseException) {
                null
            }
    }

    companion object {
        val DATE: GraphQLScalarType =
            GraphQLScalarType
                .newScalar()
                .name("Date")
                .coercing(IsoScalar(LocalDate::class.java, LocalDate::parse, "an ISO date such as 2026-10-25"))
                .build()

        val DATE_TIME: GraphQLScalarType =
            GraphQLScalarType
                .newScalar()
                .name("DateTime")
                .coercing(IsoScalar(Instant::class.java, Instant::parse, "an ISO UTC timestamp such as 2026-10-25T08:30:00Z"))
                .build()
    }
}
