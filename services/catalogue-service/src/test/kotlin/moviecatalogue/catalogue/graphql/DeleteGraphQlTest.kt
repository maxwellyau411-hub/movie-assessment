package moviecatalogue.catalogue.graphql

import moviecatalogue.catalogue.TestPng
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeleteGraphQlTest : GraphQlApiTest() {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private fun unique(prefix: String) = "$prefix ${UUID.randomUUID()}"

    private fun movieVersion(id: String) = gql("""{ movie(id: "$id") { version } }""").data("movie.version").asInt()

    private fun personVersion(id: String) = gql("""{ person(id: "$id") { version } }""").data("person.version").asInt()

    private fun deleteMovie(
        id: String,
        version: Int,
    ) = gql("""mutation { deleteMovie(id: "$id", expectedVersion: $version) }""")

    private fun deletePerson(
        id: String,
        version: Int,
    ) = gql("""mutation { deletePerson(id: "$id", expectedVersion: $version) }""")

    private fun addCast(
        movieId: String,
        personId: String,
    ): String = gql("""mutation { addCastCredit(movieId: "$movieId", personId: "$personId") { id } }""").data("addCastCredit.id").asString()

    private fun addCreator(
        movieId: String,
        personId: String,
        role: String,
    ): String =
        gql("""mutation { addCreatorCredit(movieId: "$movieId", personId: "$personId", role: $role) { id } }""")
            .data("addCreatorCredit.id")
            .asString()

    private fun rowsFor(
        table: String,
        movieId: String,
    ): Int = jdbc.queryForObject("select count(*) from $table where movie_id = ?", Int::class.java, UUID.fromString(movieId))!!

    private fun GqlResponse.reason() =
        errors
            .path(0)
            .path("extensions")
            .path("reason")
            .asString()

    @Test
    fun `deleting a movie removes its genre links, credits and poster, but keeps the people`() {
        val movieId = createMovie(unique("Doomed Film"), genres = listOf("DRAMA", "WAR"))
        val actor = createPerson(unique("Kept Actor"), listOf("CAST"))
        val director = createPerson(unique("Kept Director"), listOf("CREATOR"))
        addCast(movieId, actor)
        addCreator(movieId, director, "DIRECTOR")
        assertTrue(uploadPoster(movieId, TestPng.bytes()).errors.isEmpty)
        val artworkId = media.stored.keys.single()

        val result = deleteMovie(movieId, movieVersion(movieId))

        assertEquals(movieId, result.data("deleteMovie").asString(), result.toString())
        assertTrue(gql("""{ movie(id: "$movieId") { id } }""").data("movie").isNull)
        for (table in listOf("movie_genre", "cast_credit", "creator_credit", "movie_poster")) {
            assertEquals(0, rowsFor(table, movieId), table)
        }
        assertEquals(actor, gql("""{ person(id: "$actor") { id } }""").data("person.id").asString())
        assertEquals(director, gql("""{ person(id: "$director") { id } }""").data("person.id").asString())
        assertEquals(listOf(artworkId), media.deleteRequests, "the image is deleted from media-service")
    }

    @Test
    fun `a person with credits cannot be deleted, and the error lists each credited movie once`() {
        val alpha = createMovie(unique("Alpha"))
        val beta = createMovie(unique("Beta"))
        val person = createPerson(unique("Busy Person"), listOf("CAST", "CREATOR"))
        val castCredit = addCast(alpha, person)
        val directorCredit = addCreator(alpha, person, "DIRECTOR")
        val writerCredit = addCreator(beta, person, "WRITER")

        val refused = deletePerson(person, personVersion(person))

        assertEquals("CONFLICT", refused.errorCode(), refused.toString())
        assertEquals("PERSON_HAS_CREDITS", refused.reason())
        val extensions = refused.errors.path(0).path("extensions")
        assertEquals(2, extensions.path("movieCount").asInt()) // three credits, two movies
        assertEquals(listOf(alpha, beta), extensions.path("movies").values().map { it.path("id").asString() })
        assertTrue(refused.json.path("data").isNull) // deletePerson is ID!, so the error nulls all data
        assertEquals(person, gql("""{ person(id: "$person") { id } }""").data("person.id").asString())

        gql("""mutation { removeCastCredit(creditId: "$castCredit") }""")
        gql("""mutation { removeCreatorCredit(creditId: "$directorCredit") }""")
        gql("""mutation { removeCreatorCredit(creditId: "$writerCredit") }""")
        val deleted = deletePerson(person, personVersion(person))

        assertEquals(person, deleted.data("deletePerson").asString(), deleted.toString())
        assertTrue(gql("""{ person(id: "$person") { id } }""").data("person").isNull)
    }

    @Test
    fun `the conflict lists at most 10 movies but reports the full count`() {
        val person = createPerson(unique("Prolific Actor"), listOf("CAST"))
        repeat(12) { addCast(createMovie(unique("Role $it")), person) }

        val refused = deletePerson(person, personVersion(person))

        val extensions = refused.errors.path(0).path("extensions")
        assertEquals(12, extensions.path("movieCount").asInt(), refused.toString())
        assertEquals(10, extensions.path("movies").size())
    }

    @Test
    fun `a stale version is refused for movies and people, and nothing is deleted`() {
        val movieId = createMovie(unique("Edited Meanwhile"))
        val loadedMovieVersion = movieVersion(movieId)
        gql(
            """mutation { updateMovie(id: "$movieId", expectedVersion: $loadedMovieVersion, input: {title: "${unique(
                "Renamed",
            )}", genreCodes: ["DRAMA"]}, confirmDuplicate: true) { __typename } }""",
        )
        val personId = createPerson(unique("Linked Meanwhile"), listOf("CAST"))
        val loadedPersonVersion = personVersion(personId)
        // A credit added after the person was loaded bumps the version, so it cannot slip past the delete.
        addCast(createMovie(unique("Late Credit")), personId)

        val staleMovie = deleteMovie(movieId, loadedMovieVersion)
        val stalePerson = deletePerson(personId, loadedPersonVersion)

        assertEquals("CONFLICT" to "STALE_VERSION", staleMovie.errorCode() to staleMovie.reason(), staleMovie.toString())
        assertEquals("CONFLICT" to "STALE_VERSION", stalePerson.errorCode() to stalePerson.reason(), stalePerson.toString())
        assertFalse(gql("""{ movie(id: "$movieId") { id } }""").data("movie").isNull)
        assertFalse(gql("""{ person(id: "$personId") { id } }""").data("person").isNull)
    }

    @Test
    fun `unknown ids give NOT_FOUND, and so does deleting a second time`() {
        val unknown = UUID.randomUUID()
        assertEquals("NOT_FOUND", deleteMovie(unknown.toString(), 0).errorCode())
        assertEquals("NOT_FOUND", deletePerson(unknown.toString(), 0).errorCode())

        val movieId = createMovie(unique("Deleted Twice"))
        val personId = createPerson(unique("Deleted Twice"), listOf("CREATOR"))
        val movieVersion = movieVersion(movieId)
        val personVersion = personVersion(personId)
        assertTrue(deleteMovie(movieId, movieVersion).errors.isEmpty)
        assertTrue(deletePerson(personId, personVersion).errors.isEmpty)

        assertEquals("NOT_FOUND", deleteMovie(movieId, movieVersion).errorCode())
        assertEquals("NOT_FOUND", deletePerson(personId, personVersion).errorCode())
    }
}
