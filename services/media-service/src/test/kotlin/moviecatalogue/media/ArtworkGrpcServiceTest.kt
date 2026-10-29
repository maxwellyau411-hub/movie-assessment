package moviecatalogue.media

import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import moviecatalogue.contracts.media.v1.ArtworkMetadata
import moviecatalogue.contracts.media.v1.ArtworkServiceGrpc.ArtworkServiceBlockingStub
import moviecatalogue.contracts.media.v1.ArtworkServiceGrpc.ArtworkServiceStub
import moviecatalogue.contracts.media.v1.DeleteArtworkRequest
import moviecatalogue.contracts.media.v1.UploadArtworkRequest
import moviecatalogue.contracts.media.v1.UploadArtworkResponse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import moviecatalogue.contracts.media.v1.Artwork as ArtworkMessage

class ArtworkGrpcServiceTest : MediaIntegrationTest() {
    @Autowired
    private lateinit var stub: ArtworkServiceStub

    @Autowired
    private lateinit var blocking: ArtworkServiceBlockingStub

    @Autowired
    private lateinit var files: MediaFiles

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val http = HttpClient.newHttpClient()

    /** How the client ends the stream: normally, or by cancelling part way. */
    private enum class End { COMPLETE, CANCEL }

    private fun upload(
        bytes: ByteArray,
        contentType: String = "image/png",
        declaredSize: Long = bytes.size.toLong(),
        metadataFirst: Boolean = true,
        end: End = End.COMPLETE,
    ): CompletableFuture<ArtworkMessage> {
        val result = CompletableFuture<ArtworkMessage>()
        val requests =
            stub.uploadArtwork(
                object : StreamObserver<UploadArtworkResponse> {
                    override fun onNext(value: UploadArtworkResponse) {
                        result.complete(value.artwork)
                    }

                    override fun onError(t: Throwable) {
                        result.completeExceptionally(t)
                    }

                    override fun onCompleted() {}
                },
            )
        val metadata =
            UploadArtworkRequest
                .newBuilder()
                .setMetadata(
                    ArtworkMetadata
                        .newBuilder()
                        .setFileName("poster.png")
                        .setContentType(contentType)
                        .setDeclaredSize(declaredSize),
                ).build()
        if (metadataFirst) requests.onNext(metadata)
        bytes.asList().chunked(64 * 1024).forEach {
            requests.onNext(UploadArtworkRequest.newBuilder().setChunk(ByteString.copyFrom(it.toByteArray())).build())
        }
        when (end) {
            End.COMPLETE -> requests.onCompleted()
            End.CANCEL -> requests.onError(Status.CANCELLED.asRuntimeException())
        }
        return result
    }

    private fun stored(bytes: ByteArray) = upload(bytes).get(10, TimeUnit.SECONDS)

    private fun rejected(future: CompletableFuture<ArtworkMessage>): Status {
        val failure = assertThrows<ExecutionException> { future.get(10, TimeUnit.SECONDS) }.cause
        return (failure as StatusRuntimeException).status
    }

