package moviecatalogue.catalogue.person

import moviecatalogue.catalogue.CatalogueIntegrationTest
import moviecatalogue.catalogue.common.ConflictException
import moviecatalogue.catalogue.common.ConflictReason
import moviecatalogue.catalogue.common.ValidationException
import moviecatalogue.catalogue.createConfirmed
import moviecatalogue.catalogue.credit.CreatorRole
import moviecatalogue.catalogue.credit.CreditService
import moviecatalogue.catalogue.movie.MovieInput
import moviecatalogue.catalogue.movie.MovieService
import moviecatalogue.catalogue.person.PersonCategory.CAST
import moviecatalogue.catalogue.person.PersonCategory.CREATOR
import moviecatalogue.catalogue.updateConfirmed
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PersonServiceTest : CatalogueIntegrationTest() {
    @Autowired
    private lateinit var people: PersonService

    @Autowired
    private lateinit var movies: MovieService

    @Autowired
    private lateinit var credits: CreditService

    private fun input(
        name: String = "Test Person",
        dateOfBirth: LocalDate? = null,
        biography: String? = null,
        categories: Set<PersonCategory> = setOf(CAST),
    ) = PersonInput(name, dateOfBirth, biography, categories)

    @Test
    fun `one record can be in both categories, with optional birth date and biography`() {
        val person = people.createConfirmed(input(name = " Ada Brightwater ", categories = setOf(CAST, CREATOR)))

        assertEquals("Ada Brightwater", person.name)
        assertEquals(setOf(CAST, CREATOR), person.categories)
        assertNull(person.dateOfBirth)
        assertNull(person.biography)
    }

    @Test
    fun `name is required and at least one category is required`() {
        val ex = assertThrows<ValidationException> { people.createConfirmed(input(name = "  ", categories = emptySet())) }

        assertEquals(listOf("name", "categories"), ex.errors.map { it.field })
    }

    @Test
    fun `the database also refuses a person without any category`() {
        val error =
            runCatching {
                jdbc.update(
                    "insert into person (id, name, is_cast, is_creator, version, created_at, updated_at) values (gen_random_uuid(), 'X', false, false, 0, now(), now())",
                )
            }.exceptionOrNull()

        assertEquals(true, error?.message?.contains("person_has_category"))
    }

    @Test
    fun `birth date may be today but not in the future`() {
        // Clock: 2026-10-25T00:00Z. In UTC+14 that is already 2026-10-25 14:00.
        people.createConfirmed(input(dateOfBirth = LocalDate.of(2026, 10, 25)))

        val ex = assertThrows<ValidationException> { people.createConfirmed(input(dateOfBirth = LocalDate.of(2026, 10, 26))) }
        assertEquals("dateOfBirth", ex.errors.single().field)
    }

    @Test
    fun `a date that is already today somewhere in the world is accepted although the server clock is UTC`() {
        clock.now = Instant.parse("2026-10-25T12:00:00Z") // 2026-10-26 02:00 in UTC+14

        people.createConfirmed(input(dateOfBirth = LocalDate.of(2026, 10, 26)))
    }

    @Test
    fun `stale edits are rejected`() {
        val person = people.createConfirmed(input())
        people.updateConfirmed(person.id, 0, input(name = "First"))

        val ex = assertThrows<ConflictException> { people.updateConfirmed(person.id, 0, input(name = "Second")) }
        assertEquals(ConflictReason.STALE_VERSION, ex.reason)
    }

    @Test
    fun `a category cannot be removed while credits depend on it, but an unused one can`() {
        val person = people.createConfirmed(input(categories = setOf(CAST, CREATOR)))
        val movie = movies.createConfirmed(MovieInput(title = "Credit Holder", genreCodes = listOf("DRAMA")))
        credits.addCast(movie.id, person.id, "Lead")
        val version = people.get(person.id).version

        val ex = assertThrows<ConflictException> { people.updateConfirmed(person.id, version, input(categories = setOf(CREATOR))) }
        assertEquals(ConflictReason.CATEGORY_IN_USE, ex.reason)

        val updated = people.updateConfirmed(person.id, version, input(categories = setOf(CAST)))
        assertEquals(setOf(CAST), updated.categories)
    }

    @Test
    fun `an edit form opened before a credit was linked cannot remove the category as stale data`() {
        val person = people.createConfirmed(input(categories = setOf(CAST, CREATOR)))
        val versionSeenByEditor = person.version
        val movie = movies.createConfirmed(MovieInput(title = "Linked Meanwhile", genreCodes = listOf("DRAMA")))
        credits.addCast(movie.id, person.id, null) // someone else links a credit

        val ex =
            assertThrows<ConflictException> { people.updateConfirmed(person.id, versionSeenByEditor, input(categories = setOf(CREATOR))) }
        assertEquals(ConflictReason.STALE_VERSION, ex.reason)
    }

    @Test
    fun `removing the creator category is blocked by a role-less creator credit too`() {
        val person = people.createConfirmed(input(categories = setOf(CAST, CREATOR)))
        val movie = movies.createConfirmed(MovieInput(title = "Uncredited Role", genreCodes = listOf("DRAMA")))
        credits.addCreator(movie.id, person.id, role = null)

        val ex =
            assertThrows<ConflictException> {
                people.updateConfirmed(person.id, people.get(person.id).version, input(categories = setOf(CAST)))
            }
        assertEquals(ConflictReason.CATEGORY_IN_USE, ex.reason)
        credits.addCreator(movie.id, person.id, CreatorRole.WRITER) // the person keeps working normally
    }
}
