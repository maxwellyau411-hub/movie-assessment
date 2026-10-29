package moviecatalogue.catalogue.movie

import moviecatalogue.catalogue.CatalogueIntegrationTest
import moviecatalogue.catalogue.common.ConflictException
import moviecatalogue.catalogue.common.ConflictReason
import moviecatalogue.catalogue.common.NotFoundException
import moviecatalogue.catalogue.common.ValidationException
import moviecatalogue.catalogue.createConfirmed
import moviecatalogue.catalogue.updateConfirmed
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MovieServiceTest : CatalogueIntegrationTest() {
    @Autowired
    private lateinit var movies: MovieService

    @Autowired
    private lateinit var repository: MovieRepository

    @Autowired
    private lateinit var transactions: TransactionTemplate

    private fun input(
        title: String = "A Test Film",
        synopsis: String? = null,
        releaseDate: LocalDate? = null,
        runtimeMinutes: Int? = null,
        genres: List<String> = listOf("DRAMA"),
    ) = MovieInput(title, synopsis, releaseDate, runtimeMinutes, genres)

    private fun fieldsOf(ex: ValidationException) = ex.errors.map { it.field }

    @Test
    fun `creates a movie with trimmed text, sorted genres, version 0 and UTC timestamps`() {
        val movie =
            movies.createConfirmed(
                input(title = "  Night Ferry  ", synopsis = "   ", runtimeMinutes = 95, genres = listOf("THRILLER", "DRAMA")),
            )

        assertEquals("Night Ferry", movie.title)
        assertNull(movie.synopsis, "blank synopsis is stored as unknown (null)")
        assertEquals(listOf("Drama", "Thriller"), movie.genres.map { it.name })
        assertEquals(0, movie.version)
        assertEquals(FIXED_NOW, movie.createdAt)
        assertEquals(movie, movies.get(movie.id))
    }

    @Test
    fun `release date may be unknown or in the future`() {
        assertNull(movies.createConfirmed(input(releaseDate = null)).releaseDate)
        assertEquals(LocalDate.of(2031, 1, 1), movies.createConfirmed(input(releaseDate = LocalDate.of(2031, 1, 1))).releaseDate)
    }

    @Test
    fun `reports every invalid field at once and persists nothing`() {
        val before = repository.count()
        val ex =
            assertThrows<ValidationException> { movies.createConfirmed(input(title = "   ", runtimeMinutes = 0, genres = emptyList())) }

        assertEquals(listOf("title", "runtimeMinutes", "genreCodes"), fieldsOf(ex))
        assertEquals(before, repository.count())
    }

    @Test
    fun `title limit is 200 characters counted as PostgreSQL counts them`() {
        val emoji = "🎬" // one code point, two UTF-16 chars
        assertEquals(200, movies.createConfirmed(input(title = emoji.repeat(200))).title.codePointCount(0, 400))

        val ex = assertThrows<ValidationException> { movies.createConfirmed(input(title = "x".repeat(201))) }
        assertEquals(listOf("title"), fieldsOf(ex))
    }

    @Test
    fun `synopsis and runtime bounds`() {
        movies.createConfirmed(input(synopsis = "s".repeat(5000), runtimeMinutes = 1))
        movies.createConfirmed(input(runtimeMinutes = 1440))

        assertEquals(
            listOf("synopsis"),
            fieldsOf(assertThrows<ValidationException> { movies.createConfirmed(input(synopsis = "s".repeat(5001))) }),
        )
        assertEquals(
            listOf("runtimeMinutes"),
            fieldsOf(assertThrows<ValidationException> { movies.createConfirmed(input(runtimeMinutes = 1441)) }),
        )
        assertEquals(
            listOf("runtimeMinutes"),
            fieldsOf(assertThrows<ValidationException> { movies.createConfirmed(input(runtimeMinutes = -5)) }),
        )
    }

    @Test
    fun `genres must exist and must not repeat`() {
        val unknown = assertThrows<ValidationException> { movies.createConfirmed(input(genres = listOf("DRAMA", "NOT_A_GENRE"))) }
        val repeated = assertThrows<ValidationException> { movies.createConfirmed(input(genres = listOf("DRAMA", "DRAMA"))) }

        assertEquals("unknown genre: NOT_A_GENRE", unknown.errors.single().message)
        assertEquals("must not contain the same genre twice", repeated.errors.single().message)
    }

    @Test
    fun `update replaces fields and genres and increments the version`() {
        val created = movies.createConfirmed(input(genres = listOf("DRAMA", "WAR")))
        clock.now = clock.now.plus(Duration.ofMinutes(5))

        val updated =
            movies.updateConfirmed(
                created.id,
                0,
                input(title = "Renamed", releaseDate = LocalDate.of(2020, 5, 1), genres = listOf("HISTORY")),
            )

        assertEquals("Renamed", updated.title)
        assertEquals(listOf("HISTORY"), updated.genres.map { it.code })
        assertEquals(1, updated.version)
        assertEquals(created.createdAt, updated.createdAt)
        assertEquals(FIXED_NOW.plus(Duration.ofMinutes(5)), updated.updatedAt)
        assertEquals(1, count("select count(*) from movie_genre where movie_id = ?", created.id))
    }

    @Test
    fun `an edit based on an old version is rejected instead of overwriting`() {
        val created = movies.createConfirmed(input())
        movies.updateConfirmed(created.id, 0, input(title = "First editor"))

        val ex = assertThrows<ConflictException> { movies.updateConfirmed(created.id, 0, input(title = "Second editor")) }

        assertEquals(ConflictReason.STALE_VERSION, ex.reason)
        assertEquals("First editor", movies.get(created.id).title)
    }

    @Test
    fun `the version column also stops a concurrent write that passed the expected-version check`() {
        val created = movies.createConfirmed(input())

        assertThrows<ObjectOptimisticLockingFailureException> {
            transactions.executeWithoutResult {
                val movie = repository.findById(created.id).orElseThrow()
                // Another transaction commits in between (separate connection, auto-commit).
                jdbc.update("update movie set version = version + 1 where id = ?", created.id)
                movie.update("Late writer", null, null, null, movie.genres, clock.instant())
                repository.flush()
            }
        }
    }

    @Test
    fun `missing movies are reported as not found`() {
        assertThrows<NotFoundException> { movies.get(UUID.randomUUID()) }
        assertThrows<NotFoundException> { movies.updateConfirmed(UUID.randomUUID(), 0, input()) }
    }
}
