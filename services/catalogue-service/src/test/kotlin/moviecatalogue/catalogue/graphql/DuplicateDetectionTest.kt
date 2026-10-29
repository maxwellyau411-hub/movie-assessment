package moviecatalogue.catalogue.graphql

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Chapter 03 duplicate rules: warn first, write nothing, save only after explicit confirmation. */
class DuplicateDetectionTest : GraphQlApiTest() {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val createMovie =
        """mutation(${'$'}input: MovieInput!, ${'$'}confirm: Boolean) {
             createMovie(input: ${'$'}input, confirmDuplicate: ${'$'}confirm) {
               __typename
               ... on MovieSaved { movie { id } }
               ... on DuplicateMovieWarning { message candidates { id title releaseDate } }
             } }"""

    private val updateMovie =
        """mutation(${'$'}id: ID!, ${'$'}v: Int!, ${'$'}input: MovieInput!) {
             updateMovie(id: ${'$'}id, expectedVersion: ${'$'}v, input: ${'$'}input) { __typename } }"""

    private val createPerson =
        """mutation(${'$'}input: PersonInput!, ${'$'}confirm: Boolean) {
             createPerson(input: ${'$'}input, confirmDuplicate: ${'$'}confirm) {
               __typename
               ... on DuplicatePersonWarning { candidates { id name dateOfBirth categories } }
             } }"""

    private fun movie(
        title: String,
        date: String? = null,
        confirm: Boolean = false,
    ) = gql(
        createMovie,
        mapOf(
            "input" to mapOf("title" to title, "releaseDate" to date, "genreCodes" to listOf("DRAMA")),
            "confirm" to confirm,
        ),
    )

    private fun person(
        name: String,
        dob: String? = null,
        categories: List<String> = listOf("CAST"),
        confirm: Boolean = false,
    ) = gql(createPerson, mapOf("input" to mapOf("name" to name, "dateOfBirth" to dob, "categories" to categories), "confirm" to confirm))

    private fun moviesTitled(title: String) = jdbc.queryForObject("select count(*) from movie where title = ?", Int::class.java, title)!!

    @Test
    fun `a similar title with the same date warns and writes nothing until confirmed`() {
        val t = "Night Garden ${UUID.randomUUID()}"
        val original = movie(t, "2020-05-01").data("createMovie.movie.id").asString()

        val warning = movie("  ${t.uppercase().replace(" ", "   ")}  ", "2020-05-01")
        assertEquals("DuplicateMovieWarning", warning.data("createMovie.__typename").asString())
        assertEquals(listOf(original), warning.data("createMovie.candidates").values().map { it.path("id").asString() })
        assertEquals(1, moviesTitled(t))

        val confirmed = movie(t, "2020-05-01", confirm = true)
        assertEquals("MovieSaved", confirmed.data("createMovie.__typename").asString())
        assertEquals(2, moviesTitled(t))
    }

    @Test
    fun `an unknown date on either side still counts as a possible duplicate, different known dates do not`() {
        val t = "Echo Field ${UUID.randomUUID()}"
        movie(t, "2010-01-01", confirm = true)

        assertEquals("DuplicateMovieWarning", movie(t).data("createMovie.__typename").asString())
        assertEquals("MovieSaved", movie(t, "2011-01-01").data("createMovie.__typename").asString())
    }

    @Test
    fun `editing a movie does not warn about itself, but does warn about another match`() {
        val t = "Self Check ${UUID.randomUUID()}"
        val id = movie(t, "2001-01-01", confirm = true).data("createMovie.movie.id").asString()
        val input = mapOf("title" to t, "releaseDate" to "2001-01-01", "genreCodes" to listOf("WAR"))

        assertEquals(
            "MovieSaved",
            gql(updateMovie, mapOf("id" to id, "v" to 0, "input" to input)).data("updateMovie.__typename").asString(),
        )

        movie(t, "2001-01-01", confirm = true)
        assertEquals(
            "DuplicateMovieWarning",
            gql(updateMovie, mapOf("id" to id, "v" to 1, "input" to input)).data("updateMovie.__typename").asString(),
        )
    }

    @Test
    fun `people with the same name warn unless both birth dates are known and differ, across categories`() {
        val n = "Rowan Vale ${UUID.randomUUID()}"
        person(n, "1980-01-01", categories = listOf("CREATOR"), confirm = true)

        val sameDate = person(n, "1980-01-01")
        assertEquals("DuplicatePersonWarning", sameDate.data("createPerson.__typename").asString())
        assertEquals("CREATOR", sameDate.data("createPerson.candidates.0.categories.0").asString())
        assertEquals("DuplicatePersonWarning", person(n, null).data("createPerson.__typename").asString())
        assertEquals("PersonSaved", person(n, "1999-09-09").data("createPerson.__typename").asString())
    }

    @Test
    fun `validation runs before duplicate detection and confirmation bypasses nothing else`() {
        val result = movie(" ", confirm = true)

        assertEquals("VALIDATION_ERROR", result.errorCode())
        assertTrue(result.data("createMovie").isMissingNode || result.data("createMovie").isNull)
    }
}