    private fun get(path: String) =
        http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofByteArray())

    private fun fileCount(folder: Path) = Files.list(folder).use { it.count() }

    private fun assertNothingLeftBehind(originalsBefore: Long) {
        // The server finishes its clean-up just after it answers, so allow it a moment.
        val deadline = System.nanoTime() + 5_000_000_000
        while (fileCount(files.staging) > 0 && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(0, fileCount(files.staging), "staging folder")
        assertEquals(originalsBefore, fileCount(files.originals), "originals folder")
    }

    @Test
    fun `stores a valid upload with a 320 px thumbnail and serves both with long cache headers`() {
        val bytes = TestImages.png(600, 900)
        val artwork = stored(bytes)

        assertEquals("image/png", artwork.contentType)
        assertEquals(600 to 900, artwork.width to artwork.height)
        assertEquals(320 to 480, artwork.thumbnailWidth to artwork.thumbnailHeight)
        assertEquals(bytes.size.toLong(), artwork.byteSize)
        assertEquals("/media/artwork/${artwork.id}.png", artwork.fullUrl)
        assertEquals("/media/thumbnails/${artwork.id}.jpg", artwork.thumbnailUrl)
        assertEquals("poster.png", jdbc.queryForObject("select file_name from artwork where id = ?::uuid", String::class.java, artwork.id))

        val full = get(artwork.fullUrl)
        assertEquals(200, full.statusCode())
        assertContentEquals(bytes, full.body())
        assertEquals("image/png", full.headers().firstValue("Content-Type").get())
        assertEquals("max-age=31536000, public, immutable", full.headers().firstValue("Cache-Control").get())
        assertEquals("nosniff", full.headers().firstValue("X-Content-Type-Options").get())

        val thumbnail = get(artwork.thumbnailUrl)
        assertEquals("image/jpeg", thumbnail.headers().firstValue("Content-Type").get())
        val image = ImageIO.read(ByteArrayInputStream(thumbnail.body()))
        assertEquals(320 to 480, image.width to image.height)
        assertEquals(0, fileCount(files.staging))
    }

    @Test
    fun `stores JPEG and WebP with their own extension`() {
        assertTrue(upload(TestImages.jpeg(), "image/jpeg").get(10, TimeUnit.SECONDS).fullUrl.endsWith(".jpg"))
        assertTrue(upload(TestImages.webpLossless, "image/webp").get(10, TimeUnit.SECONDS).fullUrl.endsWith(".webp"))
    }

    @Test
    fun `every rejected upload answers INVALID_ARGUMENT with a message for the user and leaves no files`() {
        val png = TestImages.png()
        val cases =
            listOf(
                upload(TestImages.jpeg(), contentType = "image/png") to "content is JPEG, but it was sent as PNG",
                upload(TestImages.svg, contentType = "image/svg+xml") to "Unsupported image format",
                upload(TestImages.gif, contentType = "image/png") to "Unsupported image format",
                upload(ByteArray(0), declaredSize = 0) to "empty",
                upload(png, declaredSize = ImageProcessor.MAX_BYTES + 1) to "larger than 5 MB",
                upload(png, declaredSize = png.size - 1L) to "larger than its declared size",
                upload(png, declaredSize = png.size + 1L) to "ended after",
                upload(png, metadataFirst = false) to "before its metadata",
                upload(TestImages.pngHeaderClaiming(6_000, 6_000)) to "too large",
                upload(png.copyOf(png.size / 2)) to "not a valid PNG",
            )
        val originalsBefore = fileCount(files.originals)

        for ((future, message) in cases) {
            val status = rejected(future)
            assertEquals(Status.Code.INVALID_ARGUMENT, status.code, message)
            assertContains(status.description!!, message)
        }
        assertNothingLeftBehind(originalsBefore)
    }

    @Test
    fun `an upload is refused as soon as it passes 5 MB, even if it declared exactly 5 MB`() {
        val originalsBefore = fileCount(files.originals)
        val tooBig = TestImages.png() + ByteArray(ImageProcessor.MAX_BYTES.toInt())

        val status = rejected(upload(tooBig, declaredSize = ImageProcessor.MAX_BYTES))

        assertEquals(Status.Code.INVALID_ARGUMENT, status.code)
        assertContains(status.description!!, "larger than 5 MB")
        assertNothingLeftBehind(originalsBefore)
    }

    @Test
    fun `an interrupted upload keeps nothing`() {
        val originalsBefore = fileCount(files.originals)

        upload(TestImages.png(400, 600), end = End.CANCEL)

        assertNothingLeftBehind(originalsBefore)
    }

    @Test
    fun `delete removes the row and both files, and repeating it succeeds with deleted false`() {
        val artwork = stored(TestImages.png())
        val request = DeleteArtworkRequest.newBuilder().setId(artwork.id).build()

        assertTrue(blocking.deleteArtwork(request).deleted)
        assertFalse(blocking.deleteArtwork(request).deleted)

        assertEquals(0, jdbc.queryForObject("select count(*) from artwork where id = ?::uuid", Int::class.java, artwork.id))
        assertEquals(404, get(artwork.fullUrl).statusCode())
        assertEquals(404, get(artwork.thumbnailUrl).statusCode())
        val bad = assertThrows<StatusRuntimeException> { blocking.deleteArtwork(DeleteArtworkRequest.newBuilder().setId("../x").build()) }
        assertEquals(Status.Code.INVALID_ARGUMENT, bad.status.code)
    }

    @Test
    fun `the stored display name keeps only the last path part`() {
        assertEquals("poster.png", displayName("C:\\fakepath\\poster.png"))
        assertEquals("poster.png", displayName("../../etc/poster.png"))
        assertEquals(null, displayName("  "))
        assertEquals(255, displayName("a".repeat(300))!!.length)
    }

    @Test
    fun `only the two image folders are served`() {
        assertEquals(404, get("/media/staging/anything").statusCode())
        assertTrue(get("/media/artwork/%2e%2e/thumbnails/x.jpg").statusCode() in 400..499)
        assertEquals(404, get("/").statusCode())
    }
}
