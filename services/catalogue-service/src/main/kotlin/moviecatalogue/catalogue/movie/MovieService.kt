package moviecatalogue.catalogue.movie

import moviecatalogue.catalogue.artwork.ArtworkReleased
import moviecatalogue.catalogue.artwork.MoviePosterRepository
import moviecatalogue.catalogue.common.NotFoundException
import moviecatalogue.catalogue.common.SaveResult
import moviecatalogue.catalogue.common.Text
import moviecatalogue.catalogue.common.Validator
import moviecatalogue.catalogue.common.staleVersion
import moviecatalogue.catalogue.genre.Genre
import moviecatalogue.catalogue.genre.GenreRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class MovieInput(
    val title: String,
    val synopsis: String? = null,
    val releaseDate: LocalDate? = null,
    val runtimeMinutes: Int? = null,
    val genreCodes: List<String>,
)

data class GenreView(
    val code: String,
    val name: String,
)

data class MovieView(
    val id: UUID,
    val title: String,
    val synopsis: String?,
    val releaseDate: LocalDate?,
    val runtimeMinutes: Int?,
    val genres: List<GenreView>,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val releaseYear: Int? get() = releaseDate?.year
}

/** Minimal movie summary used inside credits and duplicate warnings. */
data class MovieRef(
    val id: UUID,
    val title: String,
    val releaseDate: LocalDate?,
) {
    val releaseYear: Int? get() = releaseDate?.year
}

fun Movie.toRef() = MovieRef(id, title, releaseDate)

fun Movie.toView() =
    MovieView(
        id = id,
        title = title,
        synopsis = synopsis,
        releaseDate = releaseDate,
        runtimeMinutes = runtimeMinutes,
        genres = genres.map { GenreView(it.code, it.name) }.sortedBy { it.name },
        version = checkNotNull(version),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

typealias MovieSaveResult = SaveResult<MovieView, MovieRef>

@Service
@Transactional
class MovieService(
    private val movies: MovieRepository,
    private val genres: GenreRepository,
    private val posters: MoviePosterRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    /**
     * Validates, then (unless [confirmDuplicate]) returns possible duplicates without writing anything.
     * The confirmation bypasses only the duplicate warning; every other rule still applies.
     */
    fun create(
        input: MovieInput,
        confirmDuplicate: Boolean = false,
        id: UUID = UUID.randomUUID(),
    ): MovieSaveResult {
        val valid = validate(input)
        if (!confirmDuplicate) {
            val candidates = possibleDuplicates(valid.title, valid.releaseDate, excludeId = null)
            if (candidates.isNotEmpty()) return SaveResult.DuplicateWarning(candidates)
        }
        val movie = Movie(id, valid.title, valid.synopsis, valid.releaseDate, valid.runtimeMinutes, valid.genres, clock.instant())
        return SaveResult.Saved(movies.saveAndFlush(movie).toView())
    }

    /** [expectedVersion] is the version the caller last read; a mismatch means someone else saved first. */
    fun update(
        id: UUID,
        expectedVersion: Long,
        input: MovieInput,
        confirmDuplicate: Boolean = false,
    ): MovieSaveResult {
        val movie = movies.findById(id).orElseThrow { NotFoundException("Movie", id) }
        if (movie.version != expectedVersion) throw staleVersion("Movie", id)
        val valid = validate(input)
        if (!confirmDuplicate) {
            val candidates = possibleDuplicates(valid.title, valid.releaseDate, excludeId = id)
            if (candidates.isNotEmpty()) return SaveResult.DuplicateWarning(candidates)
        }
        movie.update(valid.title, valid.synopsis, valid.releaseDate, valid.runtimeMinutes, valid.genres, clock.instant())
        try {
            return SaveResult.Saved(movies.saveAndFlush(movie).toView())
        } catch (_: ObjectOptimisticLockingFailureException) {
            throw staleVersion("Movie", id) // a concurrent save committed between our read and write
        }
    }

    /**
     * Deletes the movie. The database cascades its genre links, cast and creator credits and poster
     * reference; the people and genres themselves stay. The poster image is deleted from
     * media-service after the commit (ArtworkReleased). Hibernate deletes with `where version = ?`,
     * so a change committed after our check (an edit, a new credit or poster, all bump the version)
     * makes this stale.
     */
    fun delete(
        id: UUID,
        expectedVersion: Long,
    ) {
        val movie = movies.findById(id).orElseThrow { NotFoundException("Movie", id) }
        if (movie.version != expectedVersion) throw staleVersion("Movie", id)
        posters.findById(id).ifPresent { events.publishEvent(ArtworkReleased(it.artworkId)) }
        try {
            movies.delete(movie)
            movies.flush()
        } catch (_: ObjectOptimisticLockingFailureException) {
            throw staleVersion("Movie", id)
        }
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): MovieView = movies.findById(id).orElseThrow { NotFoundException("Movie", id) }.toView()

    @Transactional(readOnly = true)
    fun find(id: UUID): MovieView? = movies.findById(id).map { it.toView() }.orElse(null)

    /**
     * Advisory only: same normalised title, and release dates equal or unknown on either side.
     * It cannot see a matching movie committed concurrently; duplicates are allowed anyway (D07).
     */
    private fun possibleDuplicates(
        title: String,
        releaseDate: LocalDate?,
        excludeId: UUID?,
    ): List<MovieRef> = movies.findPossibleDuplicates(Text.matchKey(title), releaseDate, excludeId).map { it.toRef() }

    private class ValidMovie(
        val title: String,
        val synopsis: String?,
        val releaseDate: LocalDate?,
        val runtimeMinutes: Int?,
        val genres: Set<Genre>,
    )

    private fun validate(input: MovieInput): ValidMovie {
        val v = Validator()
        val title = v.requiredText("title", input.title, MAX_TITLE)
        val synopsis = v.optionalText("synopsis", input.synopsis, MAX_SYNOPSIS)
        input.runtimeMinutes?.let { v.check(it in 1..MAX_RUNTIME, "runtimeMinutes", "must be between 1 and $MAX_RUNTIME") }
        // Future release dates are allowed (announced films); unknown dates are null.

        val codes = input.genreCodes
        v.check(codes.isNotEmpty(), "genreCodes", "choose at least one genre")
        v.check(codes.size == codes.toSet().size, "genreCodes", "must not contain the same genre twice")
        val found = genres.findAllById(codes.toSet())
        val unknown = codes.toSet() - found.map { it.code }.toSet()
        v.check(unknown.isEmpty(), "genreCodes", "unknown genre: ${unknown.sorted().joinToString()}")

        v.throwIfInvalid()
        return ValidMovie(title, synopsis, input.releaseDate, input.runtimeMinutes, found.toSet())
    }

    companion object {
        const val MAX_TITLE = 200
        const val MAX_SYNOPSIS = 5000
        const val MAX_RUNTIME = 1440
    }
}
