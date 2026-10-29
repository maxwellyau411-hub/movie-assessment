package moviecatalogue.media

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/** Describes one stored image. The bytes are files in [MediaFiles], named after [id]. */
@Entity
@Table(name = "artwork")
class Artwork(
    @Id
    val id: UUID,
    @Column(nullable = false, length = 20)
    val contentType: String,
    @Column(length = 255)
    val fileName: String?,
    @Column(nullable = false)
    val width: Int,
    @Column(nullable = false)
    val height: Int,
    @Column(nullable = false)
    val byteSize: Int,
    @Column(nullable = false)
    val thumbnailWidth: Int,
    @Column(nullable = false)
    val thumbnailHeight: Int,
    @Column(nullable = false)
    val createdAt: Instant,
) {
    val format: ImageFormat get() = checkNotNull(ImageFormat.ofContentType(contentType))

    /** Root-relative, served by [MediaWebConfiguration] and reached through nginx's /media/ route. */
    val fullUrl: String get() = "/media/artwork/$id.${format.extension}"
    val thumbnailUrl: String get() = "/media/thumbnails/$id.jpg"

    override fun equals(other: Any?) = other is Artwork && other.id == id

    override fun hashCode() = id.hashCode()
}

interface ArtworkRepository : JpaRepository<Artwork, UUID>
