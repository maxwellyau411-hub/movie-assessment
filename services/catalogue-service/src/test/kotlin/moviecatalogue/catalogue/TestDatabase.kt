package moviecatalogue.catalogue

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.postgresql.PostgreSQLContainer

/** One PostgreSQL 18 container (the Compose image) for every test context in this JVM. */
object TestDatabase {
    private val postgres: PostgreSQLContainer =
        PostgreSQLContainer("postgres:18.6-alpine").withDatabaseName("catalogue_db").also { it.start() }

    fun register(registry: DynamicPropertyRegistry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl)
        registry.add("spring.datasource.username", postgres::getUsername)
        registry.add("spring.datasource.password", postgres::getPassword)
    }
}
