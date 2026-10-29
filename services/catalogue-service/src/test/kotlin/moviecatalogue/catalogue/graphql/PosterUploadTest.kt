package moviecatalogue.catalogue.graphql

import io.grpc.Status
import moviecatalogue.catalogue.TestPng
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** uploadMoviePoster over real multipart HTTP, against [moviecatalogue.catalogue.FakeMediaService]. */
class PosterUploadTest : GraphQlApiTest() {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private fun movie() = createMovie("Poster Test ${UUID.randomUUID()}")

    private fun read(movieId: String) = gql("""{ movie(id: "$movieId") { version artwork { thumbnailUrl fullUrl width height } } }""")

    @Test
    fun `an upload attaches the artwork, raises the movie version, and passes the bytes and declared type on`() {
        val movieId = movie()
        val bytes = TestPng.bytes(60, 90)

        val result = uploadPoster(movieId, bytes)

        assertTrue(result.errors.isEmpty, result.toString())
        val upload = media.stored.values.single()
        assertTrue(bytes.contentEquals(upload.bytes))
        assertEquals("image/png", upload.metadata.contentType)
        assertEquals("poster.png", upload.metadata.fileName)
        assertEquals(bytes.size.toLong(), upload.metadata.declaredSize)

        val artworkId = media.stored.keys.single()
        assertEquals("/media/thumbnails/$artworkId.jpg", result.data("uploadMoviePoster.artwork.thumbnailUrl").asString())
        assertEquals("/media/artwork/$artworkId.png", result.data("uploadMoviePoster.artwork.fullUrl").asString())
        assertEquals(
            60 to 90,
            result.data("uploadMoviePoster.artwork.width").asInt() to result.data("uploadMoviePoster.artwork.height").asInt(),
        )
        assertEquals(1, result.data("uploadMoviePoster.version").asInt(), "created at 0, raised by the upload")
        assertEquals(result.data("uploadMoviePoster.artwork"), read(movieId).data("movie.artwork"))
    }

    @Test
    fun `a second upload replaces the poster, and only then is the old artwork deleted`() {
        val movieId = movie()
        uploadPoster(movieId, TestPng.bytes())
        val first = media.stored.keys.single()

        val second = uploadPoster(movieId, TestPng.bytes(120, 180))

        assertTrue(second.errors.isEmpty, second.toString())
        assertEquals(listOf(first), media.deleteRequests)
        val current = media.stored.keys.single()
        assertEquals("/media/artwork/$current.png", read(movieId).data("movie.artwork.fullUrl").asString())
        assertEquals(2, read(movieId).data("movie.version").asInt())
    }

    @Test
    fun `a refused file gives VALIDATION_ERROR on file with media-service's message, and keeps the current poster`() {
        val movieId = movie()
        uploadPoster(movieId, TestPng.bytes())
        val before = read(movieId)
        media.uploadFailure = Status.INVALID_ARGUMENT.withDescription("The file content is JPEG, but it was sent as PNG")

        val refused = uploadPoster(movieId, TestPng.bytes())

        assertEquals("VALIDATION_ERROR", refused.errorCode(), refused.toString())
        val fieldError =
            refused.errors
                .path(0)
                .path("extensions")
                .path("fieldErrors")
                .path(0)
        assertEquals("file", fieldError.path("field").asString())
        assertEquals("The file content is JPEG, but it was sent as PNG", fieldError.path("message").asString())
        assertEquals(before.json, read(movieId).json, "same poster, same version")
        assertTrue(media.deleteRequests.isEmpty(), "the old artwork is kept")
    }

    @Test
    fun `media-service not answering gives SERVICE_UNAVAILABLE and changes nothing`() {
        val movieId = movie()
        media.uploadFailure = Status.UNAVAILABLE

        val result = uploadPoster(movieId, TestPng.bytes())

        assertEquals("SERVICE_UNAVAILABLE", result.errorCode(), result.toString())
        assertTrue(read(movieId).data("movie.artwork").isNull)
        assertEquals(0, read(movieId).data("movie.version").asInt())
    }

    @Test
    fun `an unknown movie gives NOT_FOUND before any bytes are sent`() {
        val result = uploadPoster(UUID.randomUUID().toString(), TestPng.bytes())

        assertEquals("NOT_FOUND", result.errorCode(), result.toString())
        assertTrue(media.stored.isEmpty())
    }

    @Test
    fun `if the movie is deleted while the file uploads, the new artwork is deleted again`() {
        val movieId = movie()
        media.beforeUploadAnswer = { jdbc.update("delete from movie where id = ?::uuid", movieId) }

        val result = uploadPoster(movieId, TestPng.bytes())

        assertEquals("NOT_FOUND", result.errorCode(), result.toString())
        assertEquals(1, media.deleteRequests.size)
        assertTrue(media.stored.isEmpty(), "no unused image is left in media-service")
    }

    @Test
    fun `a movie is still deleted when media-service cannot delete its artwork`() {
        val movieId = movie()
        uploadPoster(movieId, TestPng.bytes())
        media.deleteFailure = Status.UNAVAILABLE

        val result = gql("""mutation { deleteMovie(id: "$movieId", expectedVersion: 1) }""")

        assertEquals(movieId, result.data("deleteMovie").asString(), result.toString())
        assertTrue(read(movieId).data("movie").isNull)
    }

    @Test
    fun `a malformed multipart request is a 400, not a 500`() {
        val operations =
            mapper.writeValueAsString(
                mapOf("query" to "mutation(\$f: Upload!) { uploadMoviePoster(movieId: \"x\", file: \$f) { id } }"),
            )

        val response = multipart(operations, """{"0": ["query"]}""", TestPng.bytes())

        assertEquals(400, response.statusCode(), response.body())
        assertTrue(response.body().contains("VALIDATION_ERROR"), response.body())
    }

    @Test
    fun `a file over 5 MB is refused with HTTP 413 before it reaches media-service`() {
        val movieId = movie()

        val result = uploadPoster(movieId, TestPng.bytes() + ByteArray(5 * 1024 * 1024))

        assertEquals(413, result.status)
        assertTrue(media.stored.isEmpty())
    }
}
