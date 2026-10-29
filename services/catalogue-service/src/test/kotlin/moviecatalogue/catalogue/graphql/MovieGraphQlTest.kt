package moviecatalogue.catalogue.graphql

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MovieGraphQlTest : GraphQlApiTest() {
    private val movieFields =
        """
        id title synopsis releaseDate releaseYear runtimeMinutes version createdAt
        genres { code name } artwork { width }
        cast { id characterName person { id name } movie { id title } }
        creators { id role person { id name } }
        """

    private val createMutation =
        """mutation(${'$'}input: MovieInput!, ${'$'}confirm: Boolean) {
             createMovie(input: ${'$'}input, confirmDuplicate: ${'$'}confirm) {
               __typename
               ... on MovieSaved { movie { $movieFields } }
               ... on DuplicateMovieWarning { message candidates { id title releaseDate } }
             }
           }"""

    private val updateMutation =
        """mutation(${'$'}id: ID!, ${'$'}v: Int!, ${'$'}input: MovieInput!) {
             updateMovie(id: ${'$'}id, expectedVersion: ${'$'}v, input: ${'$'}input, confirmDuplicate: true) {
               ... on MovieSaved { movie { id title version genres { code } } }
             }
           }"""

    private fun movieInput(
        title: String,
        vararg extra: Pair<String, Any?>,
    ) = mapOf("title" to title, "genreCodes" to listOf("DRAMA")) + extra

    @Test
    fun `create and read a movie with typed dates, derived year and genres`() {
        val created =
            gql(
                createMutation,
                mapOf(
                    "input" to
                        movieInput(
                            "Harbour Lights ${UUID.randomUUID()}",
                            "releaseDate" to "2030-02-01",
                            "runtimeMinutes" to 101,
                            "genreCodes" to listOf("WAR", "DRAMA"),
                        ),
                    "confirm" to true,
                ),
            )

        assertEquals("MovieSaved", created.data("createMovie.__typename").asString(), created.toString())
        val id = created.data("createMovie.movie.id").asString()
        val read = gql("{ movie(id: \"$id\") { $movieFields } }")
        assertEquals("2030-02-01", read.data("movie.releaseDate").asString())
        assertEquals(2030, read.data("movie.releaseYear").asInt())
        assertEquals(listOf("Drama", "War"), read.data("movie.genres").values().map { it.path("name").asString() })
        assertEquals("2026-10-25T00:00:00Z", read.data("movie.createdAt").asString())
        assertTrue(read.data("movie.artwork").isNull)
    }

    @Test
    fun `validation errors list every invalid field`() {
        val result =
            gql(
                createMutation,
                mapOf(
                    "input" to mapOf("title" to " ", "runtimeMinutes" to 0, "genreCodes" to emptyList<String>()),
                    "confirm" to true,
                ),
            )

        assertEquals("VALIDATION_ERROR", result.errorCode())
        val fields =
            result.errors
                .path(0)
                .path("extensions")
                .path("fieldErrors")
                .values()
                .map { it.path("field").asString() }
        assertEquals(listOf("title", "runtimeMinutes", "genreCodes"), fields)
    }

    @Test
    fun `malformed scalars and IDs are rejected as invalid input`() {
        val badDate = gql(createMutation, mapOf("input" to movieInput("Bad Date", "releaseDate" to "2026-13-45"), "confirm" to true))
        val badId = gql("""{ movie(id: "not-a-uuid") { id } }""")

        assertTrue(
            badDate.errors
                .path(0)
                .path("message")
                .asString()
                .contains("ISO date"),
            badDate.toString(),
        )
        assertEquals("VALIDATION_ERROR", badId.errorCode(), badId.toString())
    }

    @Test
    fun `unknown IDs give null for reads and NOT_FOUND for writes`() {
        val missing = UUID.randomUUID()

        assertTrue(gql("{ movie(id: \"$missing\") { id } }").data("movie").isNull)
        assertEquals("NOT_FOUND", gql(updateMutation, mapOf("id" to missing.toString(), "v" to 0, "input" to movieInput("X"))).errorCode())
        assertEquals("NOT_FOUND", gql("""mutation { removeCastCredit(creditId: "$missing") }""").errorCode())
    }

    @Test
    fun `an update based on an old version returns CONFLICT STALE_VERSION`() {
        val id = createMovie("Versioned ${UUID.randomUUID()}")
        val first = gql(updateMutation, mapOf("id" to id, "v" to 0, "input" to movieInput("First Edit ${UUID.randomUUID()}")))
        assertEquals(1, first.data("updateMovie.movie.version").asInt())

        val stale = gql(updateMutation, mapOf("id" to id, "v" to 0, "input" to movieInput("Second Edit")))

        assertEquals("CONFLICT", stale.errorCode())
        assertEquals(
            "STALE_VERSION",
            stale.errors
                .path(0)
                .path("extensions")
                .path("reason")
                .asString(),
        )
    }

    @Test
    fun `credits can be linked and unlinked and appear on both the movie and the person`() {
        val movieId = createMovie("Credits Movie ${UUID.randomUUID()}")
        val personId = createPerson("Both Ways ${UUID.randomUUID()}", listOf("CAST", "CREATOR"))

        val cast =
            gql("""mutation { addCastCredit(movieId: "$movieId", personId: "$personId", characterName: " Lead ") { id characterName } }""")
        gql("""mutation { addCreatorCredit(movieId: "$movieId", personId: "$personId", role: DIRECTOR) { id } }""")
        val roleless = gql("""mutation { addCreatorCredit(movieId: "$movieId", personId: "$personId") { id role } }""")

        assertEquals("Lead", cast.data("addCastCredit.characterName").asString())
        assertTrue(roleless.data("addCreatorCredit.role").isNull)
        val movie = gql("{ movie(id: \"$movieId\") { cast { person { id } } creators { role } } }")
        assertEquals(listOf(personId), movie.data("movie.cast").values().map { it.path("person").path("id").asString() })
        assertEquals(listOf("DIRECTOR", "null"), movie.data("movie.creators").values().map { it.path("role").toString().trim('"') })
        val person = gql("{ person(id: \"$personId\") { castCredits { movie { id } } creatorCredits { role } } }")
        assertEquals(movieId, person.data("person.castCredits.0.movie.id").asString())
        assertEquals(2, person.data("person.creatorCredits").size())

        val castId = cast.data("addCastCredit.id").asString()
        assertEquals(castId, gql("""mutation { removeCastCredit(creditId: "$castId") }""").data("removeCastCredit").asString())
        assertEquals(0, gql("{ movie(id: \"$movieId\") { cast { id } } }").data("movie.cast").size())
    }

    @Test
    fun `credit conflicts are reported with their reason`() {
        val movieId = createMovie("Conflict Movie ${UUID.randomUUID()}")
        val actor = createPerson("Actor Only ${UUID.randomUUID()}", listOf("CAST"))
        gql("""mutation { addCastCredit(movieId: "$movieId", personId: "$actor") { id } }""")

        val duplicate = gql("""mutation { addCastCredit(movieId: "$movieId", personId: "$actor") { id } }""")
        val wrongCategory = gql("""mutation { addCreatorCredit(movieId: "$movieId", personId: "$actor", role: WRITER) { id } }""")

        assertEquals(
            "DUPLICATE_ASSOCIATION",
            duplicate.errors
                .path(0)
                .path("extensions")
                .path("reason")
                .asString(),
        )
        assertEquals(
            "CATEGORY_REQUIRED",
            wrongCategory.errors
                .path(0)
                .path("extensions")
                .path("reason")
                .asString(),
        )
    }

    @Test
    fun `person detail shows age only when the birth date is known`() {
        val known = createPerson("Aged ${UUID.randomUUID()}", listOf("CAST"), dateOfBirth = "1990-10-26")
        val unknown = createPerson("Ageless ${UUID.randomUUID()}", listOf("CAST"))

        // Clock is 2026-10-25: the 36th birthday is tomorrow.
        assertEquals(35, gql("{ person(id: \"$known\") { age } }").data("person.age").asInt())
        assertTrue(gql("{ person(id: \"$unknown\") { age } }").data("person.age").isNull)
    }

    @Test
    fun `genre options come from the controlled list`() {
        val genres = gql("{ genres { code name } }").data("genres")

        assertEquals(18, genres.size())
        assertEquals("Action", genres.path(0).path("name").asString())
    }
}
