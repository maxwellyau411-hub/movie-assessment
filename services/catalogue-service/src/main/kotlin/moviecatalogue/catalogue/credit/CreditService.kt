package moviecatalogue.catalogue.credit

import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.persistence.OptimisticLockException
import moviecatalogue.catalogue.common.ConflictException
import moviecatalogue.catalogue.common.ConflictReason
import moviecatalogue.catalogue.common.NotFoundException
import moviecatalogue.catalogue.common.Validator
import moviecatalogue.catalogue.common.staleVersion
import moviecatalogue.catalogue.movie.Movie
import moviecatalogue.catalogue.movie.MovieRef
import moviecatalogue.catalogue.movie.MovieRepository
import moviecatalogue.catalogue.movie.toRef
import moviecatalogue.catalogue.person.Person
import moviecatalogue.catalogue.person.PersonCategory
import moviecatalogue.catalogue.person.PersonRef
import moviecatalogue.catalogue.person.PersonRepository
import moviecatalogue.catalogue.person.toRef
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** A credit names both sides with small references, so it reads the same from a movie or a person. */
data class CastCreditView(
    val id: UUID,
    val movie: MovieRef,
    val person: PersonRef,
    val characterName: String?,
)

data class CreatorCreditView(
    val id: UUID,
    val movie: MovieRef,
    val person: PersonRef,
    val role: CreatorRole?,
)

data class MovieCredits(
    val cast: List<CastCreditView>,
    val creators: List<CreatorCreditView>,
)

fun CastCredit.toView() = CastCreditView(id, movie.toRef(), person.toRef(), characterName)

fun CreatorCredit.toView() = CreatorCreditView(id, movie.toRef(), person.toRef(), role)

/**
 * Link/unlink use cases. Every change touches both the movie and the person, so both versions
 * increment: open edit forms and pending deletion requests for either side become stale.
 */
@Service
@Transactional
class CreditService(
    private val movies: MovieRepository,
    private val people: PersonRepository,
    private val castCredits: CastCreditRepository,
    private val creatorCredits: CreatorCreditRepository,
    private val entityManager: EntityManager,
    private val clock: Clock,
) {
    fun addCast(
        movieId: UUID,
        personId: UUID,
        characterName: String?,
    ): CastCreditView {
        val v = Validator()
        val character = v.optionalText("characterName", characterName, MAX_CHARACTER_NAME)
        v.throwIfInvalid()
        val (movie, person) = loadPair(movieId, personId)
        if (!person.isCast) {
            throw ConflictException(
                ConflictReason.CATEGORY_REQUIRED,
                "Add the Cast category to ${person.name} before crediting them in the cast",
            )
        }
        if (castCredits.existsByMovieIdAndPersonId(movieId, personId)) {
            throw ConflictException(ConflictReason.DUPLICATE_ASSOCIATION, "${person.name} is already in this movie's cast")
        }
        val credit = CastCredit(UUID.randomUUID(), movie, person, character)
        return persist(movie, person) { castCredits.saveAndFlush(credit) }.toView()
    }

    fun removeCast(creditId: UUID) {
        val credit = castCredits.findById(creditId).orElseThrow { NotFoundException("Cast credit", creditId) }
        persist(credit.movie, credit.person) { castCredits.delete(credit) }
    }

    fun addCreator(
        movieId: UUID,
        personId: UUID,
        role: CreatorRole?,
    ): CreatorCreditView {
        val (movie, person) = loadPair(movieId, personId)
        if (!person.isCreator) {
            throw ConflictException(
                ConflictReason.CATEGORY_REQUIRED,
                "Add the Creator category to ${person.name} before crediting them as a creator",
            )
        }
        if (creatorCredits.existsByMovieIdAndPersonIdAndRole(movieId, personId, role)) {
            throw ConflictException(ConflictReason.DUPLICATE_ASSOCIATION, "${person.name} already has this creator credit")
        }
        val credit = CreatorCredit(UUID.randomUUID(), movie, person, role)
        return persist(movie, person) { creatorCredits.saveAndFlush(credit) }.toView()
    }

    fun removeCreator(creditId: UUID) {
        val credit = creatorCredits.findById(creditId).orElseThrow { NotFoundException("Creator credit", creditId) }
        persist(credit.movie, credit.person) { creatorCredits.delete(credit) }
    }

    @Transactional(readOnly = true)
    fun creditsForMovie(movieId: UUID): MovieCredits {
        if (!movies.existsById(movieId)) throw NotFoundException("Movie", movieId)
        return MovieCredits(
            cast = castCredits.findForMovie(movieId).map { it.toView() },
            creators = creatorCredits.findForMovie(movieId).map { it.toView() },
        )
    }

    private fun loadPair(
        movieId: UUID,
        personId: UUID,
    ): Pair<Movie, Person> =
        movies.findById(movieId).orElseThrow { NotFoundException("Movie", movieId) } to
            people.findById(personId).orElseThrow { NotFoundException("Person", personId) }

    /**
     * Increments both versions explicitly (a credit row is not a field of either entity, so dirty
     * checking would not). PESSIMISTIC_FORCE_INCREMENT updates the version at once, with a version
     * check, and holds the row locks until commit, serialising concurrent credit changes on the same
     * movie or person. Lock order is always movie, then person, to avoid deadlocks. The unique
     * constraints remain the backstop for duplicate links.
     */
    private fun <T> persist(
        movie: Movie,
        person: Person,
        change: () -> T,
    ): T {
        try {
            entityManager.lock(movie, LockModeType.PESSIMISTIC_FORCE_INCREMENT)
            entityManager.lock(person, LockModeType.PESSIMISTIC_FORCE_INCREMENT)
            val now = clock.instant()
            movie.touch(now)
            person.touch(now)
            val result = change()
            movies.flush()
            return result
        } catch (_: DataIntegrityViolationException) {
            throw ConflictException(ConflictReason.DUPLICATE_ASSOCIATION, "This credit already exists")
        } catch (_: ObjectOptimisticLockingFailureException) {
            throw staleVersion("Movie or person", movie.id)
        } catch (_: OptimisticLockException) {
            throw staleVersion("Movie or person", movie.id) // raised untranslated by EntityManager.lock
        }
    }

    companion object {
        const val MAX_CHARACTER_NAME = 200
    }
}
