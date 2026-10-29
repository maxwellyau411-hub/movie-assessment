package moviecatalogue.catalogue.person

import moviecatalogue.catalogue.common.ConflictException
import moviecatalogue.catalogue.common.ConflictReason
import moviecatalogue.catalogue.common.NotFoundException
import moviecatalogue.catalogue.common.SaveResult
import moviecatalogue.catalogue.common.Text
import moviecatalogue.catalogue.common.Validator
import moviecatalogue.catalogue.common.staleVersion
import moviecatalogue.catalogue.credit.CastCreditRepository
import moviecatalogue.catalogue.credit.CreatorCreditRepository
import moviecatalogue.catalogue.movie.MovieRef
import moviecatalogue.catalogue.movie.MovieRepository
import moviecatalogue.catalogue.movie.toRef
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

data class PersonInput(
    val name: String,
    val dateOfBirth: LocalDate? = null,
    val biography: String? = null,
    val categories: Set<PersonCategory>,
)

data class PersonView(
    val id: UUID,
    val name: String,
    val dateOfBirth: LocalDate?,
    val biography: String?,
    val categories: Set<PersonCategory>,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Minimal person summary used inside credits and duplicate warnings. */
data class PersonRef(
    val id: UUID,
    val name: String,
    val dateOfBirth: LocalDate? = null,
    val categories: Set<PersonCategory> = emptySet(),
)

fun Person.toView() = PersonView(id, name, dateOfBirth, biography, categories, checkNotNull(version), createdAt, updatedAt)

fun Person.toRef() = PersonRef(id, name, dateOfBirth, categories)

typealias PersonSaveResult = SaveResult<PersonView, PersonRef>

@Service
@Transactional
class PersonService(
    private val people: PersonRepository,
    private val castCredits: CastCreditRepository,
    private val creatorCredits: CreatorCreditRepository,
    private val movies: MovieRepository,
    private val clock: Clock,
) {
    /** Validates, then (unless [confirmDuplicate]) returns possible duplicates without writing anything. */
    fun create(
        input: PersonInput,
        confirmDuplicate: Boolean = false,
        id: UUID = UUID.randomUUID(),
    ): PersonSaveResult {
        val valid = validate(input)
        if (!confirmDuplicate) {
            val candidates = possibleDuplicates(valid.name, valid.dateOfBirth, excludeId = null)
            if (candidates.isNotEmpty()) return SaveResult.DuplicateWarning(candidates)
        }
        val person = Person(id, valid.name, valid.dateOfBirth, valid.biography, valid.categories, clock.instant())
        return SaveResult.Saved(people.saveAndFlush(person).toView())
    }

    fun update(
        id: UUID,
        expectedVersion: Long,
        input: PersonInput,
        confirmDuplicate: Boolean = false,
    ): PersonSaveResult {
        val person = people.findById(id).orElseThrow { NotFoundException("Person", id) }
        if (person.version != expectedVersion) throw staleVersion("Person", id)
        val valid = validate(input)
        if (!confirmDuplicate) {
            val candidates = possibleDuplicates(valid.name, valid.dateOfBirth, excludeId = id)
            if (candidates.isNotEmpty()) return SaveResult.DuplicateWarning(candidates)
        }
        // Removing a category is blocked while credits rely on it. Adding a credit bumps this person's
        // version, so a credit linked concurrently makes this update fail as stale instead of slipping past.
        if (PersonCategory.CAST !in valid.categories && castCredits.existsByPersonId(id)) {
            throw ConflictException(ConflictReason.CATEGORY_IN_USE, "Remove this person's cast credits before removing the Cast category")
        }
        if (PersonCategory.CREATOR !in valid.categories && creatorCredits.existsByPersonId(id)) {
            throw ConflictException(
                ConflictReason.CATEGORY_IN_USE,
                "Remove this person's creator credits before removing the Creator category",
            )
        }
        person.update(valid.name, valid.dateOfBirth, valid.biography, valid.categories, clock.instant())
        try {
            return SaveResult.Saved(people.saveAndFlush(person).toView())
        } catch (_: ObjectOptimisticLockingFailureException) {
            throw staleVersion("Person", id)
        }
    }

    /**
     * Deletes a person who has no credits. Credits are never removed silently: the conflict lists
     * the movies (at most [MAX_LISTED_MOVIES], plus the total) so the user can unlink them first.
     *
     * A credit added concurrently cannot slip through: adding a credit bumps this person's version,
     * so the versioned delete becomes stale, and the RESTRICT foreign key is the final guard.
     */
    fun delete(
        id: UUID,
        expectedVersion: Long,
    ) {
        val person = people.findById(id).orElseThrow { NotFoundException("Person", id) }
        if (person.version != expectedVersion) throw staleVersion("Person", id)
        val credited = movies.findCreditedMovies(id, PageRequest.of(0, MAX_LISTED_MOVIES))
        if (credited.totalElements > 0) throw hasCredits(person.name, credited.content.map { it.toRef() }, credited.totalElements)
        try {
            people.delete(person)
            people.flush()
        } catch (_: ObjectOptimisticLockingFailureException) {
            throw staleVersion("Person", id)
        } catch (_: DataIntegrityViolationException) {
            throw staleVersion("Person", id) // a credit was committed between our check and the delete
        }
    }

    private fun hasCredits(
        name: String,
        movies: List<MovieRef>,
        total: Long,
    ) = ConflictException(
        ConflictReason.PERSON_HAS_CREDITS,
        "$name is credited in $total ${if (total == 1L) "movie" else "movies"}. Remove those credits before deleting.",
        mapOf(
            "movieCount" to total,
            "movies" to movies.map { mapOf("id" to it.id.toString(), "title" to it.title, "releaseYear" to it.releaseYear) },
        ),
    )

    @Transactional(readOnly = true)
    fun get(id: UUID): PersonView = people.findById(id).orElseThrow { NotFoundException("Person", id) }.toView()

    @Transactional(readOnly = true)
    fun find(id: UUID): PersonView? = people.findById(id).map { it.toView() }.orElse(null)

    /**
     * Advisory only, across both categories: same normalised name, unless both birth dates are
     * known and differ (two different people who happen to share a name).
     */
    private fun possibleDuplicates(
        name: String,
        dateOfBirth: LocalDate?,
        excludeId: UUID?,
    ): List<PersonRef> = people.findPossibleDuplicates(Text.matchKey(name), dateOfBirth, excludeId).map { it.toRef() }

    private class ValidPerson(
        val name: String,
        val dateOfBirth: LocalDate?,
        val biography: String?,
        val categories: Set<PersonCategory>,
    )

    private fun validate(input: PersonInput): ValidPerson {
        val v = Validator()
        val name = v.requiredText("name", input.name, MAX_NAME)
        val biography = v.optionalText("biography", input.biography, MAX_BIOGRAPHY)
        input.dateOfBirth?.let { v.check(!it.isAfter(latestToday()), "dateOfBirth", "cannot be in the future") }
        v.check(input.categories.isNotEmpty(), "categories", "choose Cast, Creator, or both")
        v.throwIfInvalid()
        return ValidPerson(name, input.dateOfBirth, biography, input.categories)
    }

    /**
     * "Today" in the furthest-ahead time zone (UTC+14). A date that is already today for the
     * person entering it is never rejected as future just because the server runs on UTC.
     */
    private fun latestToday(): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.ofHours(14))

    companion object {
        const val MAX_NAME = 200
        const val MAX_BIOGRAPHY = 5000
        const val MAX_LISTED_MOVIES = 10
    }
}
