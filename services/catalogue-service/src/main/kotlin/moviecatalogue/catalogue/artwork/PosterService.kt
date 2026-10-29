package moviecatalogue.catalogue.artwork

import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.persistence.OptimisticLockException
import moviecatalogue.catalogue.common.NotFoundException
import moviecatalogue.catalogue.common.staleVersion
import moviecatalogue.catalogue.movie.MovieRepository
import moviecatalogue.catalogue.movie.MovieView
import moviecatalogue.catalogue.movie.toView
import org.springframework.context.ApplicationEventPublisher
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import java.io.InputStream
import java.time.Clock
import java.util.UUID

/**
 * Published inside a transaction when a movie stops using an artwork (replaced, or the movie was
 * deleted). [ArtworkCleanup] deletes it from media-service only after that transaction commits, so
 * a rolled-back change never loses the image it still points to.
 */
data class ArtworkReleased(
    val artworkId: UUID,
)

@Component
class ArtworkCleanup(
    private val media: MediaClient,
) {
    @TransactionalEventListener // default phase: after commit
    fun onReleased(event: ArtworkReleased) = media.deleteQuietly(event.artworkId)
}

@Service
class PosterService(
    private val media: MediaClient,
    private val movies: MovieRepository,
    private val posters: MoviePosterRepository,
    private val entityManager: EntityManager,
    private val transactions: TransactionTemplate,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    /**
     * Uploads to media-service first, then points the movie at the new artwork. The order keeps
     * the old poster until the new one is safely stored: a refused or failed upload changes
     * nothing. If saving the new reference fails (say the movie was deleted meanwhile), the new
     * artwork is deleted again so no unused image stays behind. The old artwork is deleted only
     * after the new reference is committed.
     */
    fun upload(
        movieId: UUID,
        fileName: String?,
        contentType: String?,
        size: Long,
        content: InputStream,
    ): MovieView {
        // Checked before any bytes are sent; checked again under the lock below.
        if (!movies.existsById(movieId)) throw NotFoundException("Movie", movieId)
        val artwork = media.upload(fileName, contentType, size, content)
        try {
            return checkNotNull(transactions.execute { attach(movieId, artwork) })
        } catch (ex: Exception) {
            media.deleteQuietly(artwork.id)
            throw ex
        }
    }

    /**
     * Same locking as a credit change (D27): the movie's version goes up, because its poster is
     * part of it, and the row lock serialises two uploads for the same movie.
     */
    private fun attach(
        movieId: UUID,
        artwork: StoredArtwork,
    ): MovieView {
        val movie = movies.findById(movieId).orElseThrow { NotFoundException("Movie", movieId) }
        try {
            entityManager.lock(movie, LockModeType.PESSIMISTIC_FORCE_INCREMENT)
        } catch (_: OptimisticLockException) {
            throw staleVersion("Movie", movieId) // edited between the read and the lock
        } catch (_: ObjectOptimisticLockingFailureException) {
            throw staleVersion("Movie", movieId)
        }
        val now = clock.instant()
        movie.touch(now)
        val current = posters.findById(movieId).orElse(null)
        if (current == null) {
            posters.save(MoviePoster(movieId, artwork, now))
        } else {
            events.publishEvent(ArtworkReleased(current.artworkId))
            current.replaceWith(artwork, now)
        }
        movies.flush()
        return movie.toView()
    }
}
