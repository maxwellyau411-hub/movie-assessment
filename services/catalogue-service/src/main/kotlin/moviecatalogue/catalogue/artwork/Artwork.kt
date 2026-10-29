package moviecatalogue.catalogue.artwork

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/** What media-service returns for a stored image. */
data class StoredArtwork(
    val id: UUID,
    val width: Int,
    val height: Int,
    val fullUrl: String,
    val thumbnailUrl: String,
)

/** Which artwork a movie currently uses. The bytes are in media-service, never here. */
@Entity
@Table(name = "movie_poster")
class MoviePoster(
    @Id
    @Column(name = "movie_id")
    val movieId: UUID,
    artwork: StoredArtwork,
    now: Instant,
) {
    @Column(nullable = false)
    var artworkId: UUID = artwork.id
        protected set

    @Column(nullable = false)
    var width: Int = artwork.width
        protected set

    @Column(nullable = false)
    var height: Int = artwork.height
        protected set

    @Column(nullable = false, length = 200)
    var fullUrl: String = artwork.fullUrl
        protected set

    @Column(nullable = false, length = 200)
    var thumbnailUrl: String = artwork.thumbnailUrl
        protected set

    @Column(nullable = false)
    var createdAt: Instant = now
        protected set

    /** Updates the row in place: a delete plus insert of the same key in one flush would collide. */
    fun replaceWith(
        artwork: StoredArtwork,
        now: Instant,
    ) {
        artworkId = artwork.id
        width = artwork.width
        height = artwork.height
        fullUrl = artwork.fullUrl
        thumbnailUrl = artwork.thumbnailUrl
        createdAt = now
    }

    override fun equals(other: Any?) = other is MoviePoster && other.movieId == movieId

    override fun hashCode() = movieId.hashCode()
}

interface MoviePosterRepository : JpaRepository<MoviePoster, UUID>
