package moviecatalogue.catalogue.credit

import moviecatalogue.catalogue.CatalogueIntegrationTest
import moviecatalogue.catalogue.common.ConflictException
import moviecatalogue.catalogue.common.ConflictReason
import moviecatalogue.catalogue.common.NotFoundException
import moviecatalogue.catalogue.common.ValidationException
import moviecatalogue.catalogue.createConfirmed
import moviecatalogue.catalogue.movie.MovieInput
import moviecatalogue.catalogue.movie.MovieService
import moviecatalogue.catalogue.movie.MovieView
import moviecatalogue.catalogue.person.PersonCategory
import moviecatalogue.catalogue.person.PersonCategory.CAST
import moviecatalogue.catalogue.person.PersonCategory.CREATOR
import moviecatalogue.catalogue.person.PersonInput
import moviecatalogue.catalogue.person.PersonService
import moviecatalogue.catalogue.person.PersonView
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CreditServiceTest : CatalogueIntegrationTest() {
    @Autowired
    private lateinit var credits: CreditService

    @Autowired
    private lateinit var movies: MovieService

    @Autowired
    private lateinit var people: PersonService

    private fun movie(title: String = "Credit Test"): MovieView =
        movies.createConfirmed(MovieInput(title = title, genreCodes = listOf("DRAMA")))

    private fun person(vararg categories: PersonCategory): PersonView =
        people.createConfirmed(PersonInput(name = "Person ${UUID.randomUUID()}", categories = categories.toSet()))

    private fun conflict(block: () -> Unit) = assertThrows<ConflictException> { block() }.reason

    @Test
    fun `a shared person acts in and directs the same movie with one person record`() {
        val movie = movie()
        val ilse = person(CAST, CREATOR)

        credits.addCast(movie.id, ilse.id, "Agnes")
        credits.addCreator(movie.id, ilse.id, CreatorRole.DIRECTOR)
        val listed = credits.creditsForMovie(movie.id)

        assertEquals(listOf(ilse.id), listed.cast.map { it.person.id })
        assertEquals(listOf(ilse.id to CreatorRole.DIRECTOR), listed.creators.map { it.person.id to it.role })
        assertEquals(1, count("select count(*) from person where id = ?", ilse.id))
    }

    @Test
    fun `a person appears in a movie's cast only once`() {
        val movie = movie()
        val actor = person(CAST)
        credits.addCast(movie.id, actor.id, "Twin A")

        assertEquals(ConflictReason.DUPLICATE_ASSOCIATION, conflict { credits.addCast(movie.id, actor.id, "Twin B") })
    }

    @Test
    fun `creator credits allow distinct roles but not an exact repeat, including the role-less case`() {
        val movie = movie()
        val creator = person(CREATOR)

        credits.addCreator(movie.id, creator.id, CreatorRole.WRITER)
        credits.addCreator(movie.id, creator.id, CreatorRole.PRODUCER)
        credits.addCreator(movie.id, creator.id, null)

        assertEquals(ConflictReason.DUPLICATE_ASSOCIATION, conflict { credits.addCreator(movie.id, creator.id, CreatorRole.WRITER) })
        assertEquals(ConflictReason.DUPLICATE_ASSOCIATION, conflict { credits.addCreator(movie.id, creator.id, null) })
        assertEquals(listOf(CreatorRole.PRODUCER, CreatorRole.WRITER, null), credits.creditsForMovie(movie.id).creators.map { it.role })
    }

    @Test
    fun `database unique constraints back up the service checks, including NULLS NOT DISTINCT`() {
        val movie = movie()
        val creator = person(CREATOR)
        val actor = person(CAST)
        credits.addCreator(movie.id, creator.id, null)
        credits.addCast(movie.id, actor.id, null)

        assertThrows<DataIntegrityViolationException> {
            jdbc.update(
                "insert into creator_credit (id, movie_id, person_id, role) values (gen_random_uuid(), ?, ?, null)",
                movie.id,
                creator.id,
            )
        }
        assertThrows<DataIntegrityViolationException> {
            jdbc.update("insert into cast_credit (id, movie_id, person_id) values (gen_random_uuid(), ?, ?)", movie.id, actor.id)
        }
    }

    @Test
    fun `a credit needs the matching person category and nothing is cloned silently`() {
        val movie = movie()
        val actorOnly = person(CAST)
        val creatorOnly = person(CREATOR)
        val peopleBefore = count("select count(*) from person")

        assertEquals(ConflictReason.CATEGORY_REQUIRED, conflict { credits.addCreator(movie.id, actorOnly.id, CreatorRole.DIRECTOR) })
        assertEquals(ConflictReason.CATEGORY_REQUIRED, conflict { credits.addCast(movie.id, creatorOnly.id, null) })
        assertEquals(peopleBefore, count("select count(*) from person"))
    }

    @Test
    fun `linking and unlinking bump both the movie and the person version`() {
        val movie = movie()
        val actor = person(CAST)

        val credit = credits.addCast(movie.id, actor.id, null)
        assertEquals(1, movies.get(movie.id).version)
        assertEquals(1, people.get(actor.id).version)

        credits.removeCast(credit.id)
        assertEquals(2, movies.get(movie.id).version)
        assertEquals(2, people.get(actor.id).version)
    }

    @Test
    fun `unlinking removes only the relationship`() {
        val movie = movie()
        val creator = person(CREATOR)
        val credit = credits.addCreator(movie.id, creator.id, CreatorRole.COMPOSER)

        credits.removeCreator(credit.id)

        assertEquals(0, credits.creditsForMovie(movie.id).creators.size)
        movies.get(movie.id)
        people.get(creator.id)
    }

    @Test
    fun `character name is optional, trimmed and bounded`() {
        val movie = movie()

        assertNull(credits.addCast(movie.id, person(CAST).id, "   ").characterName)
        assertEquals("Agnes Holm", credits.addCast(movie.id, person(CAST).id, " Agnes Holm ").characterName)
        assertThrows<ValidationException> { credits.addCast(movie.id, person(CAST).id, "c".repeat(201)) }
    }

    @Test
    fun `missing movie, person or credit is reported as not found`() {
        val movie = movie()
        val actor = person(CAST)

        assertThrows<NotFoundException> { credits.addCast(UUID.randomUUID(), actor.id, null) }
        assertThrows<NotFoundException> { credits.addCast(movie.id, UUID.randomUUID(), null) }
        assertThrows<NotFoundException> { credits.removeCast(UUID.randomUUID()) }
        assertThrows<NotFoundException> { credits.creditsForMovie(UUID.randomUUID()) }
    }

    @Test
    fun `a credited person cannot be deleted and deleting a movie removes only its own rows`() {
        val movie = movie()
        val actor = person(CAST)
        credits.addCast(movie.id, actor.id, null)

        assertThrows<DataIntegrityViolationException> { jdbc.update("delete from person where id = ?", actor.id) }

        jdbc.update("delete from movie where id = ?", movie.id)
        assertEquals(0, count("select count(*) from cast_credit where movie_id = ?", movie.id))
        assertEquals(0, count("select count(*) from movie_genre where movie_id = ?", movie.id))
        assertEquals(1, count("select count(*) from person where id = ?", actor.id))
        assertEquals(1, count("select count(*) from genre where code = 'DRAMA'"))
    }
}
