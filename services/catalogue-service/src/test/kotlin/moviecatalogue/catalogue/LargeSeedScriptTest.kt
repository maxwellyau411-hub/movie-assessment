package moviecatalogue.catalogue

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.images.builder.Transferable
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Runs infra/large-seed/large-seed.sql with psql, as Compose does, against its own freshly
 * migrated database (small sizes), so the shared test database stays untouched.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LargeSeedScriptTest {
    // Gradle runs tests from the module directory.
    private val script = Path.of("../../infra/large-seed/large-seed.sql")
    private val postgres = PostgreSQLContainer("postgres:18.6-alpine").withDatabaseName("catalogue_db")
    private lateinit var jdbc: JdbcTemplate

    @BeforeAll
    fun start() {
        postgres.start()
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        postgres.copyFileToContainer(Transferable.of(Files.readAllBytes(script)), "/tmp/large-seed.sql")
        jdbc = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @AfterAll
    fun stop() = postgres.stop()

    private fun seed(
        movies: Int,
        people: Int,
        credits: Int,
    ) = postgres.execInContainer(
        "psql",
        "-U",
        postgres.username,
        "-d",
        postgres.databaseName,
        "-v",
        "movies=$movies",
        "-v",
        "people=$people",
        "-v",
        "credits=$credits",
        "-f",
        "/tmp/large-seed.sql",
    )

    private fun count(sql: String) = jdbc.queryForObject(sql, Long::class.java)!!

    @Test
    fun `seeds the requested sizes, keeps the catalogue rules, and adds nothing when run again`() {
        val run = seed(movies = 200, people = 100, credits = 1000)
        assertEquals(0, run.exitCode, run.stderr)

        assertEquals(200, count("select count(*) from movie"))
        assertEquals(100, count("select count(*) from person"))
        assertEquals(700, count("select count(*) from cast_credit"))
        assertEquals(300, count("select count(*) from creator_credit"))
        assertEquals(0, count("select count(*) from movie m where not exists (select 1 from movie_genre g where g.movie_id = m.id)"))
        assertEquals(0, count("select count(*) from cast_credit c join person p on p.id = c.person_id where not p.is_cast"))
        assertEquals(0, count("select count(*) from creator_credit c join person p on p.id = c.person_id where not p.is_creator"))
        assertEquals(0, count("select count(*) from person where date_of_birth > current_date"))
        assertEquals(200, count("select count(*) from creator_credit where role = 'DIRECTOR'"), "one director per movie")
        assertTrue(count("select count(*) from movie where release_date is null") > 0, "some unknown dates")
        assertTrue(count("select count(distinct genre_code) from movie_genre") > 10, "genres are spread")

        val again = seed(movies = 200, people = 100, credits = 1000)
        assertEquals(0, again.exitCode, again.stderr)
        assertEquals(200, count("select count(*) from movie"))
        assertEquals(1000, count("select count(*) from cast_credit") + count("select count(*) from creator_credit"))
    }

    @Test
    fun `refuses sizes that cannot produce a valid catalogue`() {
        val run = seed(movies = 0, people = 100, credits = 10)

        assertNotEquals(0, run.exitCode)
        assertTrue(run.stderr.contains("Sizes must be"), run.stderr)
    }
}
