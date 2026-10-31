package moviecatalogue.catalogue.graphql

import graphql.GraphQLError
import graphql.Scalars
import graphql.execution.ExecutionStepInfo
import graphql.execution.MergedField
import graphql.execution.ResultPath
import graphql.language.Field
import graphql.schema.DataFetchingEnvironmentImpl
import moviecatalogue.catalogue.common.ConflictException
import moviecatalogue.catalogue.common.ConflictReason
import moviecatalogue.catalogue.common.FieldError
import moviecatalogue.catalogue.common.NotFoundException
import moviecatalogue.catalogue.common.ServiceUnavailableException
import moviecatalogue.catalogue.common.ValidationException
import org.junit.jupiter.api.Test
import org.springframework.beans.TypeMismatchException
import org.springframework.graphql.execution.ErrorType
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Plain unit tests of the exception → GraphQL error mapping: no Spring context. */
class CatalogueErrorResolverTest {
    private val resolver = CatalogueErrorResolver()

    private val env =
        DataFetchingEnvironmentImpl
            .newDataFetchingEnvironment()
            .mergedField(MergedField.newMergedField(Field("movie")).build())
            .executionStepInfo(
                ExecutionStepInfo
                    .newExecutionStepInfo()
                    .type(Scalars.GraphQLString)
                    .path(ResultPath.rootPath().segment("movie"))
                    .build(),
            ).build()

    private fun resolve(ex: Throwable): GraphQLError? = resolver.resolveException(ex, env).block()?.single()

    @Test
    fun `validation errors carry every field problem for the form`() {
        val error = resolve(ValidationException(listOf(FieldError("title", "is required"), FieldError("runtimeMinutes", "too long"))))!!

        assertEquals("Some fields are invalid", error.message)
        assertEquals(ErrorType.BAD_REQUEST, error.errorType)
        assertEquals("VALIDATION_ERROR", error.extensions["code"])
        assertEquals(
            listOf(mapOf("field" to "title", "message" to "is required"), mapOf("field" to "runtimeMinutes", "message" to "too long")),
            error.extensions["fieldErrors"],
        )
        assertEquals(listOf<Any>("movie"), error.path)
    }

    @Test
    fun `not found names the kind of thing but not the ID`() {
        val error = resolve(NotFoundException("Movie", UUID.fromString("8f0c4c1e-0000-4000-8000-000000000001")))!!
        assertEquals("Movie not found", error.message)
        assertEquals(ErrorType.NOT_FOUND, error.errorType)
        assertEquals("NOT_FOUND", error.extensions["code"])
    }

    @Test
    fun `conflicts carry a machine-readable reason and their details`() {
        val error =
            resolve(
                ConflictException(
                    ConflictReason.PERSON_HAS_CREDITS,
                    "Remove their credits first",
                    mapOf("movies" to listOf("Night Ferry")),
                ),
            )!!
        assertEquals("Remove their credits first", error.message)
        assertEquals(mapOf("code" to "CONFLICT", "reason" to "PERSON_HAS_CREDITS", "movies" to listOf("Night Ferry")), error.extensions)
    }

    @Test
    fun `media-service being down is reported as retryable, with the service's own message`() {
        val error = resolve(ServiceUnavailableException("Artwork storage is unavailable"))!!
        assertEquals("SERVICE_UNAVAILABLE", error.extensions["code"])
        assertEquals("Artwork storage is unavailable", error.message)
    }

    @Test
    fun `a badly formatted argument such as a non-UUID ID is a validation error, not a server error`() {
        val error = resolve(TypeMismatchException("not-a-uuid", UUID::class.java))!!
        assertEquals("VALIDATION_ERROR", error.extensions["code"])
        assertEquals(ErrorType.BAD_REQUEST, error.errorType)
    }

    @Test
    fun `anything unexpected is left to Spring's generic internal error, so no details leak`() {
        assertNull(resolve(IllegalStateException("database password is wrong")))
    }
}
