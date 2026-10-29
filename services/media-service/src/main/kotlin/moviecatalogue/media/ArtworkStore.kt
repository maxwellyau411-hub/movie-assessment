package moviecatalogue.media

import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class UploadMetadata(
    val fileName: String?,
    val format: ImageFormat,
    val declaredSize: Long,
)

@Service
class ArtworkStore(
    private val files: MediaFiles,
    private val artworks: ArtworkRepository,
) {
    /**
     * Validates a fully received upload, then writes the thumbnail, moves the original into place
     * and records the row, in that order: a row never points at a missing file. If any step fails,
     * every file written for this ID is removed again, so a failed upload leaves nothing behind.
     */
    fun store(
        staged: Path,
        metadata: UploadMetadata,
    ): Artwork {
        val image = ImageProcessor.process(staged, metadata.format)
        val byteSize = Files.size(staged).toInt()
        val id = UUID.randomUUID()
        try {
            files.writeThumbnail(image.thumbnail, files.thumbnail(id))
            files.moveIn(staged, files.original(id, image.format))
            return artworks.save(
                Artwork(
                    id = id,
                    contentType = image.format.contentType,
                    fileName = metadata.fileName,
                    width = image.width,
                    height = image.height,
                    byteSize = byteSize,
                    thumbnailWidth = image.thumbnail.width,
                    thumbnailHeight = image.thumbnail.height,
                    createdAt = Instant.now(),
                ),
            )
        } catch (ex: Exception) {
            files.deleteAll(id)
            throw ex
        }
    }

    /**
     * Files first, then the row. If this stops half way, the row is still there and calling it
     * again finishes the job. Returns false when there was no such artwork (already deleted).
     */
    fun delete(id: UUID): Boolean {
        files.deleteAll(id)
        if (!artworks.existsById(id)) return false
        artworks.deleteById(id)
        return true
    }
}
