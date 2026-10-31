package moviecatalogue.media

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Plain unit tests of the file layout: a temporary folder, no Spring context, no database. */
class MediaFilesTest {
    @TempDir
    lateinit var dir: Path

    private val id = UUID.fromString("8f0c4c1e-0000-4000-8000-000000000001")

    private fun files(root: Path = dir) = MediaFiles(MediaProperties(root))

    @Test
    fun `creates the three folders under the root`() {
        val files = files(dir.resolve("media"))
        assertEquals(
            listOf("originals", "staging", "thumbnails"),
            dir
                .resolve("media")
                .listDirectoryEntries()
                .map {
                    it.fileName.toString()
                }.sorted(),
        )
        assertTrue(Files.isDirectory(files.staging))
    }

    @Test
    fun `a root with dot-dot parts is normalised`() {
        val files = files(dir.resolve("a/../media"))
        assertEquals(dir.resolve("media/staging").toAbsolutePath().normalize(), files.staging)
    }

    @Test
    fun `starting up removes uploads left in staging by a crash, and keeps stored images`() {
        Files.createDirectories(dir.resolve("staging"))
        Files.createDirectories(dir.resolve("originals"))
        dir.resolve("staging/upload-1.part").writeText("half a file")
        dir.resolve("originals/$id.png").writeText("stored")

        files()

        assertTrue(dir.resolve("staging").listDirectoryEntries().isEmpty())
        assertEquals("stored", dir.resolve("originals/$id.png").readText())
    }

    @Test
    fun `file names come only from the generated ID and the checked format`() {
        val files = files()
        assertEquals(files.originals.resolve("$id.jpg"), files.original(id, ImageFormat.JPEG))
        assertEquals(files.originals.resolve("$id.png"), files.original(id, ImageFormat.PNG))
        assertEquals(files.originals.resolve("$id.webp"), files.original(id, ImageFormat.WEBP))
        assertEquals(files.thumbnails.resolve("$id.jpg"), files.thumbnail(id))
    }

    @Test
    fun `a staged upload is moved into place and is gone from staging`() {
        val files = files()
        val staged = files.newStagingFile()
        staged.writeText("bytes")

        files.moveIn(staged, files.original(id, ImageFormat.PNG))

        assertFalse(staged.exists())
        assertEquals("bytes", files.original(id, ImageFormat.PNG).readText())
    }

    @Test
    fun `a thumbnail is written as a readable JPEG and leaves nothing in staging`() {
        val files = files()
        files.writeThumbnail(BufferedImage(32, 48, BufferedImage.TYPE_INT_RGB), files.thumbnail(id))

        val written = ImageIO.read(files.thumbnail(id).toFile())
        assertEquals(32 to 48, written.width to written.height)
        assertTrue(files.staging.listDirectoryEntries().isEmpty())
    }

    @Test
    fun `delete removes the original in any format and the thumbnail, can be repeated, and leaves other images alone`() {
        val files = files()
        val other = UUID.fromString("8f0c4c1e-0000-4000-8000-000000000002")
        files.original(id, ImageFormat.WEBP).writeText("original")
        files.thumbnail(id).writeText("thumbnail")
        files.original(other, ImageFormat.WEBP).writeText("someone else's")

        files.deleteAll(id)
        files.deleteAll(id)

        assertFalse(files.original(id, ImageFormat.WEBP).exists())
        assertFalse(files.thumbnail(id).exists())
        assertTrue(files.original(other, ImageFormat.WEBP).exists())
    }

    @Test
    fun `content types map to formats exactly, and anything else is unsupported`() {
        assertEquals(ImageFormat.JPEG, ImageFormat.ofContentType("image/jpeg"))
        assertEquals(ImageFormat.PNG, ImageFormat.ofContentType("image/png"))
        assertEquals(ImageFormat.WEBP, ImageFormat.ofContentType("image/webp"))
        assertEquals(null, ImageFormat.ofContentType("image/gif"))
        assertEquals(null, ImageFormat.ofContentType(""))
    }
}
