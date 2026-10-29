package moviecatalogue.catalogue.graphql

import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The database is shared with other tests, so each test tags its titles with a unique token and
 * searches for it. That isolates the rows without needing an empty database.
 */
@TestPropertySource(properties = ["spring.jpa.properties.hibernate.generate_statistics=true"])
class MovieListingTest : GraphQlApiTest() {
    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private fun tag() = "t" + UUID.randomUUID().toString().take(8)

    private fun list(
        search: String,
        args: String = "",
        filter: String = "",
    ) = gql(
        """{ movies(filter: {search: "$search" $filter} $args) {
             edges { cursor node { id title releaseDate } } pageInfo { hasNextPage endCursor } } }""",
    )

    private fun nodes(result: GqlResponse): List<JsonNode> {
        assertTrue(result.errors.isEmpty, result.toString())
        return result.data("movies.edges").values().map { it.path("node") }
    }

    private fun titles(result: GqlResponse) = nodes(result).map { it.path("title").asString() }

    private fun ids(result: GqlResponse) = nodes(result).map { it.path("id").asString() }

    /** Follows endCursor until hasNextPage is false and returns every node, in order. */
    private fun allPages(
        search: String,
        sort: String,
        first: Int,
    ): List<JsonNode> {
        val seen = mutableListOf<JsonNode>()
        var after = ""
        repeat(100) {
            val page = list(search, "sort: $sort, first: $first $after")
            val nodes = nodes(page)
            assertTrue(nodes.size <= first)
            seen += nodes
            if (!page.data("movies.pageInfo.hasNextPage").asBoolean()) return seen
            after = """, after: "${page.data("movies.pageInfo.endCursor").asString()}""""
        }
        error("paging did not finish")
    }

    private fun GqlResponse.fieldError() =
        errors
            .path(0)
            .path("extensions")
            .path("fieldErrors")
            .path(0)
            .path("field")
            .asString()

    private fun total(result: GqlResponse) =
        result.data("movies.totalCount").let {
            it.path("count").asInt() to
                it.path("capped").asBoolean()
        }

    @Test
    fun `walking every page with each sort returns each movie once, in the same order as one big page`() {
        val t = tag()
        createMovie("$t Same", releaseDate = "2010-05-05")
        createMovie("$t same", releaseDate = "2010-05-05")
        createMovie("$t Alpha", releaseDate = "1999-01-01")
        createMovie("$t beta")
        createMovie("$t Gamma")
        createMovie("$t delta", releaseDate = "2030-01-01")
        createMovie("$t SAME")

        for (sort in listOf("TITLE_ASC", "TITLE_DESC", "RELEASE_DATE_NEWEST", "RELEASE_DATE_OLDEST")) {
            val paged = allPages(t, sort, first = 2).map { it.path("id").asString() }
            val onePage = ids(list(t, "sort: $sort, first: 50"))

            assertEquals(7, paged.size, sort)
            assertEquals(paged.toSet().size, paged.size, "no repeats with $sort")
            assertEquals(onePage, paged, sort)
        }
    }

    @Test
    fun `equal titles and equal dates are ordered by id, ascending or descending with the sort`() {
        val t = tag()
        val twins = listOf("$t Same", "$t same", "$t SAME").map { createMovie(it, releaseDate = "2012-12-12") }.sorted()

        assertEquals(twins, allPages(t, "TITLE_ASC", first = 1).map { it.path("id").asString() })
        assertEquals(twins.reversed(), allPages(t, "TITLE_DESC", first = 1).map { it.path("id").asString() })
        assertEquals(twins, allPages(t, "RELEASE_DATE_OLDEST", first = 1).map { it.path("id").asString() })
        assertEquals(twins.reversed(), allPages(t, "RELEASE_DATE_NEWEST", first = 1).map { it.path("id").asString() })
    }

    @Test
    fun `title sort is case-insensitive in both directions`() {
        val t = tag()
        listOf("$t beta", "$t Alpha", "$t gamma").forEach { createMovie(it) }

        assertEquals(listOf("$t Alpha", "$t beta", "$t gamma"), titles(list(t, "sort: TITLE_ASC")))
        assertEquals(listOf("$t gamma", "$t beta", "$t Alpha"), titles(list(t, "sort: TITLE_DESC")))
    }

