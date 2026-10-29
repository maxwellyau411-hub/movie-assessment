package moviecatalogue.media

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.imageio.ImageIO

@ConfigurationProperties("media")
data class MediaProperties(
    val root: Path,
)

/**
 * The three folders under one root (one Docker volume, so moves between them are atomic):
 * `staging` for uploads in progress, `originals` and `thumbnails` for stored images. File names
 * are always generated IDs; nothing the uploader sends ever becomes part of a path.
 */
@Component
class MediaFiles(
    properties: MediaProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val root: Path = properties.root.toAbsolutePath().normalize()
    val staging: Path = root.resolve("staging")
    val originals: Path = root.resolve("originals")
    val thumbnails: Path = root.resolve("thumbnails")

    init {
        listOf(staging, originals, thumbnails).forEach { Files.createDirectories(it) }
        // Leftovers from a crash mid-upload. Only this one instance writes here, so nothing is in use yet.
        Files.list(staging).use { files -> files.forEach(Files::deleteIfExists) }
    }

    fun newStagingFile(): Path = Files.createTempFile(staging, "upload-", ".part")

    fun original(
        id: UUID,
        format: ImageFormat,
    ): Path = originals.resolve("$id.${format.extension}")

    fun thumbnail(id: UUID): Path = thumbnails.resolve("$id.jpg")

    /** Moves a fully written file into place in one step, so a reader never sees half a file. */
    fun moveIn(
        staged: Path,
        target: Path,
    ) {
        Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE)
    }

    fun writeThumbnail(
        image: BufferedImage,
        target: Path,
    ) {
        val staged = newStagingFile()
        try {
            Files.newOutputStream(staged).use { check(ImageIO.write(image, "jpeg", it)) { "No JPEG writer" } }
            moveIn(staged, target)
        } finally {
            Files.deleteIfExists(staged)
        }
    }

    /** Removes every file an artwork ID can have. Missing files are fine, so this can be repeated. */
    fun deleteAll(id: UUID) {
        // listOf(...) matters: a Path is itself Iterable (of its name parts), so `list + path` would add those.
        (ImageFormat.entries.map { original(id, it) } + listOf(thumbnail(id))).forEach { path ->
            if (Files.deleteIfExists(path)) log.debug("Deleted {}", path.fileName)
        }
    }
}
