package moviecatalogue.catalogue.graphql

import graphql.analysis.MaxQueryComplexityInstrumentation
import graphql.analysis.MaxQueryDepthInstrumentation
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Rejects oversized documents before execution. List page size (`first`) is separately capped at 50. Depth 15
 * still admits the standard introspection query; real catalogue queries stay below depth 6.
 * Complexity counts fields (aliases included), so a flood of aliased fields is refused.
 */
@Configuration(proxyBeanMethods = false)
class GraphQlLimits {
    @Bean
    fun maxQueryDepth() = MaxQueryDepthInstrumentation(MAX_DEPTH)

    @Bean
    fun maxQueryComplexity() = MaxQueryComplexityInstrumentation(MAX_COMPLEXITY)

    companion object {
        const val MAX_DEPTH = 15
        const val MAX_COMPLEXITY = 500
    }
}
