package moviecatalogue.catalogue.graphql

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeopleListingTest : GraphQlApiTest() {
    private fun tag() = "p" + UUID.randomUUID().toString().take(8)

    private fun names(
        result: GqlResponse,
        field: String,
    ): List<String> {
        assertTrue(result.errors.isEmpty, result.toString())
        return result.data("$field.nodes").values().map { it.path("name").asString() }
    }

    /** Follows endCursor through the whole list and returns the ids in order. */
    private fun allIds(
        field: String,
        args: String,
    ): List<String> {
        val ids = mutableListOf<String>()
        var after = ""
        repeat(100) {
            val page = gql("""{ $field($args, first: 1 $after) { nodes { id } pageInfo { hasNextPage endCursor } } }""")
            assertTrue(page.errors.isEmpty, page.toString())
            ids += page.data("$field.nodes").values().map { it.path("id").asString() }
            if (!page.data("$field.pageInfo.hasNextPage").asBoolean()) return ids
            after = """, after: "${page.data("$field.pageInfo.endCursor").asString()}""""
        }
        error("paging did not finish")
    }

    @Test
    fun `cast and creator lists come from categories, so a shared person appears in both and uncredited people are listed`() {
        val t = tag()
        createPerson("$t Shared", listOf("CAST", "CREATOR"))
        createPerson("$t ActorOnly", listOf("CAST"))
        createPerson("$t CreatorOnly", listOf("CREATOR"))

        assertEquals(listOf("$t ActorOnly", "$t Shared"), names(gql("""{ castMembers(search: "$t") { nodes { name } } }"""), "castMembers"))
        assertEquals(listOf("$t CreatorOnly", "$t Shared"), names(gql("""{ creators(search: "$t") { nodes { name } } }"""), "creators"))
    }

    @Test
    fun `role filter lists a person once even with several matching credits, and excludes role-less creators`() {
        val t = tag()
        val director = createPerson("$t Director", listOf("CREATOR"))
        val unspecified = createPerson("$t Unspecified", listOf("CREATOR"))
        repeat(3) {
            val movie = createMovie("$t Movie $it")
            gql("""mutation { addCreatorCredit(movieId: "$movie", personId: "$director", role: DIRECTOR) { id } }""")
            gql("""mutation { addCreatorCredit(movieId: "$movie", personId: "$unspecified") { id } }""")
        }

        val directors = gql("""{ creators(search: "$t", role: DIRECTOR) { nodes { name } totalCount { count } } }""")
        val all = gql("""{ creators(search: "$t") { nodes { name } totalCount { count } } }""")

        assertEquals(listOf("$t Director"), names(directors, "creators"))
        assertEquals(1, directors.data("creators.totalCount.count").asInt())
        assertEquals(2, all.data("creators.totalCount.count").asInt())
    }

    @Test
    fun `paging through equal names in both directions returns everyone once, ordered by id within a name`() {
        val t = tag()
        val twins = listOf("$t Same", "$t same", "$t SAME").map { createPerson(it, listOf("CAST")) }.sorted()
        val first = createPerson("$t A", listOf("CAST"))
        val last = createPerson("$t z", listOf("CAST"))

        assertEquals(listOf(first) + twins + last, allIds("castMembers", """search: "$t", sort: NAME_ASC"""))
        assertEquals(listOf(last) + twins.reversed() + first, allIds("castMembers", """search: "$t", sort: NAME_DESC"""))
    }

    @Test
    fun `creators page through a role filter, and cursors cannot move between the two lists or filters`() {
        val t = tag()
        val people = (1..3).map { createPerson("$t Writer $it", listOf("CAST", "CREATOR")) }
        people.forEach {
            gql(
                """mutation { addCreatorCredit(movieId: "${createMovie("$t Film")}", personId: "$it", role: WRITER) { id } }""",
            )
        }

        assertEquals(people, allIds("creators", """search: "$t", role: WRITER"""))
        val cursor =
            gql("""{ creators(search: "$t", role: WRITER, first: 1) { pageInfo { endCursor } } }""")
                .data("creators.pageInfo.endCursor")
                .asString()

        for (other in listOf("""creators(search: "$t", role: DIRECTOR""", """creators(search: "$t"""", """castMembers(search: "$t"""")) {
            val result = gql("""{ $other, after: "$cursor") { nodes { id } } }""")
            assertEquals("VALIDATION_ERROR", result.errorCode(), "$other: $result")
        }
    }

    @Test
    fun `first outside 1 to 50 is rejected`() {
        assertEquals("VALIDATION_ERROR", gql("{ castMembers(first: 0) { nodes { id } } }").errorCode())
        assertEquals("VALIDATION_ERROR", gql("{ creators(first: 51) { nodes { id } } }").errorCode())
    }

    @Test
    fun `blank search means no search`() {
        createPerson("${tag()} Someone", listOf("CAST"))
        val withBlank = gql("""{ castMembers(search: "   ") { totalCount { count } } }""").data("castMembers.totalCount.count").asInt()
        val without = gql("{ castMembers { totalCount { count } } }").data("castMembers.totalCount.count").asInt()

        assertEquals(without, withBlank)
        assertTrue(without > 0)
    }
}