    @Test
    fun `unknown release dates sort last in both date directions, also across page boundaries`() {
        val t = tag()
        createMovie("$t Old", releaseDate = "2001-01-01")
        val unknown = listOf(createMovie("$t Unknown 1"), createMovie("$t Unknown 2"), createMovie("$t Unknown 3")).sorted()
        createMovie("$t New", releaseDate = "2029-01-01")

        val newest = allPages(t, "RELEASE_DATE_NEWEST", first = 2)
        val oldest = allPages(t, "RELEASE_DATE_OLDEST", first = 2)

        assertEquals(listOf("$t New", "$t Old"), newest.take(2).map { it.path("title").asString() })
        assertEquals(unknown.reversed(), newest.drop(2).map { it.path("id").asString() })
        assertEquals(listOf("$t Old", "$t New"), oldest.take(2).map { it.path("title").asString() })
        assertEquals(unknown, oldest.drop(2).map { it.path("id").asString() })
    }

    @Test
    fun `an empty result has no edges, no next page, no end cursor and a total of zero`() {
        val empty =
            gql(
                """{ movies(filter: {search: "${tag()}"}) { nodes { id } pageInfo { hasNextPage endCursor } totalCount { count capped } } }""",
            )

        assertTrue(empty.data("movies.nodes").isEmpty, empty.toString())
        assertFalse(empty.data("movies.pageInfo.hasNextPage").asBoolean())
        assertTrue(empty.data("movies.pageInfo.endCursor").isNull)
        assertEquals(0 to false, total(empty))
    }

