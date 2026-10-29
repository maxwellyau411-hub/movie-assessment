package moviecatalogue.catalogue.demo

import moviecatalogue.catalogue.artwork.MoviePosterRepository
import moviecatalogue.catalogue.artwork.PosterService
import moviecatalogue.catalogue.credit.CreditService
import moviecatalogue.catalogue.movie.MovieInput
import moviecatalogue.catalogue.movie.MovieRepository
import moviecatalogue.catalogue.movie.MovieService
import moviecatalogue.catalogue.person.PersonInput
import moviecatalogue.catalogue.person.PersonService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * Loads [DemoCatalogue] once, through the normal application services so seeds obey every model
 * rule. Off by default; Compose enables it with CATALOGUE_DEMO_DATA=true. Runs again only on an
 * empty catalogue: the first demo movie's fixed ID is the marker, and everything is one transaction.
 *
 * Posters are uploaded to media-service afterwards, through the same path as a user's upload. Any
 * demo movie still without a poster gets one on every start, so if media-service was not reachable
 * the first time, the next start fills the gap.
 */
@Component
@ConditionalOnBooleanProperty("catalogue.demo-data.enabled")
class DemoDataSeeder(
    private val movies: MovieService,
    private val movieRepository: MovieRepository,
    private val people: PersonService,
    private val credits: CreditService,
    private val posters: MoviePosterRepository,
    private val posterUploads: PosterService,
    private val transactions: TransactionTemplate,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        seed()
        attachMissingPosters()
    }

    /** Returns true if data was inserted, false if the demo catalogue was already present. */
    fun seed(): Boolean {
        val inserted =
            transactions.execute {
                if (movieRepository.existsById(DemoCatalogue.movies.first().id)) return@execute false
                insertCatalogue()
                true
            } == true
        log.info(if (inserted) "Demo catalogue inserted" else "Demo catalogue already present; nothing inserted")
        return inserted
    }

    /** confirmDuplicate: the demo deliberately contains duplicates (two "Paper Harbour", two "Sam Arden"). */
    private fun insertCatalogue() {
        val personIds = DemoCatalogue.people.associate { it.key to it.id }
        for (p in DemoCatalogue.people) {
            people.create(PersonInput(p.name, p.dateOfBirth, p.biography, p.categories), confirmDuplicate = true, id = p.id)
        }
        for (m in DemoCatalogue.movies) {
            movies.create(MovieInput(m.title, m.synopsis, m.releaseDate, m.runtimeMinutes, m.genres), confirmDuplicate = true, id = m.id)
            m.cast.forEach { (person, character) -> credits.addCast(m.id, personIds.getValue(person), character) }
            m.creators.forEach { (person, role) -> credits.addCreator(m.id, personIds.getValue(person), role) }
        }
    }

    /**
     * Returns how many posters were attached. Skips demo movies that were deleted or already have
     * a poster. A failure is logged, not thrown: the catalogue still starts without demo posters.
     */
    fun attachMissingPosters(): Int {
        var attached = 0
        for (movie in DemoCatalogue.movies) {
            if (!movieRepository.existsById(movie.id) || posters.existsById(movie.id)) continue
            try {
                val bytes = DemoPosterGenerator.png(movie.id, movie.title)
                posterUploads.upload(movie.id, "demo-poster.png", "image/png", bytes.size.toLong(), bytes.inputStream())
                attached++
            } catch (ex: RuntimeException) {
                log.warn("Demo posters not attached ({}); the next start tries again", ex.message)
                break
            }
        }
        if (attached > 0) log.info("Attached {} demo posters", attached)
        return attached
    }
}
