package moviecatalogue.catalogue.graphql

import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.schema.DataFetchingEnvironment
import moviecatalogue.catalogue.common.ConflictException
import moviecatalogue.catalogue.common.NotFoundException
import moviecatalogue.catalogue.common.ServiceUnavailableException
import moviecatalogue.catalogue.common.ValidationException
import org.springframework.beans.TypeMismatchException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.convert.ConversionException
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component
import org.springframework.validation.BindException

/**
 * Turns exceptions into safe GraphQL errors with a stable `extensions.code`:
 * VALIDATION_ERROR, NOT_FOUND, CONFLICT or SERVICE_UNAVAILABLE.
 * Anything unrecognised falls through to Spring's default: INTERNAL_ERROR with no stack trace.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CatalogueErrorResolver : DataFetcherExceptionResolverAdapter() {
    override fun resolveToSingleError(
        ex: Throwable,
        env: DataFetchingEnvironment,
    ): GraphQLError? =
        when (ex) {
            is ValidationException -> {
                error(
                    env,
                    ErrorType.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "Some fields are invalid",
                    "fieldErrors" to ex.errors.map { mapOf("field" to it.field, "message" to it.message) },
                )
            }

            is NotFoundException -> {
                error(env, ErrorType.NOT_FOUND, "NOT_FOUND", "${ex.entity} not found")
            }

            is ConflictException -> {
                error(
                    env,
                    ErrorType.BAD_REQUEST,
                    "CONFLICT",
                    ex.message ?: "Conflict",
                    "reason" to ex.reason.name,
                    *ex.details.toList().toTypedArray(),
                )
            }

            is ServiceUnavailableException -> {
                error(env, ErrorType.INTERNAL_ERROR, "SERVICE_UNAVAILABLE", ex.message ?: "A required service is unavailable")
            }

            is BindException, is ConversionException, is TypeMismatchException -> {
                error(
                    env,
                    ErrorType.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "An argument has an invalid format (for example an ID that is not a UUID)",
                )
            }

            else -> {
                null
            }
        }

    private fun error(
        env: DataFetchingEnvironment,
        type: ErrorType,
        code: String,
        message: String,
        vararg extra: Pair<String, Any>,
    ): GraphQLError =
        GraphqlErrorBuilder
            .newError(env)
            .errorType(type)
            .message(message)
            .extensions(mapOf("code" to code) + extra)
            .build()
}
