package moviecatalogue.catalogue.graphql

import graphql.introspection.IntrospectionQuery
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/** Query depth/complexity limits protect the server from expensive requests. */
class GraphQlLimitsTest : GraphQlApiTest() {
    @Test
    fun `an alias flood is refused before execution, but standard introspection still works`() {
        val flood = (1..600).joinToString(" ") { "a$it: genres { code }" }
        val refused = gql("{ $flood }")

        assertTrue(
            refused.errors
                .path(0)
                .path("message")
                .asString()
                .contains("complexity"),
            refused.toString(),
        )
        assertTrue(refused.data("a1").isMissingNode)
        assertTrue(gql(IntrospectionQuery.INTROSPECTION_QUERY).errors.isEmpty)
    }
}