    @Test
    fun `first accepts 1 to 50, and hasNextPage is false exactly at the end`() {
        val t = tag()
        repeat(51) { createMovie("$t Film ${"%02d".format(it)}") }

        assertEquals("first", list(t, "first: 0").fieldError())
        assertEquals("first", list(t, "first: 51").fieldError())
        assertEquals("first", list(t, "first: -1").fieldError())
        val one = list(t, "first: 1")
        assertEquals(listOf("$t Film 00"), titles(one))
        assertTrue(one.data("movies.pageInfo.hasNextPage").asBoolean())
        val fifty = list(t, "first: 50")
        assertEquals(50, nodes(fifty).size)
        assertTrue(fifty.data("movies.pageInfo.hasNextPage").asBoolean())
        val rest = list(t, """first: 50, after: "${fifty.data("movies.pageInfo.endCursor").asString()}"""")
        assertEquals(listOf("$t Film 50"), titles(rest))
        assertFalse(rest.data("movies.pageInfo.hasNextPage").asBoolean())
        assertEquals(20, nodes(list(t)).size, "default page size")
    }

    @Test
    fun `malformed cursors and cursors from another sort, filter or list are rejected`() {
        val t = tag()
        repeat(3) { createMovie("$t Film $it", genres = listOf("COMEDY")) }
        val cursor = list(t, "first: 1, sort: TITLE_ASC").data("movies.pageInfo.endCursor").asString()
        createPerson("$t Actor", listOf("CAST"))
        val castCursor =
            gql("""{ castMembers(search: "$t", first: 1) { pageInfo { endCursor } } }""").data("castMembers.pageInfo.endCursor").asString()
        val base64 = { text: String -> Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray()) }
        val badId = base64("""[["movies","TITLE_ASC","${t.lowercase()}",[],null],["x","not-a-uuid"]]""")

        val rejected =
            mapOf(
                "garbage" to list(t, """after: "not a cursor!""""),
                "valid base64, not JSON" to list(t, """after: "${base64("hello")}""""),
                "wrong JSON shape" to list(t, """after: "${base64("{}")}""""),
                "bad id in key" to list(t, """after: "$badId""""),
                "other sort" to list(t, """sort: TITLE_DESC, after: "$cursor""""),
                "other search" to list("$t Film", """after: "$cursor""""),
                "other genre filter" to list(t, """after: "$cursor"""", filter = """, genreCodes: ["COMEDY"]"""),
                "cursor from the Cast list" to list(t, """after: "$castCursor""""),
            )

        rejected.forEach { (case, result) ->
            assertEquals("VALIDATION_ERROR", result.errorCode(), "$case: $result")
            assertEquals("after", result.fieldError(), case)
        }
        // Only the page size may change between pages; search text differing only in case is the same search.
        assertEquals(2, nodes(list(t.uppercase(), """first: 5, after: "$cursor"""")).size)
    }

    @Test
    fun `a cursor still works after the movie it points to is deleted`() {
        val t = tag()
        repeat(4) { createMovie("$t Film $it") }
        val first = list(t, "first: 2")
        val last = nodes(first).last().path("id").asString()
        val version = gql("""{ movie(id: "$last") { version } }""").data("movie.version").asInt()
        gql("""mutation { deleteMovie(id: "$last", expectedVersion: $version) }""")

        val next = list(t, """first: 2, after: "${first.data("movies.pageInfo.endCursor").asString()}"""")

        assertEquals(listOf("$t Film 2", "$t Film 3"), titles(next))
    }

    @Test
    fun `totalCount is exact up to the cap and reports capped above it`() {
        val t = tag()
        repeat(3) { createMovie("$t Small $it") }
        assertEquals(3 to false, total(gql("""{ movies(filter: {search: "$t Small"}) { totalCount { count capped } } }""")))

        // 10,001 rows straight into the table: enough to pass the cap without 10,001 API calls.
        jdbc.update(
            """insert into movie (id, title, version, created_at, updated_at)
               select gen_random_uuid(), ? || ' Bulk ' || n, 0, now(), now() from generate_series(1, 10001) n""",
            t,
        )
        try {
            assertEquals(10_000 to true, total(gql("""{ movies(filter: {search: "$t Bulk"}) { totalCount { count capped } } }""")))
        } finally {
            jdbc.update("delete from movie where title like ?", "$t Bulk %")
        }
    }

    @Test
    fun `genre filter matches any selected genre and counts each movie once`() {
        val t = tag()
        createMovie("$t Both", genres = listOf("COMEDY", "HORROR"))
        createMovie("$t Comedy", genres = listOf("COMEDY"))
        createMovie("$t Drama", genres = listOf("DRAMA"))

        val result =
            gql(
                """{ movies(filter: {search: "$t", genreCodes: ["HORROR", "COMEDY"]}) { edges { node { title } } totalCount { count } } }""",
            )

        assertEquals(listOf("$t Both", "$t Comedy"), result.data("movies.edges").values().map { it.path("node").path("title").asString() })
        assertEquals(2, result.data("movies.totalCount.count").asInt())
    }

    @Test
    fun `year filter excludes unknown dates and combines with genre using AND`() {
        val t = tag()
        createMovie("$t A", genres = listOf("WAR"), releaseDate = "2015-06-01")
        createMovie("$t B", genres = listOf("DRAMA"), releaseDate = "2015-12-31")
        createMovie("$t C", genres = listOf("WAR"), releaseDate = "2016-01-01")
        createMovie("$t D", genres = listOf("WAR"))

        assertEquals(listOf("$t A", "$t B"), titles(list(t, filter = ", releaseYear: 2015")))
        assertEquals(listOf("$t A"), titles(list(t, filter = """, releaseYear: 2015, genreCodes: ["WAR"]""")))
    }

    @Test
    fun `search trims input, ignores case and treats percent and underscore literally`() {
        val t = tag()
        createMovie("$t 100% Real")
        createMovie("$t 100 Real")
        createMovie("$t snake_case")
        createMovie("$t snakeXcase")

        assertEquals(listOf("$t 100% Real"), titles(list("  ${t.uppercase()} 100%  ")))
        assertEquals(listOf("$t snake_case"), titles(list("$t snake_")))
    }

    @Test
    fun `an unknown sort value is rejected`() {
        val badSort = list(tag(), "sort: POPULARITY")

        assertTrue(
            badSort.errors
                .path(0)
                .path("message")
                .asString()
                .contains("POPULARITY"),
            badSort.toString(),
        )
        assertTrue(badSort.data("movies").isMissingNode)
    }

    @Test
    fun `nested fields cost the same number of SQL statements for 5 or 20 movies (no N+1), and only a requested total is counted`() {
        val small = tag()
        val large = tag()
        repeat(5) { createMovieWithCredits("$small M$it") }
        repeat(20) { createMovieWithCredits("$large M$it") }

        val statementsFor5 = statementsFor(small)
        val statementsFor20 = statementsFor(large)
        val withTotal = statementsFor(large, extra = "totalCount { count }")

        assertEquals(statementsFor5, statementsFor20)
        assertTrue(statementsFor20 <= 5, "page, genres, cast, creators, artwork; was $statementsFor20")
        assertEquals(statementsFor20 + 1, withTotal)
    }

    private fun createMovieWithCredits(title: String) {
        val movie = createMovie(title, genres = listOf("DRAMA", "WAR"))
        val person = createPerson("$title Person", listOf("CAST", "CREATOR"))
        gql("""mutation { addCastCredit(movieId: "$movie", personId: "$person") { id } }""")
        gql("""mutation { addCreatorCredit(movieId: "$movie", personId: "$person", role: DIRECTOR) { id } }""")
    }

    private fun statementsFor(
        search: String,
        extra: String = "",
    ): Long {
        val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        statistics.clear()
        val result =
            gql(
                """{ movies(first: 50, filter: {search: "$search"}) {
                     nodes { title genres { name } artwork { thumbnailUrl } cast { person { name } } creators { role person { name } } }
                     $extra
                   } }""",
            )
        assertTrue(result.errors.isEmpty, result.toString())
        assertNull(result.data("movies.nodes").firstOrNull { it.path("cast").isEmpty }, "every movie has credits")
        return statistics.prepareStatementCount
    }
}
