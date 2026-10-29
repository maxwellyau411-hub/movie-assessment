package moviecatalogue.catalogue.demo

import io.grpc.Status
import moviecatalogue.catalogue.CatalogueIntegrationTest
import moviecatalogue.catalogue.FakeMediaService
import moviecatalogue.catalogue.credit.CreatorRole
import moviecatalogue.catalogue.credit.CreditService
import moviecatalogue.catalogue.person.PersonCategory
import moviecatalogue.catalogue.person.PersonService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.io.ByteArrayInputStream
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestPropertySource(properties = ["catalogue.demo-data.enabled=true"])
class DemoDataSeederTest : CatalogueIntegrationTest() {
    @Autowired
    private lateinit var seeder: DemoDataSeeder

    @Autowired
    private lateinit var media: FakeMediaService

    @Autowired
    private lateinit var credits: CreditService

    @Autowired
    private lateinit var people: PersonService

    private val demoMovieIds = DemoCatalogue.movies.map { it.id }.toTypedArray()

    private fun demoMovieCount() = count("select count(*) from movie where id = any(?)", demoMovieIds)

    @Test
    fun `the demo catalogue is inserted once at startup and a second run changes nothing`() {
        assertEquals(DemoCatalogue.movies.size, demoMovieCount())
        val creditsBefore = count("select count(*) from cast_credit") + count("select count(*) from creator_credit")

        assertFalse(seeder.seed())

        assertEquals(DemoCatalogue.movies.size, demoMovieCount())
        assertEquals(creditsBefore, count("select count(*) from cast_credit") + count("select count(*) from creator_credit"))
    }

    private fun artworkId(movie: DemoCatalogue.DemoMovie): UUID? =
        jdbc.queryForList("select artwork_id from movie_poster where movie_id = ?", UUID::class.java, movie.id).singleOrNull()

    @Test
    fun `every demo movie's generated PNG poster was uploaded to media-service at startup`() {
        for (movie in DemoCatalogue.movies) {
            val upload = media.stored.getValue(artworkId(movie).toString())
            val image = ImageIO.read(ByteArrayInputStream(upload.bytes))

            assertEquals("image/png", upload.metadata.contentType)
            assertEquals(upload.bytes.size.toLong(), upload.metadata.declaredSize)
            assertEquals(DemoPosterGenerator.WIDTH to DemoPosterGenerator.HEIGHT, image.width to image.height)
        }
        assertEquals(0, seeder.attachMissingPosters(), "nothing missing")
    }

    @Test
    fun `a missing demo poster is attached on the next start, and media-service being down does not stop the start`() {
        val movie = DemoCatalogue.movies.last()
        jdbc.update("delete from movie_poster where movie_id = ?", movie.id)

        media.uploadFailure = Status.UNAVAILABLE
        assertEquals(0, seeder.attachMissingPosters())
        assertEquals(null, artworkId(movie))

        media.uploadFailure = null
        assertEquals(1, seeder.attachMissingPosters())
        assertTrue(media.stored.containsKey(artworkId(movie).toString()))
    }

    @Test
    fun `demo data covers the model's edge cases`() {
        val lantern = DemoCatalogue.movies.first { it.key == "lantern-keeper" }
        val ilse = DemoCatalogue.people.first { it.key == "ilse-varnholt" }
        val lanternCredits = credits.creditsForMovie(lantern.id)

        assertTrue(lanternCredits.cast.any { it.person.id == ilse.id })
        assertTrue(lanternCredits.creators.any { it.person.id == ilse.id && it.role == CreatorRole.DIRECTOR })
        assertEquals(setOf(PersonCategory.CAST, PersonCategory.CREATOR), people.get(ilse.id).categories)

        val oskar = DemoCatalogue.people.first { it.key == "oskar-fennimore" }
        assertEquals(0, count("select count(*) from creator_credit where person_id = ?", oskar.id))
        assertEquals(
            1,
            count(
                "select count(*) from creator_credit where role is null and movie_id = ?",
                DemoCatalogue.demoId("movie", "orbit-of-glass"),
            ),
        )
        assertEquals(2, count("select count(*) from movie where title = 'Paper Harbour' and id = any(?)", demoMovieIds))
        assertEquals(1, count("select count(*) from movie where release_date is null and id = any(?)", demoMovieIds))
        assertEquals(1, count("select count(*) from movie where release_date > date '2026-10-25' and id = any(?)", demoMovieIds))
    }
}
